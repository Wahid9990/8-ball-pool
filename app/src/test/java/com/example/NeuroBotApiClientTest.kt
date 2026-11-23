package com.example

import com.example.data.remote.ApiConnectionStatus
import com.example.data.remote.NeuroBotApiClient
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class NeuroBotApiClientTest {

    private class FakeCallFactory(
        private val handler: (Request) -> Response
    ) : Call.Factory {
        var lastRequest: Request? = null

        override fun newCall(request: Request): Call {
            lastRequest = request
            return object : Call {
                private var executed = false
                private var cancelled = false

                override fun request(): Request = request
                override fun execute(): Response {
                    executed = true
                    return handler(request)
                }

                override fun enqueue(responseCallback: Callback) {
                    try {
                        responseCallback.onResponse(this, execute())
                    } catch (e: java.io.IOException) {
                        responseCallback.onFailure(this, e)
                    }
                }

                override fun cancel() {
                    cancelled = true
                }

                override fun isExecuted(): Boolean = executed
                override fun isCanceled(): Boolean = cancelled
                override fun clone(): Call = newCall(request)
                override fun timeout(): Timeout = Timeout.NONE
            }
        }
    }

    private fun buildResponse(request: Request, code: Int, jsonBody: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(jsonBody.toResponseBody("application/json".toMediaType()))
            .build()
    }

    @Test
    fun checkHealth_sendsApiTokenHeaderAndReturnsConnectedOn200() = runTest {
        val factory = FakeCallFactory { req ->
            buildResponse(req, 200, """{"status":"ok"}""")
        }
        val client = NeuroBotApiClient(factory)

        val result = client.checkHealth(
            baseUrl = "https://magic-roy-jonathan-ladies.trycloudflare.com/",
            apiToken = "secret-token-123"
        )

        assertEquals(ApiConnectionStatus.CONNECTED, result.status)
        assertEquals(200, result.httpCode)
        assertEquals(
            "https://magic-roy-jonathan-ladies.trycloudflare.com/health",
            factory.lastRequest?.url.toString()
        )
        assertEquals("secret-token-123", factory.lastRequest?.header("X-API-Token"))
    }

    @Test
    fun checkHealth_returnsUnauthorizedOnHttp401WithoutLeakingToken() = runTest {
        val factory = FakeCallFactory { req ->
            buildResponse(req, 401, """{"detail":"Invalid token"}""")
        }
        val client = NeuroBotApiClient(factory)
        val secretToken = "super-secret-xyz"

        val result = client.checkHealth(
            baseUrl = "https://magic-roy-jonathan-ladies.trycloudflare.com",
            apiToken = secretToken
        )

        assertEquals(ApiConnectionStatus.UNAUTHORIZED, result.status)
        assertEquals(401, result.httpCode)
        assertFalse(result.message.contains(secretToken))
    }

    @Test
    fun checkHealth_returnsDisconnectedOnNetworkFailure() = runTest {
        val factory = FakeCallFactory {
            throw UnknownHostException("magic-roy-jonathan-ladies.trycloudflare.com")
        }
        val client = NeuroBotApiClient(factory)

        val result = client.checkHealth(
            baseUrl = "https://magic-roy-jonathan-ladies.trycloudflare.com",
            apiToken = "token"
        )

        assertEquals(ApiConnectionStatus.DISCONNECTED, result.status)
        assertTrue(result.message.contains("Host not found"))
    }

    @Test
    fun uploadScreenshot_postsMultipartImageFieldWithApiToken() = runTest {
        val factory = FakeCallFactory { req ->
            buildResponse(req, 200, """{"filename":"frame.jpg","size_bytes":12}""")
        }
        val client = NeuroBotApiClient(factory)
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)

        val result = client.uploadScreenshot(
            baseUrl = "https://magic-roy-jonathan-ladies.trycloudflare.com",
            apiToken = "token-abc",
            jpegBytes = fakeJpeg,
            filename = "test_frame.jpg"
        )

        assertTrue(result.success)
        assertEquals(ApiConnectionStatus.CONNECTED, result.status)
        val req = factory.lastRequest!!
        assertEquals(
            "https://magic-roy-jonathan-ladies.trycloudflare.com/upload-screenshot",
            req.url.toString()
        )
        assertEquals("token-abc", req.header("X-API-Token"))
        assertTrue(req.body is MultipartBody)

        val buf = Buffer()
        req.body!!.writeTo(buf)
        val rawBody = buf.readUtf8()
        assertTrue(rawBody.contains("name=\"image\""))
        assertTrue(rawBody.contains("filename=\"test_frame.jpg\""))
    }

    @Test
    fun parseBaseUrl_rejectsNonHttpSchemesAndInvalidStrings() {
        assertEquals(null, NeuroBotApiClient.parseBaseUrl(""))
        assertEquals(null, NeuroBotApiClient.parseBaseUrl("not-a-url"))
        assertEquals(null, NeuroBotApiClient.parseBaseUrl("ftp://example.com"))
        assertEquals(
            "https://magic-roy-jonathan-ladies.trycloudflare.com",
            NeuroBotApiClient.parseBaseUrl("https://magic-roy-jonathan-ladies.trycloudflare.com/")?.toString()?.trimEnd('/')
        )
    }
}
