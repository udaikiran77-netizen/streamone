package com.streamone.app.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.streamone.app.engine.Post
import com.streamone.app.engine.ProviderEntry
import com.streamone.app.engine.StreamItem
import com.streamone.app.extensions.ExtensionBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The content source currently driving Home / Search. */
sealed interface ActiveSource {
    data class Vega(val entry: ProviderEntry) : ActiveSource
    data class Ext(val bridge: ExtensionBridge) : ActiveSource

    val displayName: String
        get() = when (this) {
            is Vega -> entry.displayName
            is Ext -> bridge.displayName
        }
}

// ---------------------------------------------------------------------------
// Watchlist: bookmarks persisted as JSON in filesDir (watchlist.json).
// ---------------------------------------------------------------------------

data class WatchlistItem(
    /** "provider" (Vega engine) or "extension" (CloudStream plugin). */
    val kind: String,
    /** ProviderEntry.id or ExtensionBridge.id. */
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val link: String,
    val image: String,
    val type: String
) {
    fun toPost(): Post = Post(title = title, link = link, image = image)
}

class WatchlistStore(context: Context) {
    private val file = File(context.filesDir, "watchlist.json")

    var items: List<WatchlistItem> by mutableStateOf(load())
        private set

    fun contains(sourceId: String, link: String): Boolean =
        items.any { it.sourceId == sourceId && it.link == link }

    /** Adds the item when absent, removes it when present. Returns saved-now. */
    fun toggle(item: WatchlistItem): Boolean {
        return if (contains(item.sourceId, item.link)) {
            remove(item.sourceId, item.link)
            false
        } else {
            items = listOf(item) + items
            save()
            true
        }
    }

    fun remove(sourceId: String, link: String) {
        items = items.filterNot { it.sourceId == sourceId && it.link == link }
        save()
    }

    private fun load(): List<WatchlistItem> {
        return try {
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText())
            val out = ArrayList<WatchlistItem>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val link = obj.optString("link")
                if (link.isBlank()) continue
                out.add(
                    WatchlistItem(
                        kind = obj.optString("kind", "provider"),
                        sourceId = obj.optString("sourceId"),
                        sourceName = obj.optString("sourceName"),
                        title = obj.optString("title"),
                        link = link,
                        image = obj.optString("image"),
                        type = obj.optString("type")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save() {
        try {
            val array = JSONArray()
            for (item in items) {
                array.put(
                    JSONObject()
                        .put("kind", item.kind)
                        .put("sourceId", item.sourceId)
                        .put("sourceName", item.sourceName)
                        .put("title", item.title)
                        .put("link", item.link)
                        .put("image", item.image)
                        .put("type", item.type)
                )
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            // A failed bookmark write must never crash the UI.
        }
    }
}

// ---------------------------------------------------------------------------
// Downloads: enqueue with DownloadManager into the app downloads folder and
// list / play / delete the files that land there.
// ---------------------------------------------------------------------------

data class DownloadedFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long
)

object DownloadsHelper {

    private const val SUB_DIR = "StreamOne"

    /** The folder finished downloads are listed from (created on demand). */
    fun downloadsDir(context: Context): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val dir = if (external != null) {
            File(external, SUB_DIR)
        } else {
            File(context.filesDir, "downloads")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listDownloads(context: Context): List<DownloadedFile> {
        val dir = downloadsDir(context)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.length() > 0 }
            .map {
                DownloadedFile(
                    name = it.name,
                    path = it.absolutePath,
                    size = it.length(),
                    lastModified = it.lastModified()
                )
            }
            .sortedByDescending { it.lastModified }
    }

    fun delete(file: DownloadedFile): Boolean = File(file.path).delete()

    /**
     * Enqueues [stream] with DownloadManager. Returns the download id, or -1
     * when the device has no external files dir (DownloadManager cannot
     * target internal storage through the public API).
     */
    fun enqueue(context: Context, title: String, stream: StreamItem): Long {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return -1L
        val fileName = fileNameFor(title, stream)
        val request = DownloadManager.Request(Uri.parse(stream.link))
        request.setTitle(title)
        request.setDescription(stream.server)
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            "$SUB_DIR/$fileName"
        )
        // Streams frequently refuse requests without the headers getStream
        // returned; pass the main ones through.
        for ((key, value) in stream.headers) {
            try {
                request.addRequestHeader(key, value)
            } catch (e: Exception) {
                // Skip malformed headers instead of failing the download.
            }
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return -1L
        return try {
            manager.enqueue(request)
        } catch (e: Exception) {
            -1L
        }
    }

    private fun fileNameFor(title: String, stream: StreamItem): String {
        val clean = title.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
            .ifBlank { "streamone-video" }
        val fromUrl = Uri.parse(stream.link).lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
        val ext = fromUrl ?: when (stream.type.lowercase()) {
            "m3u8", "hls" -> "m3u8"
            "dash", "mpd" -> "mpd"
            else -> "mp4"
        }
        return if (clean.endsWith(".$ext", ignoreCase = true)) clean else "$clean.$ext"
    }
}
