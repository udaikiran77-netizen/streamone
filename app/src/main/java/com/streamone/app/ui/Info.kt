package com.streamone.app.ui

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

/** What the inline streams panel is currently resolving. */
private data class StreamTarget(
    val key: String,
    val title: String,
    val link: String,
    val type: String,
    val episodes: List<EpisodeRef> = emptyList(),
    val episodeIndex: Int = -1
)

/**
 * Splits provider tags into (year, genres): tags often arrive comma-joined
 * ("Action, Drama") and may embed a 4-digit year, which Vega shows in the
 * meta line rather than as a chip.
 */
private fun splitTags(tags: List<String>): Pair<String?, List<String>> {
    val flat = tags
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val year = flat.firstOrNull { it.matches(Regex("\\d{4}")) }
    val genres = flat.filter { it != year }.distinct().take(10)
    return year to genres
}

private fun castInitials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[parts.size - 1].take(1)).uppercase()
    }
}

// ---------------------------------------------------------------------------
// Vega Info layout for provider content: a backdrop zone (blurred when the
// only artwork is a portrait poster, like Vega's Info.tsx) with the poster
// card and title block overlapping its bottom edge, an action row, storyline
// and cast, then link groups; tapping a link or episode expands the streams
// panel (servers with quality/type chips and a download action per stream)
// right underneath it.
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
    val listState = rememberLazyListState()
    val headerParallax by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                1000f
            }
        }
    }

    var info by remember(post.link) { mutableStateOf<Info?>(null) }
    var error by remember(post.link) { mutableStateOf<String?>(null) }
    var retryKey by remember(post.link) { mutableStateOf(0) }

    var episodesByLink by remember(post.link) {
        mutableStateOf<Map<String, List<EpisodeLink>>>(emptyMap())
    }
    var episodesLoadingFor by remember(post.link) { mutableStateOf<String?>(null) }
    var episodesError by remember(post.link) { mutableStateOf<String?>(null) }
    var pendingPlayLink by remember(post.link) { mutableStateOf<String?>(null) }

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

    fun toggleSaved() {
        val meta = info
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

    fun episodeTarget(
        episodes: List<EpisodeLink>,
        index: Int,
        type: String
    ): StreamTarget {
        val episode = episodes[index]
        return StreamTarget(
            key = episode.link,
            title = episode.title,
            link = episode.link,
            type = type,
            episodes = episodes.map { EpisodeRef(title = it.title, key = it.link) },
            episodeIndex = index
        )
    }

    fun loadEpisodes(episodesLink: String) {
        if (episodesLoadingFor == episodesLink) return
        episodesLoadingFor = episodesLink
        episodesError = null
        scope.launch {
            try {
                val loaded = engine.getEpisodes(provider, episodesLink)
                episodesByLink = episodesByLink + (episodesLink to loaded)
            } catch (e: Exception) {
                episodesError = e.message ?: "Failed to load episodes"
            }
            episodesLoadingFor = null
        }
    }

    // The header Play button asked for episodes that were still loading:
    // once they arrive, open the first episode's streams.
    LaunchedEffect(episodesByLink, pendingPlayLink) {
        val link = pendingPlayLink ?: return@LaunchedEffect
        val episodes = episodesByLink[link]
        if (!episodes.isNullOrEmpty()) {
            pendingPlayLink = null
            selectTarget(episodeTarget(episodes, 0, "series"))
        }
    }

    fun playFirst() {
        val meta = info ?: return
        val first = meta.linkList.firstOrNull() ?: return
        scope.launch { listState.animateScrollToItem(2) }
        when {
            first.directLinks.isNotEmpty() -> {
                val direct = first.directLinks.first()
                selectTarget(
                    StreamTarget(
                        key = direct.link,
                        title = direct.title,
                        link = direct.link,
                        type = direct.type ?: meta.type
                    )
                )
            }

            first.episodesLink != null -> {
                val episodes = episodesByLink[first.episodesLink]
                if (!episodes.isNullOrEmpty()) {
                    selectTarget(episodeTarget(episodes, 0, "series"))
                } else {
                    pendingPlayLink = first.episodesLink
                    loadEpisodes(first.episodesLink)
                }
            }
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
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            val backdrop = meta?.image?.takeIf { it.isNotBlank() } ?: post.image
            val poster = meta?.poster?.takeIf { it.isNotBlank() }
                ?: meta?.image?.takeIf { it.isNotBlank() }
                ?: post.image
            val (tagYear, _) = splitTags(meta?.tags ?: emptyList())
            BackdropHeader(
                backdropUrl = backdrop,
                posterUrl = poster,
                posterLike = poster == backdrop,
                title = meta?.title ?: post.title,
                rating = meta?.rating,
                facts = listOfNotNull(
                    meta?.type?.replaceFirstChar { it.uppercase() },
                    tagYear
                ).joinToString("  •  "),
                sourceName = provider.displayName,
                saved = saved,
                parallaxPx = headerParallax,
                onBack = onBack,
                onToggleSaved = { toggleSaved() }
            )
        }

        when {
            error != null -> item {
                ErrorCard(message = error!!, onRetry = { retryKey++ })
            }

            meta == null -> item { InfoSkeleton() }

            else -> {
                item {
                    StaggeredIn(index = 1) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val (_, genres) = splitTags(meta.tags)
                            if (genres.isNotEmpty()) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 14.dp)
                                ) {
                                    items(genres) { genre -> MetaChip(text = genre) }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            InfoActionRow(
                                saved = saved,
                                onPlay = { playFirst() },
                                onToggleSaved = { toggleSaved() }
                            )
                            if (meta.synopsis.isNotBlank()) {
                                Spacer(modifier = Modifier.height(22.dp))
                                StorylineBlock(
                                    synopsis = meta.synopsis,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            if (meta.cast.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(22.dp))
                                CastRow(cast = meta.cast)
                            }
                        }
                    }
                }

                for (linkItem in meta.linkList) {
                    item {
                        Row(
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp
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
                                    StaggeredIn(index = 0) {
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
                                                    "Could not start this download on " +
                                                        "this device."
                                                }
                                            }
                                        )
                                    }
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
                                        loadEpisodes(episodesLink)
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
                                episodes.forEachIndexed { index, episode ->
                                    item {
                                        PlayRow(
                                            title = episode.title,
                                            badge = (index + 1).toString()
                                        ) {
                                            selectTarget(
                                                episodeTarget(episodes, index, "series")
                                            )
                                        }
                                    }
                                    if (target?.key == episode.link) {
                                        item {
                                            StaggeredIn(index = 0) {
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
                                                            "Download started — track it " +
                                                                "from the notification " +
                                                                "shade or Downloads tab."
                                                        } else {
                                                            "Could not start this " +
                                                                "download on this device."
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
    val listState = rememberLazyListState()
    val headerParallax by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                1000f
            }
        }
    }

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

    fun toggleSaved() {
        val meta = detail
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

    fun episodeTarget(meta: ExtDetail, index: Int): StreamTarget {
        val episode = meta.episodes[index]
        return StreamTarget(
            key = episode.data,
            title = episode.title,
            link = episode.data,
            type = meta.type,
            episodes = meta.episodes.map { EpisodeRef(title = it.title, key = it.data) },
            episodeIndex = index
        )
    }

    fun playFirst() {
        val meta = detail ?: return
        if (meta.episodes.isEmpty()) return
        scope.launch { listState.animateScrollToItem(2) }
        selectTarget(episodeTarget(meta, 0))
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
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            val backdrop = meta?.image?.takeIf { it.isNotBlank() } ?: post.image
            val poster = meta?.poster?.takeIf { it.isNotBlank() }
                ?: meta?.image?.takeIf { it.isNotBlank() }
                ?: post.image
            BackdropHeader(
                backdropUrl = backdrop,
                posterUrl = poster,
                posterLike = poster == backdrop,
                title = meta?.title ?: post.title,
                rating = meta?.rating,
                facts = listOfNotNull(
                    meta?.type?.replaceFirstChar { it.uppercase() },
                    meta?.year?.toString()
                ).joinToString("  •  "),
                sourceName = bridge.displayName,
                saved = saved,
                parallaxPx = headerParallax,
                onBack = onBack,
                onToggleSaved = { toggleSaved() }
            )
        }

        when {
            error != null -> item {
                ErrorCard(message = error!!, onRetry = { retryKey++ })
            }

            meta == null -> item { InfoSkeleton() }

            else -> {
                item {
                    StaggeredIn(index = 1) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val (_, genres) = splitTags(meta.tags)
                            if (genres.isNotEmpty()) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 14.dp)
                                ) {
                                    items(genres) { genre -> MetaChip(text = genre) }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            InfoActionRow(
                                saved = saved,
                                onPlay = { playFirst() },
                                onToggleSaved = { toggleSaved() }
                            )
                            if (meta.synopsis.isNotBlank()) {
                                Spacer(modifier = Modifier.height(22.dp))
                                StorylineBlock(
                                    synopsis = meta.synopsis,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            if (meta.cast.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(22.dp))
                                CastRow(cast = meta.cast)
                            }
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
                                start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp
                            )
                        )
                    }
                    meta.episodes.forEachIndexed { index, episode ->
                        item {
                            PlayRow(
                                title = episode.title,
                                badge = if (meta.episodes.size > 1) {
                                    (index + 1).toString()
                                } else {
                                    null
                                }
                            ) {
                                selectTarget(episodeTarget(meta, index))
                            }
                        }
                        if (target?.key == episode.data) {
                            item {
                                StaggeredIn(index = 0) {
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
                                                "Could not start this download on " +
                                                    "this device."
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

// ---------------------------------------------------------------------------
// Shared Info pieces (modelled on Vega's Info.tsx + ContentOverview)
// ---------------------------------------------------------------------------

/**
 * Backdrop zone: 300dp of artwork with a bottom gradient into black and a
 * light scroll parallax. When the only artwork is the portrait poster
 * (posterLike), the backdrop is that poster blurred at 75% opacity over
 * black — Vega's exact treatment — and the sharp poster card overlaps the
 * bottom-left next to the title block. A separate landscape image is shown
 * unblurred instead. Modifier.blur needs API 31+, so older devices get a
 * dark scrim fallback.
 */
@Composable
private fun BackdropHeader(
    backdropUrl: String,
    posterUrl: String,
    posterLike: Boolean,
    title: String,
    rating: String?,
    facts: String,
    sourceName: String,
    saved: Boolean,
    parallaxPx: Float,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(372.dp)
            .background(VegaBlack)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.TopCenter)
                .clipToBounds()
        ) {
            if (backdropUrl.isNotBlank()) {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            IntOffset(0, (parallaxPx * 0.45f).roundToInt())
                        }
                        .scale(if (posterLike) 1.18f else 1.05f)
                        .alpha(
                            (if (posterLike) 0.75f else 1f) *
                                (1f - parallaxPx / 900f).coerceIn(0.3f, 1f)
                        )
                        .then(
                            if (posterLike && canBlur) {
                                Modifier.blur(20.dp)
                            } else {
                                Modifier
                            }
                        )
                )
            }
            if (posterLike && !canBlur) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.30f),
                                Color.Transparent,
                                VegaBlack
                            ),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY
                        )
                    )
            )
        }

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

        // Poster card + title block overlapping the backdrop's bottom edge.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, VegaBorder, RoundedCornerShape(12.dp))
                    .background(VegaSurface)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 2.dp)
            ) {
                MetaChip(text = sourceName, accent = true)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    color = VegaText,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                val cleanRating = rating?.takeIf { it.isNotBlank() }
                if (cleanRating != null || facts.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (cleanRating != null) {
                            Text(
                                text = "★ $cleanRating",
                                color = Tomato,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (cleanRating != null && facts.isNotBlank()) {
                            Text(
                                text = "  •  ",
                                color = VegaSubText,
                                fontSize = 13.sp
                            )
                        }
                        if (facts.isNotBlank()) {
                            Text(text = facts, color = VegaSubText, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Wide tomato Play + ghost Watchlist toggle, like Vega's action row. */
@Composable
private fun InfoActionRow(
    saved: Boolean,
    onPlay: () -> Unit,
    onToggleSaved: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VegaButton(
            text = "▶  Play",
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            onClick = onPlay
        )
        GhostButton(
            text = if (saved) "✓  Saved" else "+  Watchlist",
            modifier = Modifier.height(48.dp),
            onClick = onToggleSaved
        )
    }
}

/** "Storyline" with an animated Read more / Show less expansion. */
@Composable
private fun StorylineBlock(synopsis: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val expandable = synopsis.length > 220
    Column(
        modifier = modifier.animateContentSize(
            animationSpec = vegaTweenSpec(350)
        )
    ) {
        Text(
            text = "Storyline",
            color = VegaText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = synopsis,
            color = VegaText.copy(alpha = 0.88f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis
        )
        if (expandable) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (expanded) "Show less" else "Read more",
                color = Tomato,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .pressClickable(pressedScale = 0.94f) { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/** Horizontal cast row: circular initial avatars (providers give names only). */
@Composable
private fun CastRow(cast: List<String>) {
    Column {
        Text(
            text = "Cast",
            color = VegaText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(cast) { name ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(64.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(VegaSurfaceHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = castInitials(name),
                            color = Tomato,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = name,
                        color = VegaSubText,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
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

/**
 * One tappable link / episode row. Episodes carry a number badge in place
 * of the play glyph, Vega SeasonList style.
 */
@Composable
private fun PlayRow(
    title: String,
    badge: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .pressClickable(pressedScale = 0.98f) { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = VegaSurface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(VegaSurfaceHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badge,
                        color = Tomato,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            } else {
                VegaIcon(name = VegaIconName.Play, tint = Tomato, size = 18.dp)
            }
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
 * quality / type chips, tap to play, download icon per stream. Cards
 * stagger in and the panel grows/shrinks smoothly.
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
            .animateContentSize(animationSpec = vegaTweenSpec(300))
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
                StaggeredIn(index = index) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .pressClickable(pressedScale = 0.98f) {
                                onPlay(stream, index)
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = VegaSurfaceHigh
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 14.dp, vertical = 12.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            VegaIcon(
                                name = VegaIconName.Play,
                                tint = Tomato,
                                size = 18.dp
                            )
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
                                    stream.quality?.takeIf { it.isNotBlank() }
                                        ?.let { it + "p" },
                                    stream.tag?.takeIf { it.isNotBlank() },
                                    stream.type.takeIf { it.isNotBlank() }
                                        ?.uppercase()
                                )
                                if (chips.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(5.dp))
                                    Row(
                                        horizontalArrangement =
                                            Arrangement.spacedBy(6.dp)
                                    ) {
                                        chips.forEach { chip ->
                                            MetaChip(text = chip)
                                        }
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .pressClickable(pressedScale = 0.9f) {
                                        onDownload(stream)
                                    },
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
        }
        if (downloadNote != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = downloadNote, color = Tomato, fontSize = 12.sp)
        }
    }
}
