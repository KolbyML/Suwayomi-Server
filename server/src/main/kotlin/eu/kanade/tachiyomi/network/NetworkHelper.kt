package eu.kanade.tachiyomi.network

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.network.MemoryCookieStore
import eu.kanade.tachiyomi.network.RuntimeCookieStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource
import suwayomi.tachidesk.server.RuntimeMode
import suwayomi.tachidesk.server.plugin.ServerPluginRegistry
import suwayomi.tachidesk.server.serverConfig
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class NetworkHelper(
    context: Context,
) {
    companion object {
        private const val DEFAULT_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MOBILE_SAFARI_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }

    //    private val preferences: PreferencesHelper by injectLazy()

//    private val cacheDir = File(context.cacheDir, "network_cache")

//    private val cacheSize = 5L * 1024 * 1024 // 5 MiB

    // Tachidesk -->
    val cookieStore: RuntimeCookieStore =
        if (RuntimeMode.isRuntimeOnly()) {
            MemoryCookieStore()
        } else {
            PersistentCookieStore(context)
        }

    init {
        CookieHandler.setDefault(
            CookieManager(cookieStore, CookiePolicy.ACCEPT_ALL),
        )
    }
    // Tachidesk <--

    private val userAgent = MutableStateFlow(configuredUserAgent())
    val userAgentFlow = userAgent.asStateFlow()

    @Volatile
    private var sharedClient: OkHttpClient = buildClient()

    fun defaultUserAgentProvider(): String = userAgent.value
    fun setDefaultUserAgent(value: String) {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) {
            userAgent.value = trimmed
        }
    }

    init {
        @OptIn(DelicateCoroutinesApi::class)
        userAgent
            .drop(1)
            .onEach {
                GetCatalogueSource.unregisterAllCatalogueSources() // need to reset the headers
            }.launchIn(GlobalScope)

        @OptIn(DelicateCoroutinesApi::class)
        serverConfig.iosUserAgentMode
            .drop(1)
            .onEach {
                userAgent.value = configuredUserAgent()
            }.launchIn(GlobalScope)

        @OptIn(DelicateCoroutinesApi::class)
        serverConfig.iosCloudflareBypassEnabled
            .drop(1)
            .onEach {
                applyNativeCookieSetting(it)
                sharedClient = buildClient()
            }.launchIn(GlobalScope)

        @OptIn(DelicateCoroutinesApi::class)
        serverConfig.iosReceiveTimeoutSeconds
            .drop(1)
            .onEach {
                sharedClient = buildClient()
            }.launchIn(GlobalScope)
    }

    private fun configuredUserAgent(): String =
        when (serverConfig.iosUserAgentMode.value.trim().uppercase()) {
            "MOBILE_SAFARI" -> MOBILE_SAFARI_USER_AGENT
            else -> DEFAULT_DESKTOP_USER_AGENT
        }

    private fun applyNativeCookieSetting(enabled: Boolean) {
        val effective = enabled && android.webkit.McCookieManager.isNativeBridgeAvailable()
        McCookieJar.ENABLE_NATIVE_COOKIE = effective
        System.setProperty("suwayomi.native.cookie", effective.toString())
    }

    private fun cookieJar(): CookieJar {
        applyNativeCookieSetting(serverConfig.iosCloudflareBypassEnabled.value)
        return if (McCookieJar.ENABLE_NATIVE_COOKIE) {
            McCookieJar()
        } else {
            PersistentCookieJar(cookieStore)
        }
    }

    private fun buildClient(): OkHttpClient {
        val receiveTimeoutSeconds = serverConfig.iosReceiveTimeoutSeconds.value.toLong()
        val builder =
            OkHttpClient
                .Builder()
                .cookieJar(cookieJar())
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(receiveTimeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .cache(
                    Cache(
                        directory = Files.createTempDirectory("tachidesk_network_cache").toFile(),
                        maxSize = 5L * 1024 * 1024, // 5 MiB
                    ),
                ).addInterceptor(UncaughtExceptionInterceptor())
                .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

        val networkLogger =
            object : HttpLoggingInterceptor.Logger {
                val logger = KotlinLogging.logger { }

                override fun log(message: String) {
                    logger.debug { message }
                }
            }
        val httpLoggingInterceptor =
            HttpLoggingInterceptor(networkLogger).apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        builder.addNetworkInterceptor(httpLoggingInterceptor)
        builder.eventListenerFactory(McLoggingEventListener.Factory(networkLogger))

        builder.addInterceptor(
            CloudflareInterceptor(
                setUserAgent = { userAgent.value = it },
                clearCookiesForUrl = { url -> cookieStore.remove(url) },
            ),
        )
        ServerPluginRegistry.configureNetworkClient(builder)

        return builder.build()
    }

    val client: OkHttpClient
        get() = sharedClient

    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient
        get() = sharedClient
}
