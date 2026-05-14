package xyz.nulldev.androidcompat.webkit

import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import java.net.CookieHandler
import java.net.HttpCookie
import java.net.URI

@Suppress("DEPRECATION")
class CookieManagerImpl : CookieManager() {
    private var acceptCookie = true
    private var acceptThirdPartyCookies = true
    private var allowFileSchemeCookies = false

    private fun cookieHandler(): java.net.CookieManager {
        val existing = CookieHandler.getDefault()
        if (existing is java.net.CookieManager) {
            return existing
        }

        val manager = java.net.CookieManager()
        CookieHandler.setDefault(manager)
        return manager
    }

    override fun setAcceptCookie(accept: Boolean) {
        acceptCookie = accept
    }

    override fun acceptCookie(): Boolean = acceptCookie

    override fun setAcceptThirdPartyCookies(
        webview: WebView?,
        accept: Boolean,
    ) {
        acceptThirdPartyCookies = accept
    }

    override fun acceptThirdPartyCookies(webview: WebView?): Boolean = acceptThirdPartyCookies

    override fun setCookie(
        url: String,
        value: String?,
    ) {
        if (!acceptCookie || value == null) {
            return
        }

        val uri =
            if (url.startsWith("http")) {
                URI(url)
            } else {
                URI("http://$url")
            }

        HttpCookie.parse(value).forEach {
            cookieHandler().cookieStore.add(uri, it)
        }
    }

    override fun setCookie(
        url: String,
        value: String?,
        callback: ValueCallback<Boolean>?,
    ) {
        setCookie(url, value)
        callback?.onReceiveValue(true)
    }

    override fun getCookie(url: String): String {
        if (!acceptCookie) {
            return ""
        }

        val uri =
            if (url.startsWith("http")) {
                URI(url)
            } else {
                URI("http://$url")
            }
        return cookieHandler()
            .cookieStore
            .get(uri)
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Deprecated("Deprecated in Java")
    override fun removeSessionCookie() {}

    override fun removeSessionCookies(callback: ValueCallback<Boolean>?) {}

    @Deprecated("Deprecated in Java")
    override fun removeExpiredCookie() {}

    @Deprecated("Deprecated in Java")
    override fun removeAllCookie() {
        cookieHandler().cookieStore.removeAll()
    }

    override fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        val removedCookies = cookieHandler().cookieStore.removeAll()
        callback?.onReceiveValue(removedCookies)
    }

    override fun hasCookies(): Boolean = cookieHandler().cookieStore.cookies.isNotEmpty()

    override fun flush() {}

    override fun allowFileSchemeCookiesImpl(): Boolean = allowFileSchemeCookies

    override fun setAcceptFileSchemeCookiesImpl(accept: Boolean) {
        allowFileSchemeCookies = acceptCookie
    }
}
