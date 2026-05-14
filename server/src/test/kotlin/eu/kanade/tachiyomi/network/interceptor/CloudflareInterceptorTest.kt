package eu.kanade.tachiyomi.network.interceptor

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CloudflareInterceptorTest {
    @Test
    fun `cloudflare edge errors clear cookies and retry once`() {
        val chain = mockk<Interceptor.Chain>()
        val request = Request.Builder().url("https://raw1001.net/ranking/week/1").build()
        val first =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(520)
                .message("Unknown Error")
                .header("Server", "cloudflare")
                .body("Server Error".toResponseBody())
                .build()
        val second =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Server", "cloudflare")
                .body("ok".toResponseBody())
                .build()
        var clearedUrl = request.url

        every { chain.request() } returns request
        every { chain.proceed(request) } returnsMany listOf(first, second)

        val interceptor =
            CloudflareInterceptor(
                setUserAgent = {},
                clearCookiesForUrl = { clearedUrl = it },
            )

        interceptor.intercept(chain).use { result ->
            assertEquals(200, result.code)
            assertEquals(request.url, clearedUrl)
        }

        verify(exactly = 2) { chain.proceed(request) }
    }
}
