package com.streamone.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.streamone.app.engine.CatalogItem
import com.streamone.app.engine.EpisodeLink
import com.streamone.app.engine.Info
import com.streamone.app.engine.Post
import com.streamone.app.engine.ProviderEngine
import com.streamone.app.engine.ProviderEntry
import com.streamone.app.engine.ProviderRepository
import com.streamone.app.engine.StreamItem
import kotlinx.coroutines.launch

private val Tomato = Color(0xFFFF6347)
private val VegaBlack = Color(0xFF000000)
private val VegaSurface = Color(0xFF171717)
private val VegaText = Color(0xFFF2F2F2)
private val VegaSubText = Color(0xFFC4C4C4)

// ---------------------------------------------------------------------------
// Navigation: a tiny back-stack state holder (no nav-compose dependency).
// ---------------------------------------------------------------------------

private sealed interface Screen {
    data object Home : Screen
    data class Posts(val provider: ProviderEntry) : Screen
    data class Detail(val provider: ProviderEntry, val post: Post) : Screen
    data class Streams(
        val provider: ProviderEntry,
        val title: String,
        val link: String,
        val type: String
    ) : Screen

    data class Player(val title: String, val stream: StreamItem) : Screen
}

class MainActivity : ComponentActivity() {

    private lateinit var repository: ProviderRepository
    private lateinit var engine: ProviderEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ProviderRepository(applicationContext)
        engine = ProviderEngine(applicationContext, repository)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Tomato,
                    background = VegaBlack,
                    surface = VegaSurface,
                    onBackground = VegaText,
                    onSurface = VegaText
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = VegaBlack
                ) {
                    StreamOneApp(engine)
                }
            }
        }
    }
}

@Composable
private fun StreamOneApp(engine: ProviderEngine) {
    var backStack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    val current = backStack.last()

    fun navigate(screen: Screen) {
        backStack = backStack + screen
    }

    fun goBack() {
        if (backStack.size > 1) backStack = backStack.dropLast(1)
    }

    BackHandler(enabled = backStack.size > 1) { goBack() }

    when (val screen = current) {
        is Screen.Home -> HomeScreen(
            engine = engine,
            onProvider = { navigate(Screen.Posts(it)) }
        )

        is Screen.Posts -> PostsScreen(
            engine = engine,
            provider = screen.provider,
            onBack = { goBack() },
            onPost = { navigate(Screen.Detail(screen.provider, it)) }
        )

        is Screen.Detail -> DetailScreen(
            engine = engine,
            provider = screen.provider,
            post = screen.post,
            onBack = { goBack() },
            onPlay = { title, link, type ->
                navigate(Screen.Streams(screen.provider, title, link, type))
            }
        )

        is Screen.Streams -> StreamsScreen(
            engine = engine,
            provider = screen.provider,
            title = screen.title,
            link = screen.link,
            type = screen.type,
            onBack = { goBack() },
            onStream = { navigate(Screen.Player(screen.title, it)) }
        )

        is Screen.Player -> PlayerScreen(
            stream = screen.stream,
            title = screen.title,
            onBack = { goBack() }
        )
    }
}

