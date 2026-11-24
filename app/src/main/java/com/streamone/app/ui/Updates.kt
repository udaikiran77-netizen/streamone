package com.streamone.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.streamone.app.engine.HttpBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// In-app updates: StreamOne releases are published on its own GitHub repo
// (udaikiran77-netizen/streamone). The app reads the latest release from
// the public GitHub API, compares its tag with the installed versionName,
// and — on demand or at startup — downloads the attached streamone.apk and
// hands it to the system package installer. No background service, no
// notifications: checks happen in-app only.
// ---------------------------------------------------------------------------

/** A release that is strictly newer than the installed app. */
data class UpdateInfo(
    /** Cleaned version from the tag, e.g. "1.0.2". */
    val version: String,
    /** Raw tag, e.g. "v1.0.2". */
    val tag: String,
    val name: String,
    /** Release notes (the release body). */
    val notes: String,
    /** "d MMM yyyy" when the API date parsed, else "". */
    val publishedAt: String,
    /** browser_download_url of the streamone.apk asset. */
    val downloadUrl: String
)

object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/udaikiran77-netizen/streamone/releases/latest"
    private const val APK_ASSET_NAME = "streamone.apk"

    private const val PREFS_NAME = "streamone_settings"
    private const val KEY_AUTO_UPDATE = "auto_update"

    // -- Preferences --------------------------------------------------------

    fun isAutoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_UPDATE, enabled)
            .apply()
    }

    // -- Installed version ----------------------------------------------------

    /** The installed versionName, read from PackageManager (never hardcoded). */
    fun installedVersionName(context: Context): String {
        return try {
            val manager = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    // -- Version comparison ---------------------------------------------------

    /** True when [candidate] (a tag or version) is newer than [installed]. */
    fun isNewer(candidate: String, installed: String): Boolean =
        compareVersions(candidate, installed) > 0

    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        val count = maxOf(pa.size, pb.size)
        for (i in 0 until count) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parseVersion(raw: String): List<Int> =
        raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '_')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    // -- The check --------------------------------------------------------------

    /**
     * Fetches the latest GitHub release. Success carries an [UpdateInfo]
     * when the release is strictly newer than the installed version, or
     * null when the app is already up to date. Failures (network, HTTP,
     * malformed payload, a release without the APK asset) come back as
     * Result.failure with a human-readable message.
     */
    suspend fun checkForUpdate(context: Context): Result<UpdateInfo?> {
        val installed = installedVersionName(context)
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(LATEST_RELEASE_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "StreamOne/$installed")
                    .build()
                HttpBridge.client().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException(
                                if (response.code == 404) {
                                    "No releases published yet"
                                } else {
                                    "GitHub returned HTTP ${response.code}"
                                }
                            )
                        )
                    }
                    val text = response.body?.string()
                        ?: return@withContext Result.failure(IOException("Empty response from GitHub"))
                    val json = JSONObject(text)
                    val tag = json.optString("tag_name")
                    if (tag.isBlank()) {
                        return@withContext Result.failure(IOException("Release has no version tag"))
                    }
                    val version = tag.trim().removePrefix("v").removePrefix("V")
                    if (!isNewer(version, installed)) {
                        return@withContext Result.success(null)
                    }
                    var downloadUrl: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            if (asset.optString("name") == APK_ASSET_NAME) {
                                downloadUrl = asset.optString("browser_download_url")
                                    .takeIf { it.isNotBlank() }
                                break
                            }
                        }
                    }
                    val url = downloadUrl
                        ?: return@withContext Result.failure(
                            IOException("Release $tag has no $APK_ASSET_NAME attached")
                        )
                    Result.success(
                        UpdateInfo(
                            version = version,
                            tag = tag,
                            name = json.optString("name"),
                            notes = json.optString("body"),
                            publishedAt = formatReleaseDate(json.optString("published_at")),
                            downloadUrl = url
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun formatReleaseDate(iso: String): String {
        if (iso.isBlank()) return ""
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date = parser.parse(iso) ?: return iso.substringBefore('T')
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            iso.substringBefore('T')
        }
    }
}

// ---------------------------------------------------------------------------
// Download: stream the release APK to the app downloads folder with
// progress. The active OkHttp call is kept so the dialog can cancel it.
// ---------------------------------------------------------------------------

