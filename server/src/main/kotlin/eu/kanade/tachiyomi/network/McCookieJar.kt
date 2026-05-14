package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class McCookieJar : CookieJar {
    private val logger = KotlinLogging.logger {}
    private val manager = CookieManager.getInstance()

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        if (!ENABLE_NATIVE_COOKIE || cookies.isEmpty()) {
            return
        }

        val urlString = url.toString()
        logger.debug { "[Cookie] saveFromResponse url=$urlString cookies=${cookies.size}" }
        cookies.forEach { cookie ->
            manager.setCookie(urlString, cookie.toString())
        }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!ENABLE_NATIVE_COOKIE) {
            return emptyList()
        }

        val cookieHeader = manager.getCookie(url.toString()).orEmpty()
        if (cookieHeader.isBlank()) {
            return emptyList()
        }

        val cookies = cookieHeader.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
        logger.debug { "[Cookie] loadForRequest url=$url cookies=${cookies.size}" }
        return cookies
    }

    companion object {
        @Volatile
        var ENABLE_NATIVE_COOKIE: Boolean =
            System.getProperty("suwayomi.native.cookie")?.toBooleanStrictOrNull()
                ?: System.getenv("SUWAYOMI_NATIVE_COOKIE")?.toBooleanStrictOrNull()
                ?: false
    }
}
