package com.streamone.app.engine

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Synchronous HTTP executor behind the JS `__nativeHttp` bridge.
 *
 * The axios shim in assets/engine/axios-shim.js serialises each request to
 * JSON: { url, method, headers, data(string|null), timeout(ms), responseType }
 * and this object performs it with OkHttp, returning a JSON string:
 * { status, statusText, headers, body } — plus `bodyBase64: true` when the
 * provider asked for an arraybuffer, or { status: 0, error } on transport
 * failure so the shim can reject like a real network error.
 *
 * Everything here runs on the provider engine's single JS thread: the bridge
 * call blocks until the response arrives, which is exactly what the
 * synchronous QuickJS bridge requires.
 */
object HttpBridge {

    /** Default User-Agent (same Edge UA the Vega template sends). */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Shared client for plain GETs from the repository layer. */
    fun client(): OkHttpClient = baseClient

    fun execute(requestJson: String): String {
        val out = JSONObject()
        try {
            val request = JSONObject(requestJson)
            val url = request.optString("url")
            if (url.isBlank()) {
                out.put("status", 0)
                out.put("error", "Missing URL in native HTTP request")
                return out.toString()
            }
            val method = request.optString("method", "GET").uppercase()
            val timeoutMs = request.optLong("timeout", 0L)
            val responseType = request.optString("responseType", "")

            val builder = Request.Builder().url(url)

            var hasUserAgent = false
            var contentType: String? = null
            val headersJson = request.optJSONObject("headers")
            if (headersJson != null) {
                val keys = headersJson.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val value = headersJson.opt(name)
                    if (value == null || value == JSONObject.NULL) continue
                    val text = value.toString()
                    builder.header(name, text)
                    if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                    if (name.equals("Content-Type", ignoreCase = true)) contentType = text
                }
            }
            if (!hasUserAgent) {
                builder.header("User-Agent", DEFAULT_USER_AGENT)
            }

            val data: String? = if (request.isNull("data")) null else request.optString("data", "")
            when (method) {
                "GET" -> builder.get()
                "HEAD" -> builder.head()
                "DELETE" -> builder.delete()
                "POST", "PUT", "PATCH" -> {
                    val body = (data ?: "").toRequestBody(contentType?.toMediaTypeOrNull())
                    when (method) {
                        "POST" -> builder.post(body)
                        "PUT" -> builder.put(body)
                        else -> builder.patch(body)
                    }
                }
                else -> builder.method(method, null)
            }

            val callClient = if (timeoutMs > 0) {
                baseClient.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
            } else {
                baseClient
            }

            callClient.newCall(builder.build()).execute().use { response ->
                out.put("status", response.code)
                out.put("statusText", response.message)
                val responseHeaders = JSONObject()
                for ((name, value) in response.headers) {
                    responseHeaders.put(name, value)
                }
                out.put("headers", responseHeaders)
                if (responseType == "arraybuffer") {
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    out.put("body", Base64.getEncoder().encodeToString(bytes))
                    out.put("bodyBase64", true)
                } else {
                    out.put("body", response.body?.string() ?: "")
                }
            }
        } catch (e: Exception) {
            out.put("status", 0)
            out.put("error", e.message ?: e.javaClass.simpleName)
        }
        return out.toString()
    }
}