class UpdateDownloader {

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
        activeCall?.cancel()
    }

    /**
     * Downloads [url] into [dest]. [onProgress] receives 0–100, or -1 when
     * the server did not send a content length.
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit
    ): Result<File> {
        cancelled = false
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "StreamOne-Updater")
                    .build()
                // Longer read timeout than the shared client: APKs are tens
                // of MB and connections can stall between chunks.
                val client = HttpBridge.client().newBuilder()
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                val call = client.newCall(request)
                activeCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IOException("HTTP ${response.code}"))
                    }
                    val body = response.body
                        ?: return@withContext Result.failure(IOException("Empty download"))
                    val total = body.contentLength()
                    dest.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                output.write(buffer, 0, count)
                                read += count
                                onProgress(
                                    if (total > 0) {
                                        ((read * 100) / total).toInt().coerceIn(0, 100)
                                    } else {
                                        -1
                                    }
                                )
                            }
                        }
                    }
                }
                if (cancelled) {
                    dest.delete()
                    Result.failure(IOException("Download cancelled"))
                } else {
                    Result.success(dest)
                }
            } catch (e: Exception) {
                dest.delete()
                Result.failure(
                    if (cancelled) IOException("Download cancelled") else e
                )
            } finally {
                activeCall = null
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Install: hand the downloaded APK to the system installer via FileProvider.
// ---------------------------------------------------------------------------

/** Whether this app may install packages (always true before Android O). */
fun canInstallPackages(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

/** Opens the "Install unknown apps" settings page for this app. */
fun openUnknownSourcesSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ignored: Exception) {
            // No settings page to open; the dialog message still explains.
        }
    }
}

/** Launches the system installer for [apk]. Returns false when it can't. */
fun launchUpdateInstaller(context: Context, apk: File): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

// ---------------------------------------------------------------------------
// Update dialog: release notes + Update now / Later, then download
// progress with Cancel, then the installer. Errors stay in the dialog
// with a Retry.
// ---------------------------------------------------------------------------

@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { UpdateDownloader() }

    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { downloader.cancel() }
    }

    fun startDownload() {
        if (!canInstallPackages(context)) {
            openUnknownSourcesSettings(context)
            notice = "Allow “Install unknown apps” for StreamOne in the " +
                "settings screen that just opened, then come back and tap " +
                "“Update now” again."
            return
        }
        notice = null
        error = null
        progress = 0
        downloading = true
        val dest = File(DownloadsHelper.downloadsDir(context), "update.apk")
        scope.launch {
            downloader.download(info.downloadUrl, dest) { pct ->
                if (pct >= 0) progress = pct
            }.onSuccess { file ->
                downloading = false
                if (launchUpdateInstaller(context, file)) {
                    onDismiss()
                } else {
                    error = "Could not open the installer for the downloaded update."
                }
            }.onFailure { e ->
                downloading = false
                if (!downloader.cancelled) {
                    error = e.message ?: "Download failed"
                }
            }
        }
    }

    Dialog(onDismissRequest = { if (!downloading) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = VegaSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VegaIcon(name = VegaIconName.Download, tint = Tomato, size = 26.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "New version available",
                        color = VegaText,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("Version ")
                        append(info.version)
                        if (info.publishedAt.isNotBlank()) {
                            append(" • ")
                            append(info.publishedAt)
                        }
                    },
                    color = Tomato,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                val notes = info.notes.trim()
                if (notes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = notes,
                        color = VegaSubText,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }

                if (notice != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = notice!!, color = VegaText, fontSize = 13.sp)
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = error!!,
                        color = Tomato,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))
                if (downloading) {
                    if (progress > 0) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Tomato,
                            trackColor = VegaSurfaceHigh
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Tomato,
                            trackColor = VegaSurfaceHigh
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (progress > 0) {
                            "Downloading… $progress%"
                        } else {
                            "Downloading…"
                        },
                        color = VegaSubText,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    GhostButton(
                        text = "Cancel",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        downloader.cancel()
                        downloading = false
                    }
                } else {
                    VegaButton(
                        text = if (error != null) "Retry" else "Update now",
                        modifier = Modifier.fillMaxWidth()
                    ) { startDownload() }
                    Spacer(modifier = Modifier.height(10.dp))
                    GhostButton(
                        text = "Later",
                        modifier = Modifier.fillMaxWidth()
                    ) { onDismiss() }
                }
            }
        }
    }
}
