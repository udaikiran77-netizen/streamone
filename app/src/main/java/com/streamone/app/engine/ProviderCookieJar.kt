package com.streamone.app.engine

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-provider cookie jar, a native port of the Vega app's
 * sandbox/providerCookieJar.ts. Provider requests never share the WebView's
 * cookie store: cookies set while talking to one provider's sites (including
 * WAF clearance cookies) are only sent back to matching hosts for that same
 * provider.
 *
 * Kept simple like Vega's: cookies match by domain only (path and Secure
 * are ignored). Jars are held in memory and persisted best-effort to one
 * JSON file per provider under filesDir/engine-cookies/.
 */
class ProviderCookieJar(context: android.content.Context) {

    private data class StoredCookie(
        val value: String,
        /** Unix time in ms, or null for a session cookie. */
        val expiresAt: Long?,
        /** Sent only to the exact host, not its subdomains. */
        val hostOnly: Boolean
    )

    /** providerKey -> domain -> cookie name -> cookie */
    private val jars = ConcurrentHashMap<String, MutableMap<String, MutableMap<String, StoredCookie>>>()
    private val loadedProviders = ConcurrentHashMap.newKeySet<String>()
    private val dir = File(context.applicationContext.filesDir, "engine-cookies").apply { mkdirs() }
    private val lock = Any()

    // ------------------------------------------------------------ public API

    /** Cookie header value for [url], or null when the jar has nothing to send. */
    fun cookieHeaderFor(providerKey: String, url: String): String? {
        val host = hostOf(url) ?: return null
        val pairs = matchingCookies(providerKey, host)
        if (pairs.isEmpty()) return null
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** Cookies for [url] as a name -> value map (providerContext.getCookies). */
    fun cookiesMapFor(providerKey: String, url: String): Map<String, String> {
        val host = hostOf(url) ?: return emptyMap()
        return matchingCookies(providerKey, host)
    }

    /** JSON object string of [cookiesMapFor], for the JS bridge. */
    fun cookiesMapJson(providerKey: String, url: String): String {
        val obj = JSONObject()
        for ((name, value) in cookiesMapFor(providerKey, url)) obj.put(name, value)
        return obj.toString()
    }

    /** Stores every Set-Cookie header value received from [responseUrl]. */
    fun storeFromResponse(providerKey: String, responseUrl: String, setCookieHeaders: List<String>) {
        val host = hostOf(responseUrl) ?: return
        if (setCookieHeaders.isEmpty()) return
        synchronized(lock) {
            val jar = jarFor(providerKey)
            for (header in setCookieHeaders) {
                storeOne(jar, host, header)
            }
            persist(providerKey)
        }
    }

    /**
     * Merges a "name=value; name2=value2" cookie string (e.g. the cookies a
     * provider obtained through openWebView) into the jar for [url]'s host.
     */
    fun mergeCookieString(providerKey: String, url: String, cookieString: String) {
        val host = hostOf(url) ?: return
        if (cookieString.isBlank()) return
        synchronized(lock) {
            val jar = jarFor(providerKey)
            val domainMap = jar.getOrPut(host) { HashMap() }
            for (part in cookieString.split(';')) {
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isNotEmpty()) {
                    domainMap[name] = StoredCookie(value, null, hostOnly = true)
                }
            }
            persist(providerKey)
        }
    }

    // --------------------------------------------------------------- internals

    private fun matchingCookies(providerKey: String, host: String): Map<String, String> {
        synchronized(lock) {
            val jar = jarFor(providerKey)
            val now = System.currentTimeMillis()
            val out = LinkedHashMap<String, String>()
            var pruned = false
            for ((domain, cookies) in jar) {
                val domainMatches = host == domain || host.endsWith(".$domain")
                val it = cookies.entries.iterator()
                while (it.hasNext()) {
                    val (name, cookie) = it.next()
                    if (cookie.expiresAt != null && cookie.expiresAt <= now) {
                        it.remove()
                        pruned = true
                        continue
                    }
                    if (domainMatches && !(cookie.hostOnly && host != domain)) {
                        out[name] = cookie.value
                    }
                }
            }
            if (pruned) persist(providerKey)
            return out
        }
    }

