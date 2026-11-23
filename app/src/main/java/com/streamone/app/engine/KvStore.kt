package com.streamone.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-backed persistent key-value store — one instance per provider, used
 * behind the JS providerContext.kvStore bridge.
 *
 * Values cross the bridge as raw JSON text (the JS side JSON.stringifies on
 * set and JSON.parses on get), so this class only stores strings. The whole
 * map is persisted to a small JSON file on every mutation; provider KV data
 * is tiny (session tokens, settings), so this stays cheap. All methods are
 * synchronized: the engine is single-threaded, but the store is also safe if
 * that ever changes.
 */
class KvStore(dir: File, namespace: String) {

    private val file: File = File(
        dir,
        "kv-" + namespace.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json"
    )
    private val map = HashMap<String, String>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (file.exists()) {
                val obj = JSONObject(file.readText())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.optString(key)
                }
            }
        } catch (e: Exception) {
            // Corrupt store file: start empty rather than crashing the provider.
            map.clear()
        }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val obj = JSONObject()
            for ((key, value) in map) obj.put(key, value)
            file.writeText(obj.toString())
        } catch (e: Exception) {
            // Persistence is best-effort; the in-memory map keeps working.
        }
    }

    /** Returns the stored JSON text, or "" when the key is absent. */
    @Synchronized
    fun get(key: String): String {
        ensureLoaded()
        return map[key] ?: ""
    }

    @Synchronized
    fun set(key: String, value: String): String {
        ensureLoaded()
        map[key] = value
        persist()
        return "ok"
    }

    /** Returns "true" when a key was actually removed (bridge protocol). */
    @Synchronized
    fun delete(key: String): String {
        ensureLoaded()
        val removed = map.remove(key) != null
        if (removed) persist()
        return if (removed) "true" else "false"
    }

    /** Returns all keys as a JSON array string. */
    @Synchronized
    fun keys(): String {
        ensureLoaded()
        val array = JSONArray()
        for (key in map.keys) array.put(key)
        return array.toString()
    }

    @Synchronized
    fun clear(): String {
        ensureLoaded()
        map.clear()
        persist()
        return "ok"
    }
}
