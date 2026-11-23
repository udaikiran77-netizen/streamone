package com.streamone.app.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data shapes mirroring the Vega provider template's providers/types.ts.
 * All parsers are tolerant: missing fields fall back to empty values because
 * real-world providers are sloppy about optional fields.
 */

data class ProviderEntry(
    /** Unique engine id: "<source>:<value>", e.g. "udai:cinejoy". */
    val id: String,
    /** Manifest source tag: "udai" (user's own repo) or "zenda" (Zenda-Cross). */
    val source: String,
    val owner: String,
    val repo: String,
    /** The manifest "value" — also the dist/<value>/ folder name. */
    val value: String,
    val displayName: String,
    val icon: String,
    val version: String,
    val type: String,
    val hasSettings: Boolean
) {
    fun moduleUrl(module: String): String =
        "https://cdn.jsdelivr.net/gh/$owner/$repo@main/dist/$value/$module.js"
}

data class Post(
    val title: String,
    val link: String,
    val image: String,
    val tag: String? = null
) {
    companion object {
        fun fromJson(obj: JSONObject): Post = Post(
            title = obj.optString("title"),
            link = obj.optString("link"),
            image = obj.optString("image"),
            tag = obj.optStringOrNull("tag")
        )

        fun listFromJson(array: JSONArray?): List<Post> {
            if (array == null) return emptyList()
            val out = ArrayList<Post>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val post = fromJson(item)
                if (post.link.isNotBlank()) out.add(post)
            }
            return out
        }
    }
}

data class CatalogItem(
    val title: String,
    val filter: String
) {
    companion object {
        fun listFromJson(array: JSONArray?): List<CatalogItem> {
            if (array == null) return emptyList()
            val out = ArrayList<CatalogItem>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val filter = item.optString("filter")
                if (filter.isNotBlank()) {
                    out.add(CatalogItem(title = item.optString("title", filter), filter = filter))
                }
            }
            return out
        }
    }
}

data class DirectLink(
    val title: String,
    val link: String,
    val type: String? = null
)

data class LinkItem(
    val title: String,
    val quality: String? = null,
    val episodesLink: String? = null,
    val directLinks: List<DirectLink> = emptyList()
) {
    companion object {
        fun listFromJson(array: JSONArray?): List<LinkItem> {
            if (array == null) return emptyList()
            val out = ArrayList<LinkItem>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val directs = ArrayList<DirectLink>()
                val directArray = item.optJSONArray("directLinks")
                if (directArray != null) {
                    for (j in 0 until directArray.length()) {
                        val d = directArray.optJSONObject(j) ?: continue
                        val link = d.optString("link")
                        if (link.isNotBlank()) {
                            directs.add(
                                DirectLink(
                                    title = d.optString("title", "Play"),
                                    link = link,
                                    type = d.optStringOrNull("type")
                                )
                            )
                        }
                    }
                }
                out.add(
                    LinkItem(
                        title = item.optString("title"),
                        quality = item.optStringOrNull("quality"),
                        episodesLink = item.optStringOrNull("episodesLink"),
                        directLinks = directs
                    )
                )
            }
            return out
        }
    }
}

data class Info(
    val title: String,
    val image: String,
    val poster: String? = null,
    val synopsis: String,
    val imdbId: String,
    val type: String,
    val tags: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val rating: String? = null,
    val linkList: List<LinkItem> = emptyList()
) {
    companion object {
        fun fromJson(obj: JSONObject): Info = Info(
            title = obj.optString("title"),
            image = obj.optString("image"),
            poster = obj.optStringOrNull("poster"),
            synopsis = obj.optString("synopsis"),
            imdbId = obj.optString("imdbId"),
            type = obj.optString("type", "movie"),
            tags = obj.optStringList("tags"),
            cast = obj.optStringList("cast"),
            rating = obj.optStringOrNull("rating"),
            linkList = LinkItem.listFromJson(obj.optJSONArray("linkList"))
        )
    }
}

data class EpisodeLink(
    val title: String,
    val link: String
) {
    companion object {
        fun listFromJson(array: JSONArray?): List<EpisodeLink> {
            if (array == null) return emptyList()
            val out = ArrayList<EpisodeLink>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val link = item.optString("link")
                if (link.isNotBlank()) {
                    out.add(EpisodeLink(title = item.optString("title", "Episode"), link = link))
                }
            }
            return out
        }
    }
}

data class TextTrack(
    val title: String,
    val language: String,
    val type: String,
    val uri: String
)

data class StreamItem(
    val server: String,
    val link: String,
    val type: String,
    val quality: String? = null,
    val tag: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<TextTrack> = emptyList()
) {
    companion object {
        fun listFromJson(array: JSONArray?): List<StreamItem> {
            if (array == null) return emptyList()
            val out = ArrayList<StreamItem>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val link = item.optString("link")
                if (link.isBlank()) continue
                val headers = LinkedHashMap<String, String>()
                val headersObj = item.optJSONObject("headers")
                if (headersObj != null) {
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = headersObj.opt(key)
                        if (value != null && value != JSONObject.NULL) {
                            headers[key] = value.toString()
                        }
                    }
                }
                val subtitles = ArrayList<TextTrack>()
                val subsArray = item.optJSONArray("subtitles")
                if (subsArray != null) {
                    for (j in 0 until subsArray.length()) {
                        val s = subsArray.optJSONObject(j) ?: continue
                        val uri = s.optString("uri")
                        if (uri.isNotBlank()) {
                            subtitles.add(
                                TextTrack(
                                    title = s.optString("title"),
                                    language = s.optString("language"),
                                    type = s.optString("type", "text/vtt"),
                                    uri = uri
                                )
                            )
                        }
                    }
                }
                out.add(
                    StreamItem(
                        server = item.optString("server", "Server"),
                        link = link,
                        type = item.optString("type", ""),
                        quality = item.optAnyStringOrNull("quality"),
                        tag = item.optStringOrNull("tag"),
                        headers = headers,
                        subtitles = subtitles
                    )
                )
            }
            return out
        }
    }
}

// ---------------------------------------------------------------------------
// Small org.json helpers shared by the parsers above.
// ---------------------------------------------------------------------------

internal fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name)
    return value.takeIf { it.isNotBlank() }
}

/** Like optStringOrNull but also accepts numbers (e.g. quality: 720). */
internal fun JSONObject.optAnyStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = opt(name) ?: return null
    val text = value.toString()
    return text.takeIf { it.isNotBlank() }
}

internal fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        val value = array.optString(i)
        if (value.isNotBlank()) out.add(value)
    }
    return out
}
