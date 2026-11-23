package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

enum class ApiConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    UNAUTHORIZED
}

data class HealthCheckResult(
    val status: ApiConnectionStatus,
    val message: String,
    val httpCode: Int? = null
)

data class ScreenshotUploadResult(
    val status: ApiConnectionStatus,
    val success: Boolean,
    val message: String,
    val serverInfo: String? = null,
    val httpCode: Int? = null
)

/**
 * Optional FastAPI client for NeuroBot.
 * Communicates strictly with:
 * - GET /health
 * - POST /upload-screenshot (multipart/form-data field: "image")
 * Authenticates via the X-API-Token header. Never logs or exposes the token.
 */
class NeuroBotApiClient(
    private val callFactory: Call.Factory = defaultOkHttpClient()
) {

    companion object {
        const val HEADER_API_TOKEN = "X-API-Token"
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()

        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        fun parseBaseUrl(rawUrl: String): HttpUrl? {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https" && parsed.scheme != "http") return null
            return parsed
        }
    }

    suspend fun checkHealth(baseUrl: String, apiToken: String): HealthCheckResult {
        return withContext(Dispatchers.IO) {
            val cleanToken = apiToken.trim()
            val parsedBase = parseBaseUrl(baseUrl)
                ?: return@withContext HealthCheckResult(
                    status = ApiConnectionStatus.DISCONNECTED,
                    message = if (baseUrl.isBlank()) {
                        "API Server URL is empty."
                    } else {
                        "Invalid API Server URL. Use full https:// or http:// address."
                    }
                )

            if (cleanToken.isEmpty()) {
                return@withContext HealthCheckResult(
                    status = ApiConnectionStatus.UNAUTHORIZED,
                    message = "API Token is required before testing connection."
                )
            }

            val healthUrl = parsedBase.newBuilder()
                .addPathSegment("health")
                .build()

            val request = Request.Builder()
                .url(healthUrl)
                .get()
                .header(HEADER_API_TOKEN, cleanToken)
                .build()

            try {
                callFactory.newCall(request).execute().use { response ->
                    val code = response.code
                    val bodyText = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> {
                            val detail = extractSummaryFromBody(bodyText)
                            HealthCheckResult(
                                status = ApiConnectionStatus.CONNECTED,
                                message = if (detail.isNotEmpty()) {
                                    "Connected ($detail)"
                                } else {
                                    "Connected to server (/health OK)"
                                },
                                httpCode = code
                            )
                        }
                        code == 401 -> HealthCheckResult(
                            status = ApiConnectionStatus.UNAUTHORIZED,
                            message = "Unauthorized (HTTP 401): Invalid API Token.",
                            httpCode = code
                        )
                        code == 403 -> HealthCheckResult(
                            status = ApiConnectionStatus.UNAUTHORIZED,
                            message = "Unauthorized (HTTP 403): Access denied.",
                            httpCode = code
                        )
                        else -> HealthCheckResult(
                            status = ApiConnectionStatus.DISCONNECTED,
                            message = "Server returned HTTP $code on /health.",
                            httpCode = code
                        )
                    }
                }
            } catch (e: Exception) {
                HealthCheckResult(
                    status = ApiConnectionStatus.DISCONNECTED,
                    message = formatNetworkError(e)
                )
            }
        }
    }

    suspend fun uploadScreenshot(
        baseUrl: String,
        apiToken: String,
        jpegBytes: ByteArray,
        filename: String = "neurobot_${System.currentTimeMillis()}.jpg"
    ): ScreenshotUploadResult {
        return withContext(Dispatchers.IO) {
            val cleanToken = apiToken.trim()
            val parsedBase = parseBaseUrl(baseUrl)
                ?: return@withContext ScreenshotUploadResult(
                    status = ApiConnectionStatus.DISCONNECTED,
                    success = false,
                    message = "Invalid or empty API Server URL."
                )

            if (cleanToken.isEmpty()) {
                return@withContext ScreenshotUploadResult(
                    status = ApiConnectionStatus.UNAUTHORIZED,
                    success = false,
                    message = "API Token is empty."
                )
            }

            if (jpegBytes.isEmpty()) {
                return@withContext ScreenshotUploadResult(
                    status = ApiConnectionStatus.DISCONNECTED,
                    success = false,
                    message = "Empty screenshot buffer."
                )
            }

            val uploadUrl = parsedBase.newBuilder()
                .addPathSegment("upload-screenshot")
                .build()

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    filename,
                    jpegBytes.toRequestBody(JPEG_MEDIA_TYPE)
                )
                .build()

            val request = Request.Builder()
                .url(uploadUrl)
                .post(multipartBody)
                .header(HEADER_API_TOKEN, cleanToken)
                .build()

            try {
                callFactory.newCall(request).execute().use { response ->
                    val code = response.code
                    val bodyText = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> {
                            val info = extractSummaryFromBody(bodyText)
                            ScreenshotUploadResult(
                                status = ApiConnectionStatus.CONNECTED,
                                success = true,
                                message = if (info.isNotEmpty()) {
                                    "Server received frame ($info)"
                                } else {
                                    "Server received frame (${jpegBytes.size / 1024} KB)"
                                },
                                serverInfo = info.ifEmpty { null },
                                httpCode = code
                            )
                        }
                        code == 401 -> ScreenshotUploadResult(
                            status = ApiConnectionStatus.UNAUTHORIZED,
                            success = false,
                            message = "Unauthorized (HTTP 401): Invalid API Token.",
                            httpCode = code
                        )
                        code == 403 -> ScreenshotUploadResult(
                            status = ApiConnectionStatus.UNAUTHORIZED,
                            success = false,
                            message = "Unauthorized (HTTP 403): Access denied.",
                            httpCode = code
                        )
                        else -> ScreenshotUploadResult(
                            status = ApiConnectionStatus.DISCONNECTED,
                            success = false,
                            message = "Upload failed (HTTP $code).",
                            httpCode = code
                        )
                    }
                }
            } catch (e: Exception) {
                ScreenshotUploadResult(
                    status = ApiConnectionStatus.DISCONNECTED,
                    success = false,
                    message = formatNetworkError(e)
                )
            }
        }
    }

    private fun extractSummaryFromBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val json = JSONObject(trimmed)
            val parts = mutableListOf<String>()
            json.optString("status").takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            json.optString("filename").takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            if (json.has("size_bytes")) {
                parts.add("${json.optLong("size_bytes")}B")
            } else if (json.has("size")) {
                parts.add("${json.optLong("size")}B")
            }
            if (json.has("width") && json.has("height")) {
                parts.add("${json.optInt("width")}x${json.optInt("height")}")
            }
            parts.joinToString(" • ").ifEmpty { trimmed.take(80) }
        } catch (_: Exception) {
            trimmed.take(80)
        }
    }

    private fun formatNetworkError(e: Exception): String {
        return when (e) {
            is UnknownHostException -> "Host not found. Check your Cloudflare Tunnel URL."
            is ConnectException -> "Connection refused. Ensure FastAPI & tunnel are running."
            is SocketTimeoutException -> "Connection timed out while reaching server."
            is SSLException -> "TLS/SSL handshake failed. Check HTTPS URL."
            is IOException -> "Network error: ${e.message ?: "Unable to reach server"}"
            else -> "Request error: ${e.message ?: "Unknown failure"}"
        }
    }
}