    private fun storeOne(
        jar: MutableMap<String, MutableMap<String, StoredCookie>>,
        responseHost: String,
        setCookie: String
    ) {
        val parts = setCookie.split(';')
        if (parts.isEmpty()) return
        val first = parts[0].trim()
        val eq = first.indexOf('=')
        if (eq <= 0) return
        val name = first.substring(0, eq).trim()
        val value = first.substring(eq + 1).trim()
        if (name.isEmpty()) return

        var domain = responseHost
        var hostOnly = true
        var expiresAt: Long? = null
        var delete = false
        for (i in 1 until parts.size) {
            val attr = parts[i].trim()
            val attrEq = attr.indexOf('=')
            val attrName = (if (attrEq >= 0) attr.substring(0, attrEq) else attr)
                .trim().lowercase()
            val attrValue = if (attrEq >= 0) attr.substring(attrEq + 1).trim() else ""
            when (attrName) {
                "domain" -> if (attrValue.isNotEmpty()) {
                    domain = attrValue.removePrefix(".").lowercase()
                    hostOnly = false
                }
                "max-age" -> {
                    val seconds = attrValue.toLongOrNull()
                    if (seconds != null) {
                        if (seconds <= 0) delete = true
                        else expiresAt = System.currentTimeMillis() + seconds * 1000
                    }
                }
                "expires" -> {
                    if (expiresAt == null) expiresAt = parseCookieDate(attrValue)
                }
            }
        }
        val domainMap = jar.getOrPut(domain) { HashMap() }
        if (delete || value.isEmpty() && expiresAt != null && expiresAt <= System.currentTimeMillis()) {
            domainMap.remove(name)
        } else {
            domainMap[name] = StoredCookie(value, expiresAt, hostOnly)
        }
    }

    private fun parseCookieDate(value: String): Long? {
        val formats = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yyyy HH:mm:ss zzz",
            "EEE, dd MMM yy HH:mm:ss zzz"
        )
        for (format in formats) {
            try {
                val parser = java.text.SimpleDateFormat(format, java.util.Locale.US)
                parser.timeZone = java.util.TimeZone.getTimeZone("GMT")
                return parser.parse(value)?.time
            } catch (e: Exception) {
                // Try the next format.
            }
        }
        return null
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------ persistence

    private fun jarFor(providerKey: String): MutableMap<String, MutableMap<String, StoredCookie>> {
        // Callers hold `lock`.
        if (loadedProviders.add(providerKey)) {
            val jar = HashMap<String, MutableMap<String, StoredCookie>>()
            try {
                val file = fileFor(providerKey)
                if (file.exists()) {
                    val root = JSONObject(file.readText())
                    val domains = root.keys()
                    while (domains.hasNext()) {
                        val domain = domains.next()
                        val cookiesObj = root.optJSONObject(domain) ?: continue
                        val map = HashMap<String, StoredCookie>()
                        val names = cookiesObj.keys()
                        while (names.hasNext()) {
                            val name = names.next()
                            val c = cookiesObj.optJSONObject(name) ?: continue
                            map[name] = StoredCookie(
                                value = c.optString("v"),
                                expiresAt = if (c.has("e") && !c.isNull("e")) c.optLong("e") else null,
                                hostOnly = c.optBoolean("h", true)
                            )
                        }
                        jar[domain] = map
                    }
                }
            } catch (e: Exception) {
                // Corrupt jar file: start empty rather than crashing.
                jar.clear()
            }
            jars[providerKey] = jar
        }
        return jars.getOrPut(providerKey) { HashMap() }
    }

    private fun persist(providerKey: String) {
        // Callers hold `lock`. Best-effort: the in-memory jar keeps working.
        try {
            val jar = jars[providerKey] ?: return
            val root = JSONObject()
            for ((domain, cookies) in jar) {
                val obj = JSONObject()
                for ((name, cookie) in cookies) {
                    val c = JSONObject()
                    c.put("v", cookie.value)
                    if (cookie.expiresAt != null) c.put("e", cookie.expiresAt)
                    c.put("h", cookie.hostOnly)
                    obj.put(name, c)
                }
                root.put(domain, obj)
            }
            fileFor(providerKey).writeText(root.toString())
        } catch (e: Exception) {
            // Persistence is best-effort.
        }
    }

    private fun fileFor(providerKey: String): File =
        File(dir, providerKey.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}
