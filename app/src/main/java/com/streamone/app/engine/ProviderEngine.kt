package com.streamone.app.engine

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs Vega provider bundles and exposes their functions as typed suspend
 * functions. The JavaScript itself executes in [WebViewEngine]'s hidden
 * WebView harness — the same architecture as the Vega app (a WebView JS
 * realm whose fetch/axios are shimmed onto a native RPC bridge) — after the
 * previous QuickJS runtime proved unreliable on device.
 *
 * This class keeps the public API the UI was built against; only the
 * transport changed. Provider calls are serialised with a [Mutex]: the
 * harness tracks one active provider at a time for cookie-jar routing.
 *
 * Modules are downloaded through [ProviderRepository] and evaluated into
 * the harness on demand, once per provider + module.
 */
class ProviderEngine(
    context: Context,
    private val repository: ProviderRepository
) {

    private val appContext: Context = context.applicationContext

    private val cookieJar = ProviderCookieJar(appContext)
    private val webViewEngine = WebViewEngine(appContext, cookieJar)

    private val mutex = Mutex()

    /** Keys ("<providerId>/<module>") of modules already evaluated in JS. */
    private val loadedModuleKeys =
        java.util.Collections.synchronizedSet(HashSet<String>())

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
        val streams = StreamItem.listFromJson(value as? JSONArray)
        // Mirror Vega's ProviderManager.withJarCookies: provider requests
        // don't share a cookie store with the player, so attach this
        // provider's jar cookies for the stream host unless the provider
        // already set a Cookie header itself.
        return streams.map { stream ->
            val hasCookie = stream.headers.keys.any { it.equals("Cookie", ignoreCase = true) }
            if (hasCookie || !stream.link.startsWith("http", ignoreCase = true)) {
                stream
            } else {
                val cookie = cookieJar.cookieHeaderFor(entry.id, stream.link)
                if (cookie == null) stream
                else stream.copy(headers = stream.headers + ("Cookie" to cookie))
            }
        }
    }

    // ------------------------------------------------------------- internals

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
        // Fetch the module source outside the call lock (suspending,
        // IO-bound, disk-cached). Skipped entirely once evaluated.
        val moduleKey = entry.id + "/" + module
        val source: String? = if (loadedModuleKeys.contains(moduleKey)) {
            null
        } else {
            repository.loadModuleSource(entry, module)
        }
        return mutex.withLock {
            if (!loadedModuleKeys.contains(moduleKey)) {
                val src = source ?: throw ProviderEngineException(
                    "${entry.displayName}: module $module source unavailable"
                )
                try {
                    webViewEngine.loadModule(entry.id, module, src)
                } catch (e: ProviderEngineException) {
                    throw ProviderEngineException("${entry.displayName}: ${e.message}")
                }
                loadedModuleKeys.add(moduleKey)
            }
            val resultJson = try {
                webViewEngine.callProvider(entry.id, module, export, args.toString())
            } catch (e: ProviderEngineException) {
                throw ProviderEngineException("${entry.displayName}: ${e.message}")
            }
            val slot = try {
                JSONObject(resultJson)
            } catch (e: Exception) {
                throw ProviderEngineException(
                    "${entry.displayName}: invalid JS result for $module.$export"
                )
            }
            if (!slot.optBoolean("ok", false)) {
                throw ProviderEngineException(
                    "${entry.displayName}: $module.$export error: " +
                        slot.optString("error", "unknown error")
                )
            }
            val value = slot.opt("value")
            if (value == JSONObject.NULL) null else value
        }
    }

    private companion object {
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
