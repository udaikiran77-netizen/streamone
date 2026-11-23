package com.streamone.app.engine

import android.content.Context
import android.util.Log
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

// -----------------------------------------------------------------------------
// Native bridge interfaces exposed to JS through QuickJs.set().
// They must stay public top-level interfaces with String-only signatures:
// the QuickJS binding proxies them reflectively. The JS side sees each one
// as an object whose methods are the interface methods.
// -----------------------------------------------------------------------------

/** Synchronous HTTP: takes the axios shim's request JSON, returns reply JSON. */
interface NativeHttpBridge {
    fun http(requestJson: String): String
}

/** Per-provider persistent KV: values are raw JSON text, "" means absent. */
interface NativeKvBridge {
    fun get(key: String): String
    fun set(key: String, value: String): String
    fun delete(key: String): String
    fun keys(): String
    fun clear(): String
}

/** console.* sink from JS. */
interface NativeLogBridge {
    fun log(message: String)
}

// -----------------------------------------------------------------------------
// JS functions fetched through QuickJs.get() and invoked from Kotlin.
// -----------------------------------------------------------------------------

interface JsLoadModule {
    fun load(name: String, source: String): String
}

interface JsCallProvider {
    fun call(moduleName: String, exportName: String, argsJson: String): String
}

interface JsPollSlot {
    fun poll(): String
}

interface JsRunTimers {
    fun run(): String
}

/**
 * Runs Vega provider bundles (esbuild CommonJS dist files) inside QuickJS,
 * one isolated context per provider, and exposes their functions as typed
 * suspend functions.
 *
 * Threading: QuickJS contexts are thread-confined, so every JS interaction
 * happens on a single dedicated thread ([engineDispatcher]) and is
 * serialised with a [Mutex] — a provider call is a request/response slot
 * protocol (see assets/engine/provider-loader.js) and must not interleave.
 *
 * Each context is bootstrapped with, in order:
 *   1. the native bridges (__nativeHttp / __nativeKv / __nativeLog),
 *   2. provider-loader.js  (context object, polyfills, module registry),
 *   3. axios-shim.js       (axios backed by the synchronous native bridge),
 *   4. cheerio.bundle.js   (cheerio 1.1.0, browser build).
 * Provider modules are then downloaded through [ProviderRepository] and
 * evaluated into the registry on demand.
 */
