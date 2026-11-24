package com.streamone.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Vega-style provider runtime: provider bundles execute inside one hidden
 * WebView (the harness page in assets/engine/), exactly the architecture
 * the Vega app uses for its sandbox WebView. The page has no direct network
 * access of its own — harness.js shims fetch() and axios onto an RPC bridge
 * implemented here:
 *
 *   JS -> Kotlin : __nativeRpc.rpc(id, payloadJson) for async operations
 *                  (http, openWebView, crypto), plus synchronous helpers
 *                  (kvStore, getBaseUrl, getCookies) and result()/ready().
 *   Kotlin -> JS : __rpcResolve(id, json) / __rpcReject(id, message) for
 *                  RPC replies; __harness.loadModule / __harness.callProvider
 *                  to run provider code. Provider call results return via
 *                  __nativeRpc.result(callId, json) — real promises, no
 *                  polling anywhere.
 *
 * HTTP is performed by [HttpBridge] (OkHttp, async) with cookies from the
 * per-provider [ProviderCookieJar] and Vega's commonHeaders applied as
 * defaults, mirroring the Vega host.
 *
 * All WebView interaction happens on the main thread; the WebView is
 * created lazily and kept for the process lifetime.
 */
class WebViewEngine(
    context: Context,
    private val cookieJar: ProviderCookieJar
) {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rpcExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "streamone-rpc").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var readyDeferred: CompletableDeferred<Unit>? = null

    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val kvStores = ConcurrentHashMap<String, KvStore>()
    private val kvDir = File(appContext.filesDir, "kvstore")

    // ------------------------------------------------------------ public API

    /**
     * Evaluates one provider module in the harness and registers its
     * exports under (providerKey, moduleName). Throws ProviderEngineException
     * with the JS error when evaluation fails.
     */
    suspend fun loadModule(providerKey: String, moduleName: String, source: String) {
        ensureReady()
        val js = "__harness.loadModule(" +
            "${quote(providerKey)}, ${quote(moduleName)}, ${quote(source)})"
        val raw = evaluate(js)
        val text = decodeJsString(raw)
        val result = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ProviderEngineException("Module $moduleName: unreadable load result")
        }
        if (!result.optBoolean("ok", false)) {
            throw ProviderEngineException(
                "Module $moduleName failed to evaluate: ${result.optString("error", "unknown error")}"
            )
        }
    }

    /**
     * Invokes one exported provider function and returns the raw result JSON
     * ({ok, value|error}) produced by the harness. Times out after
     * [CALL_TIMEOUT_MS] with a clear error instead of hanging.
     */
    suspend fun callProvider(
        providerKey: String,
        moduleName: String,
        exportName: String,
        argsJson: String
    ): String {
        ensureReady()
        val callId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingCalls[callId] = deferred
        try {
            evaluate(
                "__harness.callProvider(" +
                    "${quote(callId)}, ${quote(providerKey)}, ${quote(moduleName)}, " +
                    "${quote(exportName)}, ${quote(argsJson)})"
            )
            return try {
                withTimeout(CALL_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw ProviderEngineException(
                    "Provider call $moduleName.$exportName timed out after " +
                        "${CALL_TIMEOUT_MS / 1000}s"
                )
            }
        } finally {
            pendingCalls.remove(callId)
        }
    }

    // ------------------------------------------------------------ WebView setup

    private suspend fun ensureReady() {
        val existing = readyDeferred
        if (webView != null && existing != null && existing.isCompleted) return
        val deferred = synchronized(this) {
            if (webView == null) {
                val fresh = CompletableDeferred<Unit>()
                readyDeferred = fresh
                mainHandler.post { createWebView() }
                fresh
            } else {
                readyDeferred ?: CompletableDeferred<Unit>().also { readyDeferred = it }
            }
        }
        try {
            withTimeout(READY_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            synchronized(this) {
                if (readyDeferred === deferred) {
                    readyDeferred = null
                    webView = null
                }
            }
            throw ProviderEngineException("Provider engine WebView failed to start")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        // Runs on the main thread.
        try {
            val wv = WebView(appContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.allowFileAccess = true
            CookieManager.getInstance().setAcceptCookie(true)
            wv.addJavascriptInterface(NativeRpc(), "__nativeRpc")
            wv.webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    if (view === webView) {
                        webView = null
                        readyDeferred = null
                    }
                    return true
                }
            }
            webView = wv
            wv.loadUrl("file:///android_asset/engine/harness.html")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create engine WebView", e)
            readyDeferred?.completeExceptionally(
                ProviderEngineException("Provider engine WebView failed to start: ${e.message}")
            )
            readyDeferred = null
            webView = null
        }
    }

    private suspend fun evaluate(js: String): String {
        val wv = webView ?: throw ProviderEngineException("Provider engine is not running")
        return suspendCancellableCoroutine { cont ->
            mainHandler.post {
                try {
                    wv.evaluateJavascript(js) { value ->
                        if (cont.isActive) cont.resume(value ?: "")
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    }

    private fun evaluateFireAndForget(js: String) {
        val wv = webView ?: return
        mainHandler.post {
            try {
                wv.evaluateJavascript(js, null)
            } catch (e: Exception) {
                // The JS side may be gone (renderer restart); nothing to do.
            }
        }
    }

    private fun resolveRpc(id: String, jsonText: String) {
        evaluateFireAndForget("__rpcResolve(${quote(id)}, ${quote(jsonText)})")
    }

    private fun rejectRpc(id: String, message: String) {
        evaluateFireAndForget("__rpcReject(${quote(id)}, ${quote(message)})")
    }

    private fun quote(value: String): String = JSONObject.quote(value)

    /** evaluateJavascript returns JS strings JSON-encoded; unwrap one level. */
    private fun decodeJsString(raw: String): String {
        if (raw.isEmpty() || raw == "null") return ""
        return try {
            val value = JSONTokener(raw).nextValue()
            if (value is String) value else raw
        } catch (e: Exception) {
            raw
        }
    }

    // ------------------------------------------------------------ RPC handlers

    private fun handleRpc(id: String, payloadJson: String) {
        val payload = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            rejectRpc(id, "Malformed RPC payload")
            return
        }
        when (payload.optString("kind")) {
            "http" -> handleHttp(id, payload)
            "openWebView" -> {
                val providerKey = payload.optString("providerKey")
                val url = payload.optString("url")
                val options = payload.optJSONObject("options")
                scope.launch {
                    val result = openWebView(url, options)
                    // Cookies the page earned (e.g. WAF clearance) join the
                    // provider's jar, like in Vega's host.
                    val cookies = result.optString("cookies")
                    if (cookies.isNotBlank()) {
                        cookieJar.mergeCookieString(
                            providerKey,
                            result.optString("url", url),
                            cookies
                        )
                    }
                    resolveRpc(id, result.toString())
                }
            }
            "cryptoDigest" -> {
                try {
                    val algorithm = payload.optString("algorithm")
                    val data = payload.optString("data")
                    val encoding = payload.optString("encoding", "hex")
                    val digest = MessageDigest.getInstance(normalizeAlgorithm(algorithm))
                        .digest(data.toByteArray(Charsets.UTF_8))
                    val text = if (encoding.equals("base64", ignoreCase = true)) {
                        android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
                    } else {
                        digest.joinToString("") { "%02x".format(it) }
                    }
                    resolveRpc(id, quote(text))
                } catch (e: Exception) {
                    rejectRpc(id, e.message ?: "cryptoDigest failed")
                }
            }
            "cryptoRandom" -> {
                try {
                    val count = payload.optInt("byteCount", 0).coerceIn(0, 65536)
                    val bytes = ByteArray(count)
                    SecureRandom().nextBytes(bytes)
                    resolveRpc(
                        id,
                        quote(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    )
                } catch (e: Exception) {
                    rejectRpc(id, e.message ?: "cryptoRandom failed")
                }
            }
            else -> rejectRpc(id, "Unknown RPC kind: ${payload.optString("kind")}")
        }
    }

    private fun normalizeAlgorithm(algorithm: String): String =
        when (algorithm.uppercase().replace("-", "")) {
            "SHA1" -> "SHA-1"
            "SHA256" -> "SHA-256"
            "SHA384" -> "SHA-384"
            "SHA512" -> "SHA-512"
            "MD5" -> "MD5"
            else -> algorithm
        }

    private fun handleHttp(id: String, payload: JSONObject) {
        val providerKey = payload.optString("providerKey")
        val url = payload.optString("url")
        if (url.isBlank()) {
            rejectRpc(id, "Missing URL in HTTP RPC")
            return
        }
        val headers = ArrayList<Pair<String, String>>()
        val headerArray = payload.optJSONArray("headers")
        if (headerArray != null) {
            for (i in 0 until headerArray.length()) {
                val pair = headerArray.optJSONArray(i) ?: continue
                if (pair.length() >= 2) {
                    headers.add(pair.optString(0) to pair.optString(1))
                }
            }
        }
        // Vega's commonHeaders act as defaults: only fill in what the
        // provider did not set itself.
        for ((name, value) in COMMON_HEADERS) {
            if (headers.none { it.first.equals(name, ignoreCase = true) }) {
                headers.add(name to value)
            }
        }
        // Per-provider cookie jar: attach stored cookies unless the provider
        // set a Cookie header of its own.
        if (headers.none { it.first.equals("Cookie", ignoreCase = true) }) {
            cookieJar.cookieHeaderFor(providerKey, url)?.let { headers.add("Cookie" to it) }
        }

        var bodyText: String? = null
        var bodyBase64: String? = null
        var contentType: String? = null
        val body = payload.optJSONObject("body")
        if (body != null) {
            when (body.optString("kind")) {
                "text" -> bodyText = body.optString("value", "")
                "base64" -> {
                    bodyBase64 = body.optString("value", "")
                    contentType = body.optString("contentType").ifBlank { null }
                }
            }
        }

        val request = HttpBridge.BridgeRequest(
            url = url,
            method = payload.optString("method", "GET"),
            headers = headers,
            bodyText = bodyText,
            bodyBase64 = bodyBase64,
            contentType = contentType,
            timeoutMs = payload.optLong("timeoutMs", 0L),
            followRedirects = payload.optString("redirect", "follow") != "manual"
        )
        HttpBridge.executeAsync(request) { result ->
            result.fold(
                onSuccess = { response ->
                    val setCookies = response.headerValues("Set-Cookie")
                    if (setCookies.isNotEmpty()) {
                        cookieJar.storeFromResponse(providerKey, response.finalUrl, setCookies)
                    }
                    val out = JSONObject()
                    out.put("status", response.status)
                    out.put("statusText", response.statusText)
                    out.put("url", response.finalUrl)
                    val pairs = JSONArray()
                    for ((name, value) in response.headers) {
                        val pair = JSONArray()
                        pair.put(name)
                        pair.put(value)
                        pairs.put(pair)
                    }
                    out.put("headers", pairs)
                    out.put(
                        "bodyBase64",
                        android.util.Base64.encodeToString(response.body, android.util.Base64.NO_WRAP)
                    )
                    resolveRpc(id, out.toString())
                },
                onFailure = { error ->
                    rejectRpc(id, error.message ?: error.javaClass.simpleName)
                }
            )
        }
    }

    // ------------------------------------------------------------ openWebView

    /**
     * Headless page load used by providers to pass WAF challenges: loads
     * [url] in a throwaway WebView, waits for the page to finish (and, when
     * options.waitForCookie is set, for that cookie to appear), then returns
     * the rendered HTML and cookies in Vega's OpenWebViewResult shape.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun openWebView(url: String, options: JSONObject?): JSONObject {
        val timeoutMs = (options?.optLong("timeoutMs", 0L) ?: 0L)
            .takeIf { it > 0 } ?: OPEN_WEBVIEW_TIMEOUT_MS
        val result = try {
            withTimeout(timeoutMs + 5000) {
                openWebViewInternal(url, options, timeoutMs)
            }
        } catch (e: Exception) {
            null
        }
        return result ?: JSONObject().apply {
            put("success", false)
            put("data", "")
            put("cookies", "")
            put("cookieMap", JSONObject())
            put("userAgent", "")
            put("url", url)
        }
    }

    private suspend fun openWebViewInternal(
        url: String,
        options: JSONObject?,
        timeoutMs: Long
    ): JSONObject = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            val wv = try {
                WebView(appContext)
            } catch (e: Exception) {
                if (cont.isActive) {
                    cont.resume(JSONObject().apply {
                        put("success", false)
                        put("data", "")
                        put("cookies", "")
                        put("cookieMap", JSONObject())
                        put("userAgent", "")
                        put("url", url)
                    })
                }
                return@post
            }
            val userAgent = options?.optString("userAgent")?.ifBlank { null } ?: DEFAULT_WEBVIEW_UA
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = userAgent
            try {
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            } catch (e: Exception) {
                // Older WebViews: third-party cookies are a nicety, not a must.
            }
            wv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, 1080, 1920)

            var finished = false
            var pageLoaded = false
            var settleRunnable: Runnable? = null
            var pollRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null

            fun cleanup() {
                settleRunnable?.let { mainHandler.removeCallbacks(it) }
                pollRunnable?.let { mainHandler.removeCallbacks(it) }
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (e: Exception) {
                    // Already gone.
                }
            }

            fun finish(success: Boolean) {
                if (finished) return
                finished = true
                settleRunnable?.let { mainHandler.removeCallbacks(it) }
                pollRunnable?.let { mainHandler.removeCallbacks(it) }
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                val currentUrl = try { wv.url } catch (e: Exception) { null } ?: url
                try {
                    wv.evaluateJavascript("document.documentElement.outerHTML") { htmlRaw ->
                        val html = decodeJsString(htmlRaw ?: "")
                        val cookieString =
                            try { CookieManager.getInstance().getCookie(currentUrl) } catch (e: Exception) { null }
                                ?: ""
                        val out = JSONObject()
                        out.put("success", success)
                        out.put("data", if (success) html else "")
                        out.put("cookies", cookieString)
                        out.put("cookieMap", parseCookieMap(cookieString))
                        out.put("userAgent", userAgent)
                        out.put("url", currentUrl)
                        cleanup()
                        if (cont.isActive) cont.resume(out)
                    }
                } catch (e: Exception) {
                    val out = JSONObject()
                    out.put("success", false)
                    out.put("data", "")
                    out.put("cookies", "")
                    out.put("cookieMap", JSONObject())
                    out.put("userAgent", userAgent)
                    out.put("url", currentUrl)
                    cleanup()
                    if (cont.isActive) cont.resume(out)
                }
            }

            val waitForCookie = options?.optString("waitForCookie")?.ifBlank { null }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    pageLoaded = true
                    if (waitForCookie != null) {
                        // Poll until the expected cookie shows up (challenge
                        // solved) or the overall timeout fires.
                        if (pollRunnable == null) {
                            val poll = object : Runnable {
                                override fun run() {
                                    val cookies = try {
                                        CookieManager.getInstance().getCookie(finishedUrl ?: url)
                                    } catch (e: Exception) {
                                        null
                                    } ?: ""
                                    if (cookies.split(';').any {
                                            it.trim().startsWith("$waitForCookie=")
                                        }
                                    ) {
                                        finish(true)
                                    } else if (!finished) {
                                        mainHandler.postDelayed(this, 500)
                                    }
                                }
                            }
                            pollRunnable = poll
                            mainHandler.postDelayed(poll, 500)
                        }
                    } else {
                        settleRunnable?.let { mainHandler.removeCallbacks(it) }
                        val settle = Runnable { finish(true) }
                        settleRunnable = settle
                        mainHandler.postDelayed(settle, OPEN_WEBVIEW_SETTLE_MS)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true && !pageLoaded) {
                        finish(false)
                    }
                }
            }
            val timeout = Runnable { finish(pageLoaded) }
            timeoutRunnable = timeout
            mainHandler.postDelayed(timeout, timeoutMs)

            cont.invokeOnCancellation { mainHandler.post { cleanup() } }

            val extraHeaders = HashMap<String, String>()
            val headersObj = options?.optJSONObject("headers")
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extraHeaders[key] = headersObj.optString(key)
                }
            }
            try {
                if (extraHeaders.isEmpty()) wv.loadUrl(url) else wv.loadUrl(url, extraHeaders)
            } catch (e: Exception) {
                finish(false)
            }
        }
    }

    private fun parseCookieMap(cookieString: String): JSONObject {
        val out = JSONObject()
        for (part in cookieString.split(';')) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim())
            }
        }
        return out
    }

    // ------------------------------------------------------------ kv + baseUrl

    private fun kvStoreFor(providerKey: String): KvStore =
        synchronized(kvStores) {
            kvStores.getOrPut(providerKey) { KvStore(kvDir, providerKey) }
        }

    private val baseUrlLock = Any()
    private var baseUrls: MutableMap<String, String>? = null

    private fun readBaseUrl(providerValue: String): String {
        synchronized(baseUrlLock) {
            val map = baseUrls ?: loadBaseUrls().also { baseUrls = it }
            return map[providerValue] ?: ""
        }
    }

    private fun loadBaseUrls(): MutableMap<String, String> {
        val map = HashMap<String, String>()
        try {
            val file = File(appContext.filesDir, "engine-baseurls.json")
            if (file.exists()) {
                val obj = JSONObject(file.readText())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.optString(key)
                }
            }
        } catch (e: Exception) {
            map.clear()
        }
        return map
    }

    // ------------------------------------------------------------ JS bridge

    /**
     * The object harness.js talks to. All methods are callable from the
     * WebView's JavaBridge thread; async work is handed to executors/OkHttp,
     * synchronous helpers only touch thread-safe stores.
     */
    inner class NativeRpc {

        @JavascriptInterface
        fun rpc(id: String, payloadJson: String) {
            rpcExecutor.execute { handleRpc(id, payloadJson) }
        }

        @JavascriptInterface
        fun result(callId: String, json: String) {
            pendingCalls.remove(callId)?.complete(json)
        }

        @JavascriptInterface
        fun ready() {
            readyDeferred?.complete(Unit)
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, message.take(MAX_LOG_CHARS))
        }

        @JavascriptInterface
        fun kvGet(providerKey: String, key: String): String = kvStoreFor(providerKey).get(key)

        @JavascriptInterface
        fun kvSet(providerKey: String, key: String, value: String) {
            kvStoreFor(providerKey).set(key, value)
        }

        @JavascriptInterface
        fun kvDelete(providerKey: String, key: String): String =
            kvStoreFor(providerKey).delete(key)

        @JavascriptInterface
        fun kvKeys(providerKey: String): String = kvStoreFor(providerKey).keys()

        @JavascriptInterface
        fun kvClear(providerKey: String) {
            kvStoreFor(providerKey).clear()
        }

        @JavascriptInterface
        fun getBaseUrl(providerValue: String): String = readBaseUrl(providerValue)

        @JavascriptInterface
        fun cookiesFor(providerKey: String, url: String): String =
            cookieJar.cookiesMapJson(providerKey, url)
    }

    companion object {
        private const val TAG = "StreamOneWebViewEngine"
        private const val READY_TIMEOUT_MS = 20_000L
        private const val CALL_TIMEOUT_MS = 60_000L
        private const val OPEN_WEBVIEW_TIMEOUT_MS = 30_000L
        private const val OPEN_WEBVIEW_SETTLE_MS = 2_000L
        private const val MAX_LOG_CHARS = 2000

        private const val DEFAULT_WEBVIEW_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Vega's providers/headers.ts (Android branch) — same values as harness.js. */
        val COMMON_HEADERS: List<Pair<String, String>> = listOf(
            "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "User-Agent" to DEFAULT_WEBVIEW_UA,
            "Accept-Language" to "en-US,en;q=0.9"
        )
    }
}
