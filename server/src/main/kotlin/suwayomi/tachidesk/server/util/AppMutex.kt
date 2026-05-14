package suwayomi.tachidesk.server.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.json.JsonMapper
import io.javalin.json.fromJsonString
import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import suwayomi.tachidesk.global.impl.AboutDataClass
import suwayomi.tachidesk.server.RuntimeMode
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.util.Browser.openInBrowser
import suwayomi.tachidesk.server.util.ExitCode.MutexCheckFailedAnotherAppRunning
import suwayomi.tachidesk.server.util.ExitCode.MutexCheckFailedTachideskRunning
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.TimeUnit

object AppMutex {
    private val logger = KotlinLogging.logger {}

    private enum class AppMutexState(
        val stat: Int,
    ) {
        Clear(0),
        TachideskInstanceRunning(1),
        OtherApplicationRunning(2),
    }

    private val jsonMapper: JsonMapper by injectLazy()

    private fun appIP(): String {
        val configuredIp = serverConfig.ip.value
        return if (configuredIp == "0.0.0.0") "127.0.0.1" else configuredIp
    }

    private fun checkAppMutex(): AppMutexState {
        val appIP = appIP()
        val client =
            OkHttpClient
                .Builder()
                .connectTimeout(200, TimeUnit.MILLISECONDS)
                .build()

        val request =
            Builder()
                .url("http://$appIP:${serverConfig.port.value}/api/v1/settings/about/")
                .build()

        val response =
            try {
                client
                    .newCall(request)
                    .execute()
                    .body
                    .string()
            } catch (e: IOException) {
                return AppMutexState.Clear
            }

        return try {
            jsonMapper.fromJsonString<AboutDataClass>(response)
            AppMutexState.TachideskInstanceRunning
        } catch (e: IOException) {
            AppMutexState.OtherApplicationRunning
        }
    }

    fun handleAppMutex() {
        if (System.getProperty("suwayomi.skipAppMutex")?.toBoolean() == true ||
            System.getenv("SUWAYOMI_SKIP_APP_MUTEX")?.toBoolean() == true
        ) {
            logger.info { "Skipping app mutex check" }
            return
        }

        val hasEmbeddedManifest = !System.getProperty("manatan.runtimeBootstrapManifest").isNullOrBlank()
        if (RuntimeMode.isRuntimeOnly() && hasEmbeddedManifest) {
            logger.info { "Skipping app mutex check for embedded runtime-only startup" }
            return
        }

        when (checkAppMutex()) {
            AppMutexState.Clear -> {
                logger.info { "Mutex status is clear, Resuming startup." }
            }

            AppMutexState.TachideskInstanceRunning -> {
                val appIP = appIP()
                logger.info { "Another instance of Suwayomi-Server is running on $appIP:${serverConfig.port.value}" }

                logger.info { "Probably user thought Suwayomi-Server is closed so, opening webUI in browser again." }
                openInBrowser()

                logger.info { "Aborting startup." }

                shutdownApp(MutexCheckFailedTachideskRunning)
            }

            AppMutexState.OtherApplicationRunning -> {
                val appIP = appIP()
                logger.error { "A non Suwayomi-Server application is running on $appIP:${serverConfig.port.value}, aborting startup." }
                shutdownApp(MutexCheckFailedAnotherAppRunning)
            }
        }
    }
}
