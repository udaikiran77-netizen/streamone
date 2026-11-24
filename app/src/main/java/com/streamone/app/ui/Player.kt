package com.streamone.app.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamone.app.engine.StreamItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One episode in a series, identified by an opaque key the loader understands. */
data class EpisodeRef(val title: String, val key: String)

/** Everything the player needs, including optional next-episode support. */
class PlayerArgs(
    val title: String,
    /** Provider / server label shown under the title. */
    val subtitle: String,
    val streams: List<StreamItem>,
    val startIndex: Int = 0,
    val episodes: List<EpisodeRef> = emptyList(),
    val episodeIndex: Int = -1,
    val loadEpisodeStreams: (suspend (EpisodeRef) -> List<StreamItem>)? = null
)

/**
 * Builds a MediaSource for one stream with that stream's own headers applied
 * to the HTTP data source — identical behaviour to the previous player
 * (streams refuse to play without the exact headers getStream returned).
 */
private fun buildMediaSource(context: Context, stream: StreamItem): MediaSource {
    val headers = HashMap<String, String>(stream.headers)
    val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
    headers.entries
        .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
        ?.let { httpFactory.setUserAgent(it.value) }
    if (headers.isNotEmpty()) {
        httpFactory.setDefaultRequestProperties(headers)
    }
    val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
    val mediaItem = MediaItem.Builder().setUri(stream.link).build()
    return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
}

