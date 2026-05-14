package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.withLock
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MemoryCookieStore : RuntimeCookieStore {
    private val cookieMap = mutableMapOf<String, List<Cookie>>()
    private val lock = ReentrantLock()

    override fun addAll(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        lock.withLock {
            for (cookie in cookies) {
                val cookiesForDomain = cookieMap[cookie.domain].orEmpty().toMutableList()
                val pos = cookiesForDomain.indexOfFirst { it.name == cookie.name }
                if (pos == -1) {
                    cookiesForDomain.add(cookie)
                } else {
                    cookiesForDomain[pos] = cookie
                }
                cookieMap[cookie.domain] = cookiesForDomain
            }
        }
    }

    override fun removeAll(): Boolean =
        lock.withLock {
            val wasNotEmpty = cookieMap.isEmpty()
            cookieMap.clear()
            wasNotEmpty
        }

    fun remove(uri: URI) {
        val url = uri.toURL()
        lock.withLock {
            cookieMap.remove(url.host)
        }
    }

    override fun remove(url: HttpUrl) {
        lock.withLock {
            cookieMap.keys
                .filter { domain ->
                    domain == url.host || url.host.endsWith(".$domain")
                }.forEach(cookieMap::remove)
        }
    }

    override fun get(uri: URI): List<HttpCookie> {
        val url = uri.toURL()
        return get(url.toHttpUrlOrNull()!!).map { it.toHttpCookie() }
    }

    override fun get(url: HttpUrl): List<Cookie> =
        lock.withLock {
            cookieMap.values
                .flatMap { it }
                .filter { !it.hasExpired() && it.matches(url) }
        }

    override fun add(
        uri: URI?,
        cookie: HttpCookie,
    ) {
        lock.withLock {
            val okCookie = cookie.toCookie(uri?.host) ?: return@withLock
            val cookiesForDomain = cookieMap[okCookie.domain].orEmpty().toMutableList()
            val pos = cookiesForDomain.indexOfFirst { it.name == okCookie.name }
            if (pos == -1) {
                cookiesForDomain.add(okCookie)
            } else {
                cookiesForDomain[pos] = okCookie
            }
            cookieMap[okCookie.domain] = cookiesForDomain
        }
    }

    override fun getCookies(): List<HttpCookie> =
        lock.withLock {
            cookieMap.values.flatMap { cookies ->
                cookies.map { it.toHttpCookie() }
            }
        }

    override fun getStoredCookies(): List<Cookie> =
        lock.withLock {
            cookieMap.values.flatMap { it }.filter { !it.hasExpired() }
        }

    override fun getURIs(): List<URI> =
        lock.withLock {
            cookieMap.keys.map { URI("http://$it") }
        }

    override fun remove(
        uri: URI?,
        cookie: HttpCookie,
    ): Boolean =
        lock.withLock {
            val okCookie = cookie.toCookie(uri?.host) ?: return@withLock false
            val cookies = cookieMap[okCookie.domain].orEmpty()
            val index = cookies.indexOfFirst { it.name == okCookie.name && it.path == okCookie.path }
            if (index >= 0) {
                val newList = cookies.toMutableList()
                newList.removeAt(index)
                cookieMap[okCookie.domain] = newList.toList()
                true
            } else {
                false
            }
        }

    private fun Cookie.hasExpired() = System.currentTimeMillis() >= expiresAt

    private fun HttpCookie.toCookie(urlDomain: String?): Cookie? {
        return Cookie
            .Builder()
            .name(name)
            .value(value)
            .domain((domain ?: urlDomain ?: return null).removePrefix("."))
            .path(path ?: "/")
            .also {
                if (maxAge != -1L) {
                    it.expiresAt(System.currentTimeMillis() + maxAge.seconds.inWholeMilliseconds)
                } else {
                    it.expiresAt(Long.MAX_VALUE)
                }
                if (secure) {
                    it.secure()
                }
                if (isHttpOnly) {
                    it.httpOnly()
                }
                if (domain != null && !domain.startsWith('.')) {
                    it.hostOnlyDomain(domain.removePrefix("."))
                }
            }.build()
    }

    private fun Cookie.toHttpCookie(): HttpCookie {
        val it = this
        return HttpCookie(it.name, it.value).apply {
            domain = if (hostOnly) it.domain else "." + it.domain
            path = it.path
            secure = it.secure
            maxAge =
                if (it.persistent) {
                    -1
                } else {
                    (it.expiresAt.milliseconds - System.currentTimeMillis().milliseconds).inWholeSeconds
                }
            isHttpOnly = it.httpOnly
        }
    }
}
