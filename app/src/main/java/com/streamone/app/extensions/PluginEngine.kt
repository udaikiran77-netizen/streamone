package com.streamone.app.extensions

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.Repository
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "StreamOnePlugins"
private const val ONLINE_PLUGINS_FOLDER = "Extensions"

/** A repository the user added, as persisted by StreamOne. */
data class StoredRepo(
    val url: String,
    val name: String
)

/** An installed .cs3 plugin, as persisted by StreamOne. */
data class InstalledPlugin(
    val internalName: String,
    val name: String,
    val version: Int,
    /** Repository URL this plugin came from, "" for local/sideloaded plugins. */
    val repositoryUrl: String,
    /** Download URL of the .cs3 file. */
    val url: String,
    val filePath: String,
    val language: String? = null,
    val iconUrl: String? = null
)

/** A plugin offered by a repository, paired with its repository. */
data class RepoPlugin(
    val repo: StoredRepo,
    val plugin: SitePlugin
)

/**
 * CloudStream extensions engine for StreamOne: repository handling, .cs3
 * download/install, and plugin loading that mirrors upstream CloudStream's
 * PluginManager.loadPlugin (PathClassLoader + manifest.json + Plugin.load).
 *
 * Loaded [MainAPI]s register themselves in [APIHolder.allProviders] exactly
 * like in upstream CloudStream (via BasePlugin.registerMainAPI), and built-in
 * + plugin extractors live in `extractorApis`. [ExtensionBridge] wraps each
 * MainAPI for the StreamOne UI.
 */