class ProviderEngine(
    context: Context,
    private val repository: ProviderRepository
) {

    private val appContext: Context = context.applicationContext

    private val engineDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "streamone-provider-engine")
        }.asCoroutineDispatcher()

    private val mutex = Mutex()

    private class Runtime(
        val entry: ProviderEntry,
        val quickJs: QuickJs,
        val loadModule: JsLoadModule,
        val callProvider: JsCallProvider,
        val pollSlot: JsPollSlot,
        val runTimers: JsRunTimers,
        val loadedModules: MutableSet<String> = HashSet()
    )

    private val runtimes = HashMap<String, Runtime>()

    @Volatile
    private var providersById: Map<String, ProviderEntry> = emptyMap()

    // ------------------------------------------------------------- manifests

    suspend fun fetchManifests(forceRefresh: Boolean = false): List<ProviderEntry> {
        val providers = repository.fetchProviders(forceRefresh)
        providersById = providers.associateBy { it.id }
        return providers
    }

    fun entry(providerId: String): ProviderEntry =
        providersById[providerId]
            ?: throw ProviderEngineException("Unknown provider: $providerId")

    // ------------------------------------------------------------- public API

    suspend fun getCatalog(providerId: String): List<CatalogItem> =
        getCatalog(entry(providerId))

    suspend fun getCatalog(entry: ProviderEntry): List<CatalogItem> {
        val catalog = callForValue(entry, MODULE_CATALOG, EXPORT_CATALOG, JSONObject())
        val items = CatalogItem.listFromJson(catalog as? JSONArray)
        if (items.isNotEmpty()) return items
        // Some providers only expose genres; treat them as catalogs.
        val genres = callForValue(entry, MODULE_CATALOG, EXPORT_GENRES, JSONObject())
        return CatalogItem.listFromJson(genres as? JSONArray)
    }

    suspend fun getPosts(providerId: String, filter: String, page: Int): List<Post> =
        getPosts(entry(providerId), filter, page)

    suspend fun getPosts(entry: ProviderEntry, filter: String, page: Int): List<Post> {
        val args = JSONObject()
            .put("filter", filter)
            .put("page", page)
            .put("providerValue", entry.value)
        val value = callForValue(entry, MODULE_POSTS, EXPORT_GET_POSTS, args)
        return Post.listFromJson(value as? JSONArray)
    }

    suspend fun getSearchPosts(providerId: String, query: String, page: Int): List<Post> =
        getSearchPosts(entry(providerId), query, page)

    suspend fun getSearchPosts(entry: ProviderEntry, query: String, page: Int): List<Post> {
        val args = JSONObject()
            .put("searchQuery", query)
            .put("page", page)
            .put("providerValue", entry.value)
        val value = callForValue(entry, MODULE_POSTS, EXPORT_GET_SEARCH_POSTS, args)
        return Post.listFromJson(value as? JSONArray)
    }

    suspend fun getMeta(providerId: String, link: String): Info =
        getMeta(entry(providerId), link)

    suspend fun getMeta(entry: ProviderEntry, link: String): Info {
        val args = JSONObject()
            .put("link", link)
            .put("provider", entry.value)
        val value = callForValue(entry, MODULE_META, EXPORT_GET_META, args)
        val obj = value as? JSONObject
            ?: throw ProviderEngineException("${entry.displayName}: getMeta returned no data")
        return Info.fromJson(obj)
    }

    suspend fun getEpisodes(providerId: String, url: String): List<EpisodeLink> =
        getEpisodes(entry(providerId), url)

    suspend fun getEpisodes(entry: ProviderEntry, url: String): List<EpisodeLink> {
        val args = JSONObject().put("url", url)
        val value = callForValue(entry, MODULE_EPISODES, EXPORT_GET_EPISODES, args)
        return EpisodeLink.listFromJson(value as? JSONArray)
    }

    suspend fun getStreams(providerId: String, link: String, type: String): List<StreamItem> =
        getStreams(entry(providerId), link, type)

    suspend fun getStreams(entry: ProviderEntry, link: String, type: String): List<StreamItem> {
        val args = JSONObject()
            .put("link", link)
            .put("type", type)
            .put("isDownload", false)
        val value = callForValue(entry, MODULE_STREAM, EXPORT_GET_STREAM, args)
        return StreamItem.listFromJson(value as? JSONArray)
    }

    // ------------------------------------------------------------- internals

    /** Keys ("<providerId>/<module>") of modules already evaluated in JS. */
    private val loadedModuleKeys =
        java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Executes one provider export and returns its raw JSON value
     * (JSONArray / JSONObject / String / null) from org.json.
     */
    private suspend fun callForValue(
        entry: ProviderEntry,
        module: String,
        export: String,
        args: JSONObject
    ): Any? {
        // Fetch the module source outside the JS lock (suspending, IO-bound,
        // disk-cached). Skipped entirely once the module is evaluated.
        val moduleKey = entry.id + "/" + module
        val source: String? = if (loadedModuleKeys.contains(moduleKey)) {
            null
        } else {
            repository.loadModuleSource(entry, module)
        }
        return withContext(engineDispatcher) {
            mutex.withLock {
                val runtime = runtimeFor(entry)
                if (!runtime.loadedModules.contains(module)) {
                    val src = source ?: throw ProviderEngineException(
                        "${entry.displayName}: module $module source unavailable"
                    )
                    try {
                        runtime.loadModule.load(module, src)
                    } catch (e: Exception) {
                        throw ProviderEngineException(
                            "${entry.displayName}: module $module failed to evaluate: ${e.message}"
                        )
                    }
                    runtime.loadedModules.add(module)
                    loadedModuleKeys.add(moduleKey)
                }
                callSyncLocked(runtime, module, export, args)
            }
        }
    }

    /** Must be called on the engine thread while holding [mutex]. */
    private fun callSyncLocked(
        runtime: Runtime,
        module: String,
        export: String,
        args: JSONObject
    ): Any? {
        var result: String = try {
            runtime.callProvider.call(module, export, args.toString())
        } catch (e: Exception) {
            throw ProviderEngineException(
                "${runtime.entry.displayName}: JS call $module.$export failed: ${e.message}"
            )
        }
        var guard = 0
        while (result == PENDING) {
            if (++guard > MAX_POLLS) {
                throw ProviderEngineException(
                    "${runtime.entry.displayName}: $module.$export timed out waiting for JS promises"
                )
            }
            try {
                runtime.runTimers.run()
            } catch (e: Exception) {
                // Timer callbacks are best-effort.
            }
            try {
                Thread.sleep(POLL_SLEEP_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ProviderEngineException("Provider call interrupted")
            }
            result = runtime.pollSlot.poll()
        }
        val slot = try {
            JSONObject(result)
        } catch (e: Exception) {
            throw ProviderEngineException(
                "${runtime.entry.displayName}: invalid JS result for $module.$export"
            )
        }
        if (!slot.optBoolean("ok", false)) {
            throw ProviderEngineException(
                "${runtime.entry.displayName}: $module.$export error: " +
                    slot.optString("error", "unknown error")
            )
        }
        val value = slot.opt("value")
        return if (value == JSONObject.NULL) null else value
    }

    /** Must be called on the engine thread. Creates the context lazily. */
    private fun runtimeFor(entry: ProviderEntry): Runtime {
        runtimes[entry.id]?.let { return it }

        val quickJs = QuickJs.create()
        try {
            val kvStore = KvStore(File(appContext.filesDir, "kvstore"), entry.id)

            quickJs.set(
                "__nativeHttp",
                NativeHttpBridge::class.java,
                object : NativeHttpBridge {
                    override fun http(requestJson: String): String =
                        HttpBridge.execute(requestJson)
                }
            )
            quickJs.set(
                "__nativeKv",
                NativeKvBridge::class.java,
                object : NativeKvBridge {
                    override fun get(key: String): String = kvStore.get(key)
                    override fun set(key: String, value: String): String = kvStore.set(key, value)
                    override fun delete(key: String): String = kvStore.delete(key)
                    override fun keys(): String = kvStore.keys()
                    override fun clear(): String = kvStore.clear()
                }
            )
            quickJs.set(
                "__nativeLog",
                NativeLogBridge::class.java,
                object : NativeLogBridge {
                    override fun log(message: String) {
                        Log.d(TAG, "[${entry.value}] " + message.take(MAX_LOG_CHARS))
                    }
                }
            )

            quickJs.evaluate(readAsset("engine/provider-loader.js"), "provider-loader.js")
            quickJs.evaluate(readAsset("engine/axios-shim.js"), "axios-shim.js")
            quickJs.evaluate(readAsset("engine/cheerio.bundle.js"), "cheerio.bundle.js")

            val runtime = Runtime(
                entry = entry,
                quickJs = quickJs,
                loadModule = quickJs.get("__loadModule", JsLoadModule::class.java),
                callProvider = quickJs.get("__callProvider", JsCallProvider::class.java),
                pollSlot = quickJs.get("__pollSlot", JsPollSlot::class.java),
                runTimers = quickJs.get("__runTimers", JsRunTimers::class.java)
            )
            runtimes[entry.id] = runtime
            return runtime
        } catch (e: Exception) {
            try {
                quickJs.close()
            } catch (ignored: Exception) {
                // Nothing sensible to do here.
            }
            throw ProviderEngineException(
                "${entry.displayName}: engine failed to start: ${e.message}"
            )
        }
    }

    private fun readAsset(path: String): String =
        appContext.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private companion object {
        const val TAG = "StreamOneEngine"
        const val PENDING = "pending"
        const val POLL_SLEEP_MS = 2L
        const val MAX_POLLS = 3000
        const val MAX_LOG_CHARS = 2000

        const val MODULE_POSTS = "posts"
        const val MODULE_CATALOG = "catalog"
        const val MODULE_META = "meta"
        const val MODULE_EPISODES = "episodes"
        const val MODULE_STREAM = "stream"

        const val EXPORT_CATALOG = "catalog"
        const val EXPORT_GENRES = "genres"
        const val EXPORT_GET_POSTS = "getPosts"
        const val EXPORT_GET_SEARCH_POSTS = "getSearchPosts"
        const val EXPORT_GET_META = "getMeta"
        const val EXPORT_GET_EPISODES = "getEpisodes"
        const val EXPORT_GET_STREAM = "getStream"
    }
}