@Composable
fun PlayerScreen(args: PlayerArgs, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context as? Activity }
    val scope = rememberCoroutineScope()

    var currentTitle by remember { mutableStateOf(args.title) }
    var currentStreams by remember { mutableStateOf(args.streams) }
    var currentIndex by remember {
        mutableStateOf(
            args.startIndex.coerceIn(0, maxOf(0, args.streams.size - 1))
        )
    }
    var episodeIndex by remember { mutableStateOf(args.episodeIndex) }
    val currentStream = currentStreams.getOrNull(currentIndex)

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var bufferedMs by remember { mutableStateOf(0L) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var busyNext by remember { mutableStateOf(false) }

    fun playStream(stream: StreamItem, keepPosition: Boolean) {
        val resumeAt = if (keepPosition) player.currentPosition else 0L
        errorMessage = null
        isBuffering = true
        player.setMediaSource(buildMediaSource(context, stream))
        player.prepare()
        if (resumeAt > 0) player.seekTo(resumeAt)
        player.playWhenReady = true
    }

    // Initial load.
    LaunchedEffect(player) {
        currentStream?.let { playStream(it, keepPosition = false) }
    }

    // Error listener.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = error.message ?: "Playback failed"
                isBuffering = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Position / state polling for the console.
    LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            isBuffering = player.playbackState == Player.STATE_BUFFERING
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration
            durationMs = if (duration == C.TIME_UNSET) 0L else duration.coerceAtLeast(0L)
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Orientation: landscape while the player is open (Vega behaviour), and
    // the system bars are hidden for an immersive picture.
    var landscape by remember { mutableStateOf(true) }
    LaunchedEffect(landscape) {
        activity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Controls visibility with auto-hide while playing.
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsNonce by remember { mutableStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    fun pokeControls() {
        controlsVisible = true
        controlsNonce++
    }
    LaunchedEffect(controlsNonce, isPlaying) {
        if (controlsVisible && isPlaying && !seeking) {
            delay(3200)
            controlsVisible = false
        }
    }

    // Double-tap seek flash indicator (-1 = back 10s, +1 = forward 10s).
    var seekFlash by remember { mutableStateOf(0) }
    var seekFlashKey by remember { mutableStateOf(0) }
    LaunchedEffect(seekFlashKey) {
        if (seekFlash != 0) {
            delay(600)
            seekFlash = 0
        }
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs)
            .coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        player.seekTo(target)
        pokeControls()
    }

    var showSources by remember { mutableStateOf(false) }

    val nextLoader = args.loadEpisodeStreams
    val hasNextEpisode = nextLoader != null && episodeIndex >= 0 &&
        episodeIndex < args.episodes.size - 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )

        // Gesture layer: tap toggles the console, double-tap on the side
        // thirds seeks -/+ 10 seconds (Vega Gestures behaviour).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            controlsNonce++
                        },
                        onDoubleTap = { offset ->
                            when {
                                offset.x < size.width * 0.35f -> {
                                    seekBy(-10_000)
                                    seekFlash = -1
                                    seekFlashKey++
                                }
                                offset.x > size.width * 0.65f -> {
                                    seekBy(10_000)
                                    seekFlash = 1
                                    seekFlashKey++
                                }
                            }
                        }
                    )
                }
        )

        // Seek flash indicators.
        if (seekFlash != 0) {
            Box(
                modifier = Modifier
                    .align(if (seekFlash < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 48.dp)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                VegaIcon(
                    name = if (seekFlash < 0) VegaIconName.Replay10 else VegaIconName.Forward10,
                    tint = Color.White,
                    size = 34.dp
                )
            }
        }

        // Loading spinner (tomato, like Vega's Loader).
        if ((isBuffering || busyNext) && errorMessage == null) {
            CircularProgressIndicator(
                color = Tomato,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
            )
        }

        // Error state with retry + back.
        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Playback failed",
                    color = VegaText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = errorMessage!!,
                    color = VegaSubText,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton(text = "Back", onClick = onBack)
                    VegaButton(text = "Retry") {
                        currentStream?.let { playStream(it, keepPosition = true) }
                    }
                }
            }
        }

        // ---- Media console (top + centre + bottom), Vega style ----
        AnimatedVisibility(
            visible = controlsVisible && errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top scrim + TopControls: back, title, server label.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIconButton(icon = VegaIconName.Back, onClick = onBack)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentTitle,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val serverLabel = currentStream?.server?.takeIf { it.isNotBlank() }
                        Text(
                            text = listOfNotNull(args.subtitle, serverLabel)
                                .joinToString(" • "),
                            color = VegaSubText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val quality = currentStream?.quality?.takeIf { it.isNotBlank() }
                    if (quality != null) {
                        MetaChip(text = quality + "p", accent = true)
                    }
                }

                // Centre PlayPause + skip controls.
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    PlayerControlButton(icon = VegaIconName.Replay10, size = 52.dp) {
                        seekBy(-10_000)
                        seekFlash = -1
                        seekFlashKey++
                    }
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable {
                                if (player.isPlaying) player.pause() else player.play()
                                pokeControls()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        VegaIcon(
                            name = if (isPlaying) VegaIconName.Pause else VegaIconName.Play,
                            tint = Color.White,
                            size = 36.dp
                        )
                    }
                    PlayerControlButton(icon = VegaIconName.Forward10, size = 52.dp) {
                        seekBy(10_000)
                        seekFlash = 1
                        seekFlashKey++
                    }
                }

                // Bottom scrim + BottomControls: seekbar, times, buttons.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    VegaSeekbar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedMs = bufferedMs,
                        onSeek = { target ->
                            player.seekTo(target)
                            pokeControls()
                        },
                        onDragStateChange = { dragging ->
                            seeking = dragging
                            if (dragging) pokeControls()
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerControlButton(icon = VegaIconName.List, size = 42.dp) {
                            showSources = true
                            pokeControls()
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (hasNextEpisode) {
                            PlayerControlButton(icon = VegaIconName.Next, size = 42.dp) {
                                scope.launch {
                                    busyNext = true
                                    try {
                                        val nextRef = args.episodes[episodeIndex + 1]
                                        val streams = nextLoader!!(nextRef)
                                        if (streams.isNotEmpty()) {
                                            episodeIndex += 1
                                            currentTitle = nextRef.title
                                            currentStreams = streams
                                            currentIndex = 0
                                            playStream(streams[0], keepPosition = false)
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = e.message
                                            ?: "Could not load the next episode"
                                    }
                                    busyNext = false
                                    pokeControls()
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        PlayerControlButton(
                            icon = if (landscape) {
                                VegaIconName.FullscreenExit
                            } else {
                                VegaIconName.Fullscreen
                            },
                            size = 42.dp
                        ) {
                            landscape = !landscape
                            pokeControls()
                        }
                    }
                }
            }
        }

        // Source / quality picker (reopens the stream list for this title).
        if (showSources) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { showSources = false }
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(VegaSurface)
                    .clickable { }
                    .padding(20.dp)
            ) {
                Text(
                    text = "Sources",
                    color = VegaText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    itemsIndexed(currentStreams) { index, stream ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    currentIndex = index
                                    playStream(stream, keepPosition = true)
                                    showSources = false
                                }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stream.server,
                                    color = VegaText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val sub = listOfNotNull(
                                    stream.quality?.takeIf { it.isNotBlank() }
                                        ?.let { it + "p" },
                                    stream.tag?.takeIf { it.isNotBlank() },
                                    stream.type.takeIf { it.isNotBlank() }?.uppercase()
                                ).joinToString(" • ")
                                if (sub.isNotBlank()) {
                                    Text(text = sub, color = VegaSubText, fontSize = 12.sp)
                                }
                            }
                            if (index == currentIndex) {
                                VegaIcon(
                                    name = VegaIconName.Check,
                                    tint = Tomato,
                                    size = 20.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControlButton(
    icon: VegaIconName,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        VegaIcon(name = icon, tint = Color.White, size = size * 0.62f)
    }
}

/**
 * Vega Seekbar: thin track, buffered indicator, tomato progress + thumb,
 * current / total time labels. Supports tap-to-seek and drag.
 */
@Composable
private fun VegaSeekbar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    onDragStateChange: (Boolean) -> Unit
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val played = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shown = dragFraction ?: played
    val bufferedFraction = if (durationMs > 0) {
        (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val previewMs = (shown * durationMs).toLong()

    Column(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0) {
                            onSeek((offset.x / size.width * durationMs).toLong())
                        }
                    }
                }
                .pointerInput(durationMs) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            onDragStateChange(true)
                        },
                        onDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width)
                                .coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val fraction = dragFraction
                            if (fraction != null && durationMs > 0) {
                                onSeek((fraction * durationMs).toLong())
                            }
                            dragFraction = null
                            onDragStateChange(false)
                        },
                        onDragCancel = {
                            dragFraction = null
                            onDragStateChange(false)
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val trackWidth = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            )
            Box(
                modifier = Modifier
                    .width(trackWidth * bufferedFraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.45f))
            )
            Box(
                modifier = Modifier
                    .width(trackWidth * shown)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tomato)
            )
            Box(
                modifier = Modifier
                    .offset(
                        x = (trackWidth * shown - 7.dp)
                            .coerceIn(0.dp, maxOf(0.dp, trackWidth - 14.dp))
                    )
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Tomato)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatTime(previewMs), color = Color.White, fontSize = 12.sp)
            Text(
                text = formatTime(durationMs),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
        }
    }
}