class PluginEngine(private val appContext: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val loadMutex = Mutex()
    private val loadedPlugins = LinkedHashMap<String, BasePlugin>()
    private val loadedApis = LinkedHashMap<String, MutableList<MainAPI>>()

    private val storeFile: File
        get() = File(appContext.filesDir, "extensions.json")

    // ------------------------------------------------------------------
    // Persistence (repos + installed plugins) in a small JSON file.
    // ------------------------------------------------------------------

    @Synchronized
    fun storedRepos(): List<StoredRepo> {
        val root = readStore() ?: return emptyList()
        val array = root.optJSONArray("repos") ?: return emptyList()
        val out = ArrayList<StoredRepo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(
                StoredRepo(
                    url = item.optString("url"),
                    name = item.optString("name")
                )
            )
        }
        return out
    }

    @Synchronized
    fun installedPlugins(): List<InstalledPlugin> {
        val root = readStore() ?: return emptyList()
        val array = root.optJSONArray("installed") ?: return emptyList()
        val out = ArrayList<InstalledPlugin>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(
                InstalledPlugin(
                    internalName = item.optString("internalName"),
                    name = item.optString("name"),
                    version = item.optInt("version"),
                    repositoryUrl = item.optString("repositoryUrl"),
                    url = item.optString("url"),
                    filePath = item.optString("filePath"),
                    language = item.optString("language").ifBlank { null },
                    iconUrl = item.optString("iconUrl").ifBlank { null }
                )
            )
        }
        return out
    }

    private fun readStore(): JSONObject? {
        return try {
            val file = storeFile
            if (!file.exists()) null else JSONObject(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read extension store", e)
            null
        }
    }

    @Synchronized
    private fun writeStore(repos: List<StoredRepo>, installed: List<InstalledPlugin>) {
        try {
            val root = JSONObject()
            val repoArray = JSONArray()
            for (repo in repos) {
                repoArray.put(JSONObject().apply {
                    put("url", repo.url)
                    put("name", repo.name)
                })
            }
            val installedArray = JSONArray()
            for (plugin in installed) {
                installedArray.put(JSONObject().apply {
                    put("internalName", plugin.internalName)
                    put("name", plugin.name)
                    put("version", plugin.version)
                    put("repositoryUrl", plugin.repositoryUrl)
                    put("url", plugin.url)
                    put("filePath", plugin.filePath)
                    put("language", plugin.language ?: "")
                    put("iconUrl", plugin.iconUrl ?: "")
                })
            }
            root.put("repos", repoArray)
            root.put("installed", installedArray)
            storeFile.writeText(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write extension store", e)
        }
    }

    @Synchronized
    private fun upsertRepo(repo: StoredRepo) {
        val repos = storedRepos().filter { it.url != repo.url } + repo
        writeStore(repos, installedPlugins())
    }

    @Synchronized
    private fun upsertInstalled(plugin: InstalledPlugin) {
        val installed = installedPlugins()
            .filter { !(it.internalName == plugin.internalName && it.repositoryUrl == plugin.repositoryUrl) } +
            plugin
        writeStore(storedRepos(), installed)
    }

    @Synchronized
    private fun removeInstalled(internalName: String, repositoryUrl: String) {
        val installed = installedPlugins()
            .filter { !(it.internalName == internalName && it.repositoryUrl == repositoryUrl) }
        writeStore(storedRepos(), installed)
    }

    // ------------------------------------------------------------------
    // Repositories
    // ------------------------------------------------------------------

    /**
     * Normalizes a user-entered repository reference the same way upstream
     * RepositoryManager.parseRepoUrl handles direct URLs and the
     * cloudstreamrepo:// / cs.repo shortlink prefixes. (The cutt.ly / py.md
     * shortcode resolution is not supported, see PHASE4-NOTES.md.)
     */
    fun normalizeRepoUrl(input: String): String? {
        val fixedUrl = input.trim()
        return when {
            fixedUrl.matches(Regex("^https?://.*")) -> fixedUrl
            fixedUrl.contains(Regex("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)")) -> {
                val stripped = fixedUrl.replace(
                    Regex("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)"), ""
                )
                if (stripped.matches(Regex("^https?://.*"))) stripped else "https://$stripped"
            }

            else -> null
        }
    }

    /** Fetches and parses a repository JSON. Adds it to the stored repo list. */
    suspend fun addRepository(url: String): Repository = withContext(Dispatchers.IO) {
        val text = httpGetText(url)
        val repo = mapper.readValue(text, Repository::class.java)
        upsertRepo(StoredRepo(url = url, name = repo.name))
        repo
    }

    /** Fetches every plugin list of a repository. */
    suspend fun fetchRepoPlugins(repoUrl: String, repo: Repository): List<RepoPlugin> =
        withContext(Dispatchers.IO) {
            val stored = StoredRepo(url = repoUrl, name = repo.name)
            val out = ArrayList<RepoPlugin>()
            for (listUrl in repo.pluginLists) {
                try {
                    val text = httpGetText(listUrl)
                    val plugins: List<SitePlugin> =
                        mapper.readValue(text, object : TypeReference<List<SitePlugin>>() {})
                    for (plugin in plugins) {
                        out.add(RepoPlugin(stored, plugin))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse plugin list $listUrl", e)
                }
            }
            out
        }

    // ------------------------------------------------------------------
    // Install / uninstall
    // ------------------------------------------------------------------

    private fun pluginFile(internalName: String, repositoryUrl: String): File {
        val folderName = sanitizeFileName(repositoryUrl.ifBlank { "local" })
        val fileName = sanitizeFileName(internalName)
        return File(
            appContext.filesDir,
            "$ONLINE_PLUGINS_FOLDER/$folderName/$fileName.cs3"
        )
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$cleaned.${name.hashCode()}"
    }

    /** Downloads a .cs3, verifies its hash when the repo provides one, installs and loads it. */
    suspend fun install(repoPlugin: RepoPlugin): InstalledPlugin = withContext(Dispatchers.IO) {
        val plugin = repoPlugin.plugin
        val file = pluginFile(plugin.internalName, repoPlugin.repo.url)
        downloadPluginToFile(plugin.url, file, plugin.fileHash)
        val installed = InstalledPlugin(
            internalName = plugin.internalName,
            name = plugin.name,
            version = plugin.version,
            repositoryUrl = repoPlugin.repo.url,
            url = plugin.url,
            filePath = file.absolutePath,
            language = plugin.language,
            iconUrl = plugin.iconUrl
        )
        upsertInstalled(installed)
        loadPlugin(file)
        installed
    }

    suspend fun uninstall(installed: InstalledPlugin) {
        unloadPlugin(installed.filePath)
        try {
            File(installed.filePath).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete plugin file ${installed.filePath}", e)
        }
        removeInstalled(installed.internalName, installed.repositoryUrl)
    }

    private fun downloadPluginToFile(url: String, file: File, expectedHash: String?) {
        val parent = file.parentFile ?: throw IllegalStateException("No parent dir for $file")
        parent.mkdirs()
        val tempFile = File.createTempFile(file.name, ".tmp", appContext.cacheDir)
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed: HTTP ${response.code} for $url")
                }
                val body = response.body ?: throw IllegalStateException("Empty body for $url")
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (expectedHash != null) {
                val actual = sha256(tempFile)
                if (!expectedHash.equals(actual, ignoreCase = true)) {
                    throw IllegalStateException(
                        "Extension hash mismatch for '${file.name}'. Expected: '$expectedHash', got: '$actual'."
                    )
                }
            }
            if (file.exists()) file.delete()
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /** Returns a SHA-256 string of the file content, formatted like upstream ("sha256-…"). */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // Loading — mirrors upstream PluginManager.loadPlugin
    // ------------------------------------------------------------------

    /** Loads every installed plugin. Call from a background thread at app start. */
    suspend fun loadAllInstalled() {
        for (installed in installedPlugins()) {
            val file = File(installed.filePath)
            if (file.exists()) {
                loadPlugin(file)
            } else {
                Log.w(TAG, "Installed plugin file missing: ${installed.filePath}")
            }
        }
    }

    /**
     * Loads one .cs3 file. Safe to call repeatedly: already-loaded files are
     * skipped. Returns true on success.
     */
    suspend fun loadPlugin(file: File): Boolean = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            loadPluginLocked(file)
        }
    }

    private fun loadPluginLocked(file: File): Boolean {
        val filePath = file.absolutePath
        if (loadedPlugins.containsKey(filePath)) return true
        if (!file.exists()) return false

        // In case of Android 14+ the dex file must be read-only.
        try {
            file.setReadOnly()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set dex as read-only", t)
        }

        return try {
            val loader = PathClassLoader(filePath, appContext.classLoader)

            val manifest: BasePlugin.Manifest
            loader.getResourceAsStream("manifest.json").use { stream ->
                if (stream == null) {
                    Log.e(TAG, "Failed to load plugin ${file.name}: No manifest found")
                    return false
                }
                InputStreamReader(stream).use { reader ->
                    manifest = parseJson(reader.readText())
                }
            }

            val pluginClassName = manifest.pluginClassName
            if (pluginClassName.isNullOrBlank()) {
                Log.e(TAG, "Failed to load plugin ${file.name}: No pluginClassName in manifest")
                return false
            }

            @Suppress("UNCHECKED_CAST")
            val pluginClass =
                loader.loadClass(pluginClassName) as Class<out BasePlugin>
            val pluginInstance: BasePlugin =
                pluginClass.getDeclaredConstructor().newInstance()

            pluginInstance.filename = filePath
            if (manifest.requiresResources && pluginInstance is Plugin) {
                try {
                    val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                    val addAssetPath =
                        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                    addAssetPath.invoke(assets, filePath)

                    @Suppress("DEPRECATION")
                    pluginInstance.resources = Resources(
                        assets,
                        appContext.resources.displayMetrics,
                        appContext.resources.configuration
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to load resources for plugin ${file.name}", t)
                }
            }

            val apisBefore = APIHolder.allProviders.toList()
            val extractorsBefore = extractorApis.size

            loadedPlugins[filePath] = pluginInstance
            if (pluginInstance is Plugin) {
                pluginInstance.load(appContext)
            } else {
                pluginInstance.load()
            }

            val newApis = APIHolder.allProviders.filter { it !in apisBefore }
            for (api in newApis) {
                try {
                    api.init()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to init provider ${api.name}", t)
                }
            }
            loadedApis[filePath] = newApis.toMutableList()
            Log.i(
                TAG,
                "Loaded plugin ${file.name}: ${newApis.size} provider(s), " +
                    "${extractorApis.size - extractorsBefore} extractor(s)"
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load plugin $file", t)
            false
        }
    }

    /** Unloads a plugin and removes everything it registered. */
    suspend fun unloadPlugin(filePath: String) = loadMutex.withLock {
        val plugin = loadedPlugins.remove(filePath) ?: return@withLock
        try {
            plugin.beforeUnload()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to run beforeUnload for $filePath", t)
        }
        val apis = loadedApis.remove(filePath).orEmpty()
        for (api in apis) {
            APIHolder.removePluginMapping(api)
        }
        APIHolder.allProviders.removeAll(apis.toSet())
        extractorApis.removeAll { it.sourcePlugin == filePath }
    }

    /** Bridges for every currently loaded provider, in registration order. */
    fun bridges(): List<ExtensionBridge> {
        val byApi = HashMap<MainAPI, String>()
        for ((path, apis) in loadedApis) {
            for (api in apis) byApi[api] = path
        }
        return APIHolder.allProviders.map { api ->
            ExtensionBridge(api, byApi[api] ?: api.sourcePlugin ?: "")
        }
    }

    fun isLoaded(filePath: String): Boolean = loadedPlugins.containsKey(filePath)

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private fun httpGetText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
            )
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} for $url")
            }
            return response.body?.string() ?: throw IllegalStateException("Empty body for $url")
        }
    }
}
