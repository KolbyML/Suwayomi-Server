package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl
import java.net.CookieStore

interface RuntimeCookieStore : CookieStore {
    fun addAll(
        url: HttpUrl,
        cookies: List<Cookie>,
    )

    fun get(url: HttpUrl): List<Cookie>

    fun getStoredCookies(): List<Cookie>

    fun remove(url: HttpUrl)
}
