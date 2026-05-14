package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MemoryCookieStoreTest {
    @Test
    fun `get only returns cookies that actually match the request url`() {
        val store = MemoryCookieStore()
        val url = "https://raw1001.net/ranking/week/1".toHttpUrl()

        store.addAll(
            url,
            listOf(
                Cookie.Builder().name("valid").value("1").domain("raw1001.net").path("/").build(),
                Cookie.Builder().name("sibling-domain").value("1").domain("1001.net").path("/").build(),
                Cookie.Builder().name("wrong-path").value("1").domain("raw1001.net").path("/private").build(),
            ),
        )

        assertEquals(listOf("valid"), store.get(url).map { it.name })
    }
}
