package com.streamone.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Owns everything that comes from the network/disk side of the provider
 * system: the two provider manifests and the compiled provider modules.
 *
 * Sources are base URLs, exactly like the Vega app's extension sources:
 * the manifest lives at <base>/manifest.json and provider modules at
 * <base>/dist/<value>/<module>.js (Vega's getProviderFilesUrl shape).
 *
 *   - https://cdn.jsdelivr.net/gh/udaikiran77-netizen/vega-providers@main
 *   - https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@main
 *
 * Manifests are merged with the user's own repo first, so its providers
 * win value collisions. Both manifests and modules are cached with a TTL
 * and fall back to the stale cache when the network is unavailable.
 */
class ProviderRepository(context: Context) {

    private data class ManifestSource(
        val tag: String,
        val owner: String,
        val repo: String,
        /** Source base URL; the manifest is "$baseUrl/manifest.json". */
        val baseUrl: String
    ) {
        val manifestUrl: String get() = "$baseUrl/manifest.json"
    }

    private val sources = listOf(
        ManifestSource(
            tag = "udai",
            owner = "udaikiran77-netizen",
            repo = "vega-providers",
            baseUrl = "https://cdn.jsdelivr.net/gh/udaikiran77-netizen/vega-providers@main"
        ),
        ManifestSource(
            tag = "zenda",
            owner = "Zenda-Cross",
            repo = "vega-providers",
            baseUrl = "https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@main"
        )
    )

    private val cacheDir = File(context.cacheDir, "provider-engine").apply { mkdirs() }
    private val client = HttpBridge.client()

    @Volatile
    private var memoryProviders: List<ProviderEntry>? = null

    suspend fun fetchProviders(forceRefresh: Boolean = false): List<ProviderEntry> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                memoryProviders?.let { return@withContext it }
            }
            val merged = LinkedHashMap<String, ProviderEntry>()
            for (source in sources) {
                val entries = loadManifest(source, forceRefresh)
                for (entry in entries) {
                    // First writer wins: the user's own providers shadow
                    // identical "value"s from the community collection.
                    if (!merged.containsKey(entry.value)) {
                        merged[entry.value] = entry
                    }
                }
            }
            val result = merged.values.toList()
            memoryProviders = result
            result
        }

    private fun loadManifest(source: ManifestSource, forceRefresh: Boolean): List<ProviderEntry> {
        val cacheFile = File(cacheDir, "manifest-${source.tag}.json")
        val cacheFresh = cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < MANIFEST_TTL_MS

        var text: String? = null
        if (forceRefresh || !cacheFresh) {
            text = httpGet(source.manifestUrl)
            if (text != null) {
                try {
                    cacheFile.writeText(text)
                } catch (e: Exception) {
                    // Cache write is best-effort.
                }
            }
        }
        if (text == null) {
            if (!cacheFile.exists()) return emptyList()
            text = try {
                cacheFile.readText()
            } catch (e: Exception) {
                return emptyList()
            }
        }
        return parseManifest(source, text)
    }

    private fun parseManifest(source: ManifestSource, text: String): List<ProviderEntry> {
        val out = ArrayList<ProviderEntry>()
        try {
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optBoolean("disabled", false)) continue
                val value = obj.optString("value")
                if (value.isBlank()) continue
                out.add(
                    ProviderEntry(
                        id = "${source.tag}:${value}",
                        source = source.tag,
                        owner = source.owner,
                        repo = source.repo,
                        value = value,
                        displayName = obj.optString(
                            "display_name",
                            obj.optString("name", value)
                        ),
                        icon = obj.optString("icon"),
                        version = obj.optString("version"),
                        type = obj.optString("type", "global"),
                        hasSettings = obj.optBoolean("hasSettings", false)
                    )
                )
            }
        } catch (e: Exception) {
            // Malformed manifest: contribute nothing rather than crashing.
        }
        return out
    }

    /**
     * Returns the JS source of one provider module ("posts", "catalog",
     * "meta", "episodes", "stream"), downloading and caching it as needed.
     */
    suspend fun loadModuleSource(entry: ProviderEntry, module: String): String =
        withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "modules/${entry.source}/${entry.value}").apply { mkdirs() }
            val file = File(dir, "$module.js")
            val fresh = file.exists() &&
                System.currentTimeMillis() - file.lastModified() < MODULE_TTL_MS
            if (!fresh) {
                val downloaded = httpGet(entry.moduleUrl(module))
                if (downloaded != null) {
                    try {
                        file.writeText(downloaded)
                    } catch (e: Exception) {
                        // Fall through and read whatever is on disk.
                    }
                    return@withContext downloaded
                }
            }
            if (file.exists()) {
                return@withContext file.readText()
            }
            throw ProviderEngineException(
                "Could not download module '$module' for provider ${entry.displayName}"
            )
        }

    private fun httpGet(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpBridge.DEFAULT_USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val MANIFEST_TTL_MS = 6 * 60 * 60 * 1000L
        const val MODULE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

/** Raised for any provider-engine failure (JS errors, missing modules, …). */
class ProviderEngineException(message: String) : Exception(message)
