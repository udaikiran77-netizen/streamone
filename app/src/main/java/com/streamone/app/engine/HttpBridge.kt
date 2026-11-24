package com.streamone.app.engine

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp executor behind the WebView harness's RPC bridge.
 *
 * Requests arrive from JS (assets/engine/harness.js) already normalised to
 * the Vega sandbox's wire shape: a URL, a method, an ordered list of header
 * pairs, and a body that is either absent, text, or base64. Responses keep
 * their headers as an ordered pair list — duplicate names (above all
 * multiple Set-Cookie headers) survive, which the per-provider cookie jar
 * and axios's set-cookie handling depend on. Bodies are returned as raw
 * bytes; the JS side base64-decodes them.
 *
 * Everything is asynchronous (OkHttp enqueue): the WebView's JS thread only
 * ever waits on promises, never on the network.
 */
object HttpBridge {

    /** Default User-Agent for repository downloads (manifests, modules). */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"

    private val followClient: OkHttpClient = buildClient(followRedirects = true)
    private val noFollowClient: OkHttpClient = buildClient(followRedirects = false)

    private fun buildClient(followRedirects: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .build()

    /** Shared client for plain GETs from the repository layer. */
    fun client(): OkHttpClient = followClient

    /** One RPC HTTP request, as decoded from the harness payload. */
    data class BridgeRequest(
        val url: String,
        val method: String,
        /** Ordered header pairs; duplicate names are allowed. */
        val headers: List<Pair<String, String>>,
        val bodyText: String?,
        val bodyBase64: String?,
        val contentType: String?,
        val timeoutMs: Long,
        val followRedirects: Boolean
    )

    /** One RPC HTTP response. [headers] preserves order and duplicates. */
    data class BridgeResponse(
        val status: Int,
        val statusText: String,
        /** Final URL after any redirects OkHttp followed. */
        val finalUrl: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray
    ) {
        fun headerValues(name: String): List<String> =
            headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
    }

    /**
     * Executes [request] asynchronously. The callback receives a failure
     * only for transport-level errors (DNS, timeouts, TLS, malformed URL);
     * any HTTP status — including 4xx/5xx — is a successful [BridgeResponse],
     * matching fetch() semantics.
     */
    fun executeAsync(request: BridgeRequest, callback: (Result<BridgeResponse>) -> Unit) {
        val call: Call = try {
            buildCall(request)
        } catch (e: Exception) {
            callback(Result.failure(e))
            return
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val pairs = ArrayList<Pair<String, String>>()
                        for ((name, value) in it.headers) {
                            pairs.add(name to value)
                        }
                        callback(
                            Result.success(
                                BridgeResponse(
                                    status = it.code,
                                    statusText = it.message,
                                    finalUrl = it.request.url.toString(),
                                    headers = pairs,
                                    body = it.body?.bytes() ?: ByteArray(0)
                                )
                            )
                        )
                    }
                } catch (e: Exception) {
                    callback(Result.failure(e))
                }
            }
        })
    }

    private fun buildCall(request: BridgeRequest): Call {
        val builder = Request.Builder().url(request.url)

        var contentType = request.contentType
        for ((name, value) in request.headers) {
            builder.addHeader(name, value)
            if (name.equals("Content-Type", ignoreCase = true)) contentType = value
        }

        val method = request.method.uppercase()
        val hasBody = method == "POST" || method == "PUT" || method == "PATCH" ||
            request.bodyText != null || request.bodyBase64 != null
        val bodyBytes: ByteArray? = when {
            request.bodyBase64 != null ->
                android.util.Base64.decode(request.bodyBase64, android.util.Base64.DEFAULT)
            request.bodyText != null -> request.bodyText.toByteArray(Charsets.UTF_8)
            hasBody -> ByteArray(0)
            else -> null
        }
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> if (bodyBytes != null) {
                builder.delete(bodyBytes.toRequestBody(contentType?.toMediaTypeOrNull()))
            } else {
                builder.delete()
            }
            "POST", "PUT", "PATCH" -> {
                val body = (bodyBytes ?: ByteArray(0))
                    .toRequestBody(contentType?.toMediaTypeOrNull())
                when (method) {
                    "POST" -> builder.post(body)
                    "PUT" -> builder.put(body)
                    else -> builder.patch(body)
                }
            }
            else -> builder.method(
                method,
                bodyBytes?.toRequestBody(contentType?.toMediaTypeOrNull())
            )
        }

        val base = if (request.followRedirects) followClient else noFollowClient
        val client = if (request.timeoutMs > 0) {
            base.newBuilder()
                .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            base
        }
        return client.newCall(builder.build())
    }
}
