package com.streamone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamone.app.engine.EpisodeLink
import com.streamone.app.engine.Info
import com.streamone.app.engine.Post
import com.streamone.app.engine.ProviderEngine
import com.streamone.app.engine.ProviderEntry
import com.streamone.app.engine.StreamItem
import com.streamone.app.extensions.ExtDetail
import com.streamone.app.extensions.ExtensionBridge
import kotlinx.coroutines.launch

/** What the inline streams panel is currently resolving. */
private data class StreamTarget(
    val key: String,
    val title: String,
    val link: String,
    val type: String,
    val episodes: List<EpisodeRef> = emptyList(),
    val episodeIndex: Int = -1
)

// ---------------------------------------------------------------------------
// Vega Info layout for provider content: backdrop with a bottom gradient
// into black, title + meta + synopsis, then link groups; tapping a link or
// episode expands the streams panel (servers with quality/type chips and a
// download action per stream) right underneath it.
// ---------------------------------------------------------------------------

@Composable
fun InfoScreen(
    engine: ProviderEngine,
    provider: ProviderEntry,
    post: Post,
    watchlist: WatchlistStore,
    onBack: () -> Unit,
    onPlay: (PlayerArgs) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var info by remember(post.link) { mutableStateOf<Info?>(null) }
    var error by remember(post.link) { mutableStateOf<String?>(null) }
    var retryKey by remember(post.link) { mutableStateOf(0) }

    var episodesByLink by remember(post.link) {
        mutableStateOf<Map<String, List<EpisodeLink>>>(emptyMap())
    }
    var episodesLoadingFor by remember(post.link) { mutableStateOf<String?>(null) }
    var episodesError by remember(post.link) { mutableStateOf<String?>(null) }

    var target by remember(post.link) { mutableStateOf<StreamTarget?>(null) }
    var streams by remember(post.link) { mutableStateOf<List<StreamItem>?>(null) }
    var streamsLoading by remember(post.link) { mutableStateOf(false) }
    var streamsError by remember(post.link) { mutableStateOf<String?>(null) }
    var downloadNote by remember(post.link) { mutableStateOf<String?>(null) }

    var saved by remember(post.link) {
        mutableStateOf(watchlist.contains(provider.id, post.link))
    }

    LaunchedEffect(post.link, retryKey) {
        error = null
        try {
            info = engine.getMeta(provider, post.link)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load details"
        }
    }

    fun selectTarget(newTarget: StreamTarget) {
        if (target?.key == newTarget.key && streams != null) {
            target = null
            return
        }
        target = newTarget
        streams = null
        streamsError = null
        downloadNote = null
        streamsLoading = true
        scope.launch {
            try {
                streams = engine.getStreams(provider, newTarget.link, newTarget.type)
            } catch (e: Exception) {
                streamsError = e.message ?: "Failed to load streams"
            }
            streamsLoading = false
        }
    }

    fun playStream(stream: StreamItem, index: Int) {
        val currentTarget = target ?: return
        val loader: (suspend (EpisodeRef) -> List<StreamItem>)? =
            if (currentTarget.episodes.isNotEmpty()) {
                { ref -> engine.getStreams(provider, ref.key, currentTarget.type) }
            } else {
                null
            }
        onPlay(
            PlayerArgs(
                title = currentTarget.title,
                subtitle = provider.displayName,
                streams = streams ?: listOf(stream),
                startIndex = index,
                episodes = currentTarget.episodes,
                episodeIndex = currentTarget.episodeIndex,
                loadEpisodeStreams = loader
            )
        )
    }

    val meta = info
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            InfoHeader(
                image = meta?.poster?.takeIf { it.isNotBlank() }
                    ?: meta?.image?.takeIf { it.isNotBlank() }
                    ?: post.image,
                saved = saved,
                onBack = onBack,
                onToggleSaved = {
                    saved = watchlist.toggle(
                        WatchlistItem(
                            kind = "provider",
                            sourceId = provider.id,
                            sourceName = provider.displayName,
                            title = post.title,
                            link = post.link,
                            image = post.image,
                            type = meta?.type ?: ""
                        )
                    )
                }
            )
        }

        when {
            error != null -> item {
                ErrorCard(message = error!!, onRetry = { retryKey++ })
            }

            meta == null -> item { InfoSkeleton() }

            else -> {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = meta.title,
                            color = VegaText,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val metaLine = listOfNotNull(
                            meta.type.replaceFirstChar { it.uppercase() },
                            meta.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
                            meta.tags.takeIf { it.isNotEmpty() }
                                ?.joinToString(", ")
                        ).joinToString("  •  ")
                        if (metaLine.isNotBlank()) {
                            Text(text = metaLine, color = VegaSubText, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        MetaChip(text = provider.displayName, accent = true)
                        if (meta.cast.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Cast: " + meta.cast.joinToString(", "),
                                color = VegaSubText,
                                fontSize = 13.sp
                            )
                        }
                        if (meta.synopsis.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(text = meta.synopsis, color = VegaText, fontSize = 14.sp)
                        }
                    }
                }

                for (linkItem in meta.linkList) {
                    item {
                        Row(
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = linkItem.title.ifBlank { "Links" },
                                color = VegaText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            val quality = linkItem.quality?.takeIf { it.isNotBlank() }
                            if (quality != null) {
                                MetaChip(text = quality)
                            }
                        }
                    }

                    if (linkItem.directLinks.isNotEmpty()) {
                        for (direct in linkItem.directLinks) {
                            item {
                                PlayRow(title = direct.title) {
                                    selectTarget(
                                        StreamTarget(
                                            key = direct.link,
                                            title = direct.title,
                                            link = direct.link,
                                            type = direct.type ?: meta.type
                                        )
                                    )
                                }
                            }
                            if (target?.key == direct.link) {
                                item {
                                    StreamsPanel(
                                        loading = streamsLoading,
                                        error = streamsError,
                                        streams = streams,
                                        downloadNote = downloadNote,
                                        onRetry = {
                                            target?.let { selectTarget(it) }
                                        },
                                        onPlay = { stream, index ->
                                            playStream(stream, index)
                                        },
                                        onDownload = { stream ->
                                            val id = DownloadsHelper.enqueue(
                                                context,
                                                target?.title ?: meta.title,
                                                stream
                                            )
                                            downloadNote = if (id >= 0) {
                                                "Download started — track it from the " +
                                                    "notification shade or Downloads tab."
                                            } else {
                                                "Could not start this download on this device."
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    } else if (linkItem.episodesLink != null) {
                        val episodesLink = linkItem.episodesLink
                        val episodes = episodesByLink[episodesLink]
                        when {
                            episodesLoadingFor == episodesLink -> item { LoadingBox() }
                            episodes == null -> item {
                                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    VegaButton(text = "Load episodes") {
                                        episodesLoadingFor = episodesLink
                                        episodesError = null
                                        scope.launch {
                                            try {
                                                val loaded = engine.getEpisodes(
                                                    provider,
                                                    episodesLink
                                                )
                                                episodesByLink = episodesByLink +
                                                    (episodesLink to loaded)
                                            } catch (e: Exception) {
                                                episodesError = e.message
                                                    ?: "Failed to load episodes"
                                            }
                                            episodesLoadingFor = null
                                        }
                                    }
                                    if (episodesError != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = episodesError!!,
                                            color = Tomato,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }

                            else -> {
                                val episodeRefs = episodes.map {
                                    EpisodeRef(title = it.title, key = it.link)
                                }
                                episodes.forEachIndexed { index, episode ->
                                    item {
                                        PlayRow(title = episode.title) {
                                            selectTarget(
                                                StreamTarget(
                                                    key = episode.link,
                                                    title = episode.title,
                                                    link = episode.link,
                                                    type = "series",
                                                    episodes = episodeRefs,
                                                    episodeIndex = index
                                                )
                                            )
                                        }
                                    }
                                    if (target?.key == episode.link) {
                                        item {
                                            StreamsPanel(
                                                loading = streamsLoading,
                                                error = streamsError,
                                                streams = streams,
                                                downloadNote = downloadNote,
                                                onRetry = {
                                                    target?.let { selectTarget(it) }
                                                },
                                                onPlay = { stream, streamIndex ->
                                                    playStream(stream, streamIndex)
                                                },
                                                onDownload = { stream ->
                                                    val id = DownloadsHelper.enqueue(
                                                        context,
                                                        episode.title,
                                                        stream
                                                    )
                                                    downloadNote = if (id >= 0) {
                                                        "Download started — track it from " +
                                                            "the notification shade or " +
                                                            "Downloads tab."
                                                    } else {
                                                        "Could not start this download " +
                                                            "on this device."
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Info screen for CloudStream extension content (same Vega layout).
// ---------------------------------------------------------------------------

@Composable
fun ExtInfoScreen(
    bridge: ExtensionBridge,
    post: Post,
    watchlist: WatchlistStore,
    onBack: () -> Unit,
    onPlay: (PlayerArgs) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember(post.link) { mutableStateOf<ExtDetail?>(null) }
    var error by remember(post.link) { mutableStateOf<String?>(null) }
    var retryKey by remember(post.link) { mutableStateOf(0) }

    var target by remember(post.link) { mutableStateOf<StreamTarget?>(null) }
    var streams by remember(post.link) { mutableStateOf<List<StreamItem>?>(null) }
    var streamsLoading by remember(post.link) { mutableStateOf(false) }
    var streamsError by remember(post.link) { mutableStateOf<String?>(null) }
    var downloadNote by remember(post.link) { mutableStateOf<String?>(null) }

    var saved by remember(post.link) {
        mutableStateOf(watchlist.contains(bridge.id, post.link))
    }

    LaunchedEffect(post.link, retryKey) {
        error = null
        try {
            detail = bridge.details(post.link)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load details"
        }
    }

    fun selectTarget(newTarget: StreamTarget) {
        if (target?.key == newTarget.key && streams != null) {
            target = null
            return
        }
        target = newTarget
        streams = null
        streamsError = null
        downloadNote = null
        streamsLoading = true
        scope.launch {
            try {
                streams = bridge.streams(newTarget.link)
            } catch (e: Exception) {
                streamsError = e.message ?: "Failed to load streams"
            }
            streamsLoading = false
        }
    }

    fun playStream(stream: StreamItem, index: Int) {
        val currentTarget = target ?: return
        val loader: (suspend (EpisodeRef) -> List<StreamItem>)? =
            if (currentTarget.episodes.isNotEmpty()) {
                { ref -> bridge.streams(ref.key) }
            } else {
                null
            }
        onPlay(
            PlayerArgs(
                title = currentTarget.title,
                subtitle = bridge.displayName,
                streams = streams ?: listOf(stream),
                startIndex = index,
                episodes = currentTarget.episodes,
                episodeIndex = currentTarget.episodeIndex,
                loadEpisodeStreams = loader
            )
        )
    }

    val meta = detail
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            InfoHeader(
                image = meta?.poster?.takeIf { it.isNotBlank() }
                    ?: meta?.image?.takeIf { it.isNotBlank() }
                    ?: post.image,
                saved = saved,
                onBack = onBack,
                onToggleSaved = {
                    saved = watchlist.toggle(
                        WatchlistItem(
                            kind = "extension",
                            sourceId = bridge.id,
                            sourceName = bridge.displayName,
                            title = post.title,
                            link = post.link,
                            image = post.image,
                            type = meta?.type ?: ""
                        )
                    )
                }
            )
        }

        when {
            error != null -> item {
                ErrorCard(message = error!!, onRetry = { retryKey++ })
            }

            meta == null -> item { InfoSkeleton() }

            else -> {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = meta.title,
                            color = VegaText,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val metaLine = listOfNotNull(
                            meta.type.replaceFirstChar { it.uppercase() },
                            meta.year?.toString(),
                            meta.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
                            meta.tags.takeIf { it.isNotEmpty() }
                                ?.joinToString(", ")
                        ).joinToString("  •  ")
                        if (metaLine.isNotBlank()) {
                            Text(text = metaLine, color = VegaSubText, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        MetaChip(text = bridge.displayName, accent = true)
                        if (meta.cast.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Cast: " + meta.cast.joinToString(", "),
                                color = VegaSubText,
                                fontSize = 13.sp
                            )
                        }
                        if (meta.synopsis.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(text = meta.synopsis, color = VegaText, fontSize = 14.sp)
                        }
                    }
                }

                if (meta.episodes.isNotEmpty()) {
                    item {
                        Text(
                            text = if (meta.episodes.size > 1) "Episodes" else "Play",
                            color = VegaText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp
                            )
                        )
                    }
                    val episodeRefs = meta.episodes.map {
                        EpisodeRef(title = it.title, key = it.data)
                    }
                    meta.episodes.forEachIndexed { index, episode ->
                        item {
                            PlayRow(title = episode.title) {
                                selectTarget(
                                    StreamTarget(
                                        key = episode.data,
                                        title = episode.title,
                                        link = episode.data,
                                        type = meta.type,
                                        episodes = episodeRefs,
                                        episodeIndex = index
                                    )
                                )
                            }
                        }
                        if (target?.key == episode.data) {
                            item {
                                StreamsPanel(
                                    loading = streamsLoading,
                                    error = streamsError,
                                    streams = streams,
                                    downloadNote = downloadNote,
                                    onRetry = { target?.let { selectTarget(it) } },
                                    onPlay = { stream, streamIndex ->
                                        playStream(stream, streamIndex)
                                    },
                                    onDownload = { stream ->
                                        val id = DownloadsHelper.enqueue(
                                            context,
                                            episode.title,
                                            stream
                                        )
                                        downloadNote = if (id >= 0) {
                                            "Download started — track it from the " +
                                                "notification shade or Downloads tab."
                                        } else {
                                            "Could not start this download on this device."
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared Info pieces
// ---------------------------------------------------------------------------

@Composable
private fun InfoHeader(
    image: String,
    saved: Boolean,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(VegaSurface)
    ) {
        if (image.isNotBlank()) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Bottom gradient fading the backdrop into the page background.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.25f),
                            Color.Transparent,
                            VegaBlack
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CircleIconButton(icon = VegaIconName.Back, onClick = onBack)
            CircleIconButton(
                icon = VegaIconName.Bookmark,
                tint = if (saved) Tomato else Color.White,
                iconFilled = saved,
                onClick = onToggleSaved
            )
        }
    }
}

@Composable
private fun InfoSkeleton() {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.7f).height(26.dp), corner = 8.dp)
        Spacer(modifier = Modifier.height(12.dp))
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.45f).height(14.dp), corner = 6.dp)
        Spacer(modifier = Modifier.height(16.dp))
        SkeletonBlock(modifier = Modifier.fillMaxWidth().height(13.dp), corner = 6.dp)
        Spacer(modifier = Modifier.height(8.dp))
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.9f).height(13.dp), corner = 6.dp)
        Spacer(modifier = Modifier.height(8.dp))
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.6f).height(13.dp), corner = 6.dp)
    }
}

@Composable
private fun PlayRow(title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = VegaSurface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VegaIcon(name = VegaIconName.Play, tint = Tomato, size = 18.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                color = VegaText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            VegaIcon(name = VegaIconName.ChevronRight, tint = VegaSubText, size = 18.dp)
        }
    }
}

/**
 * The streams list shown on the Info screen: one card per server with
 * quality / type chips, tap to play, download icon per stream.
 */
@Composable
private fun StreamsPanel(
    loading: Boolean,
    error: String?,
    streams: List<StreamItem>?,
    downloadNote: String?,
    onRetry: () -> Unit,
    onPlay: (StreamItem, Int) -> Unit,
    onDownload: (StreamItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(VegaSurface.copy(alpha = 0.55f))
            .padding(12.dp)
    ) {
        Text(
            text = "STREAMS",
            color = Tomato,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        when {
            loading -> LoadingBox()
            error != null -> {
                Text(text = error, color = VegaSubText, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                VegaButton(text = "Retry", onClick = onRetry)
            }

            streams.isNullOrEmpty() -> Text(
                text = "No streams found for this title.",
                color = VegaSubText,
                fontSize = 13.sp
            )

            else -> streams.forEachIndexed { index, stream ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onPlay(stream, index) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = VegaSurfaceHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VegaIcon(name = VegaIconName.Play, tint = Tomato, size = 18.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stream.server,
                                color = VegaText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val chips = listOfNotNull(
                                stream.quality?.takeIf { it.isNotBlank() }?.let { it + "p" },
                                stream.tag?.takeIf { it.isNotBlank() },
                                stream.type.takeIf { it.isNotBlank() }?.uppercase()
                            )
                            if (chips.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(5.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    chips.forEach { chip -> MetaChip(text = chip) }
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .clickable { onDownload(stream) },
                            contentAlignment = Alignment.Center
                        ) {
                            VegaIcon(
                                name = VegaIconName.Download,
                                tint = VegaSubText,
                                size = 20.dp
                            )
                        }
                    }
                }
            }
        }
        if (downloadNote != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = downloadNote, color = Tomato, fontSize = 12.sp)
        }
    }
}