// ---------------------------------------------------------------------------
// Shared UI pieces
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text(text = "‹ Back", color = Tomato, fontSize = 16.sp)
            }
        }
        Text(
            text = title,
            color = VegaText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Tomato)
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        color = Tomato,
        fontSize = 14.sp,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun VegaButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tomato,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(text = text, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------------------
// Home: live provider list merged from both manifests.
// ---------------------------------------------------------------------------

@Composable
private fun HomeScreen(engine: ProviderEngine, onProvider: (ProviderEntry) -> Unit) {
    var providers by remember { mutableStateOf<List<ProviderEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            providers = engine.fetchManifests()
        } catch (e: Exception) {
            error = e.message ?: "Could not load providers"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
            .padding(20.dp)
    ) {
        Text(
            text = "StreamOne",
            color = Tomato,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "All your providers. One app.",
            color = VegaSubText,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "PROVIDERS",
            color = VegaText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        when {
            error != null -> ErrorText("Providers failed to load: $error")
            providers == null -> LoadingBox()
            providers!!.isEmpty() -> ErrorText("No providers found. Check your connection.")
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(providers!!) { provider ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProvider(provider) },
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = VegaSurface)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (provider.icon.isNotBlank()) {
                                AsyncImage(
                                    model = provider.icon,
                                    contentDescription = provider.displayName,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                            }
                            Column {
                                Text(
                                    text = provider.displayName,
                                    color = VegaText,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (provider.source == "udai") {
                                        "Your providers"
                                    } else {
                                        "Zenda-Cross"
                                    } + if (provider.version.isNotBlank()) {
                                        " • v${provider.version}"
                                    } else {
                                        ""
                                    },
                                    color = VegaSubText,
                                    fontSize = 13.sp
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
// Posts: catalog chips + poster grid + search, with paging.
// ---------------------------------------------------------------------------

@Composable
private fun PostsScreen(
    engine: ProviderEngine,
    provider: ProviderEntry,
    onBack: () -> Unit,
    onPost: (Post) -> Unit
) {
    var catalogs by remember(provider.id) { mutableStateOf<List<CatalogItem>?>(null) }
    var selectedFilter by remember(provider.id) { mutableStateOf<String?>(null) }
    var posts by remember(provider.id) { mutableStateOf<List<Post>>(emptyList()) }
    var loading by remember(provider.id) { mutableStateOf(false) }
    var error by remember(provider.id) { mutableStateOf<String?>(null) }
    var page by remember(provider.id) { mutableStateOf(1) }
    var searchText by remember(provider.id) { mutableStateOf("") }
    var activeSearch by remember(provider.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadPage(pageToLoad: Int, reset: Boolean) {
        val query = activeSearch
        val filter = selectedFilter
        if (query == null && filter == null) return
        scope.launch {
            loading = true
            if (reset) error = null
            try {
                val result = if (query != null) {
                    engine.getSearchPosts(provider, query, pageToLoad)
                } else {
                    engine.getPosts(provider, filter!!, pageToLoad)
                }
                posts = if (reset) result else posts + result
                page = pageToLoad
            } catch (e: Exception) {
                if (reset) error = e.message ?: "Failed to load posts"
            }
            loading = false
        }
    }

    LaunchedEffect(provider.id) {
        loading = true
        try {
            val cats = engine.getCatalog(provider)
            catalogs = cats
            if (cats.isNotEmpty()) {
                selectedFilter = cats[0].filter
                loadPage(1, reset = true)
            } else {
                loading = false
            }
        } catch (e: Exception) {
            error = e.message ?: "Failed to load catalog"
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
            .padding(horizontal = 16.dp)
    ) {
        TopBar(title = provider.displayName, onBack = onBack)

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            VegaButton(text = "Search") {
                val q = searchText.trim()
                activeSearch = if (q.isEmpty()) null else q
                loadPage(1, reset = true)
            }
        }

        if (activeSearch == null && catalogs != null && catalogs!!.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(catalogs!!) { catalog ->
                    FilterChip(
                        selected = selectedFilter == catalog.filter,
                        onClick = {
                            selectedFilter = catalog.filter
                            loadPage(1, reset = true)
                        },
                        label = { Text(catalog.title) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = VegaSurface,
                            labelColor = VegaSubText,
                            selectedContainerColor = Tomato,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when {
            error != null && posts.isEmpty() -> ErrorText(error!!)
            loading && posts.isEmpty() -> LoadingBox()
            posts.isEmpty() -> Text(
                text = if (activeSearch != null) {
                    "No results for \"$activeSearch\""
                } else {
                    "Nothing here yet."
                },
                color = VegaSubText,
                fontSize = 14.sp,
                modifier = Modifier.padding(16.dp)
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(posts) { post ->
                    Column(
                        modifier = Modifier.clickable { onPost(post) }
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = VegaSurface)
                        ) {
                            AsyncImage(
                                model = post.image,
                                contentDescription = post.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.67f)
                            )
                        }
                        Text(
                            text = post.title,
                            color = VegaText,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        if (posts.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(color = Tomato, modifier = Modifier.size(28.dp))
                } else {
                    VegaButton(text = "Load more") { loadPage(page + 1, reset = false) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Detail: meta info, link list, on-demand episode loading.
// ---------------------------------------------------------------------------

@Composable
private fun DetailScreen(
    engine: ProviderEngine,
    provider: ProviderEntry,
    post: Post,
    onBack: () -> Unit,
    onPlay: (title: String, link: String, type: String) -> Unit
) {
    var info by remember(post.link) { mutableStateOf<Info?>(null) }
    var error by remember(post.link) { mutableStateOf<String?>(null) }
    var episodesByLink by remember(post.link) {
        mutableStateOf<Map<String, List<EpisodeLink>>>(emptyMap())
    }
    var episodesLoadingFor by remember(post.link) { mutableStateOf<String?>(null) }
    var episodesError by remember(post.link) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(post.link) {
        try {
            info = engine.getMeta(provider, post.link)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load details"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
            .padding(horizontal = 16.dp)
    ) {
        TopBar(title = post.title, onBack = onBack)
        when {
            error != null -> ErrorText(error!!)
            info == null -> LoadingBox()
            else -> {
                val meta = info!!
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        val hero = meta.poster?.takeIf { it.isNotBlank() } ?: meta.image
                        if (hero.isNotBlank()) {
                            AsyncImage(
                                model = hero,
                                contentDescription = meta.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp)
                                    .clip(RoundedCornerShape(20.dp))
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Text(
                            text = meta.title,
                            color = VegaText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val metaLine = listOfNotNull(
                            meta.type.replaceFirstChar { it.uppercase() },
                            meta.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
                            meta.tags.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ).joinToString(" • ")
                        if (metaLine.isNotBlank()) {
                            Text(text = metaLine, color = VegaSubText, fontSize = 13.sp)
                        }
                        if (meta.cast.isNotEmpty()) {
                            Text(
                                text = "Cast: " + meta.cast.joinToString(", "),
                                color = VegaSubText,
                                fontSize = 13.sp
                            )
                        }
                        if (meta.synopsis.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = meta.synopsis, color = VegaText, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    items(meta.linkList) { linkItem ->
                        Column {
                            Text(
                                text = listOfNotNull(
                                    linkItem.title.takeIf { it.isNotBlank() },
                                    linkItem.quality?.takeIf { it.isNotBlank() }
                                ).joinToString(" • "),
                                color = Tomato,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            if (linkItem.directLinks.isNotEmpty()) {
                                linkItem.directLinks.forEach { direct ->
                                    VegaButton(text = "▶  ${direct.title}") {
                                        onPlay(
                                            direct.title,
                                            direct.link,
                                            direct.type ?: meta.type
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            } else if (linkItem.episodesLink != null) {
                                val episodesLink = linkItem.episodesLink
                                val episodes = episodesByLink[episodesLink]
                                when {
                                    episodesLoadingFor == episodesLink -> LoadingBox()
                                    episodes == null -> VegaButton(text = "Load episodes") {
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
                                                episodesError =
                                                    e.message ?: "Failed to load episodes"
                                            }
                                            episodesLoadingFor = null
                                        }
                                    }

                                    else -> episodes.forEach { episode ->
                                        VegaButton(text = "▶  ${episode.title}") {
                                            onPlay(episode.title, episode.link, "series")
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                                if (episodesError != null && episodes == null) {
                                    ErrorText(episodesError!!)
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
// Streams: server/quality list for one playable link.
// ---------------------------------------------------------------------------

@Composable
private fun StreamsScreen(
    engine: ProviderEngine,
    provider: ProviderEntry,
    title: String,
    link: String,
    type: String,
    onBack: () -> Unit,
    onStream: (StreamItem) -> Unit
) {
    var streams by remember(link) { mutableStateOf<List<StreamItem>?>(null) }
    var error by remember(link) { mutableStateOf<String?>(null) }

    LaunchedEffect(link) {
        try {
            streams = engine.getStreams(provider, link, type)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load streams"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
            .padding(horizontal = 16.dp)
    ) {
        TopBar(title = title, onBack = onBack)
        Text(
            text = "Choose a server",
            color = VegaSubText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        when {
            error != null -> ErrorText(error!!)
            streams == null -> LoadingBox()
            streams!!.isEmpty() -> ErrorText("No streams found for this title.")
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(streams!!) { stream ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onStream(stream) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = VegaSurface)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "▶",
                                color = Tomato,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stream.server,
                                    color = VegaText,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val sub = listOfNotNull(
                                    stream.quality?.takeIf { it.isNotBlank() }
                                        ?.let { it + "p" },
                                    stream.tag?.takeIf { it.isNotBlank() },
                                    stream.type.takeIf { it.isNotBlank() }
                                        ?.uppercase()
                                ).joinToString(" • ")
                                if (sub.isNotBlank()) {
                                    Text(text = sub, color = VegaSubText, fontSize = 13.sp)
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
// Player: ExoPlayer with the stream's own headers (Referer / User-Agent…).
// This is the stream-engine core: most provider streams refuse to play
// without the exact headers getStream returned, so they are applied to the
// HTTP data source factory verbatim.
// ---------------------------------------------------------------------------

@Composable
private fun PlayerScreen(stream: StreamItem, title: String, onBack: () -> Unit) {
    val context = LocalContext.current

    val player = remember(stream.link) {
        val headers = HashMap<String, String>(stream.headers)
        val userAgent = headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        if (userAgent != null) {
            httpFactory.setUserAgent(userAgent)
        }
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    LaunchedEffect(stream.link) {
        player.setMediaItem(MediaItem.Builder().setUri(stream.link).build())
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(stream.link) {
        onDispose { player.release() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(text = "‹ Back", color = Tomato, fontSize = 16.sp)
            }
            Text(
                text = title,
                color = VegaText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
