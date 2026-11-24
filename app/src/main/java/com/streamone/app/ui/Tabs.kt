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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamone.app.engine.Post
import com.streamone.app.engine.ProviderEngine
import com.streamone.app.engine.ProviderEntry
import com.streamone.app.extensions.ExtensionBridge
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Search tab: prominent rounded field, poster grid results, searches the
// ACTIVE source (Vega provider via getSearchPosts with paging, or the
// CloudStream extension's search).
// ---------------------------------------------------------------------------

@Composable
fun SearchTab(
    engine: ProviderEngine,
    activeSource: ActiveSource?,
    onOpenPost: (ProviderEntry, Post) -> Unit,
    onOpenExtPost: (ExtensionBridge, Post) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Post>>(emptyList()) }
    var searchedQuery by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    val scope = rememberCoroutineScope()

    val source = activeSource

    fun runSearch(reset: Boolean) {
        val q = query.trim()
        if (q.isEmpty() || source == null) return
        val pageToLoad = if (reset) 1 else page + 1
        if (reset) {
            loading = true
            error = null
        } else {
            loadingMore = true
        }
        scope.launch {
            try {
                val found = when (source) {
                    is ActiveSource.Vega -> engine.getSearchPosts(source.entry, q, pageToLoad)
                    is ActiveSource.Ext -> {
                        // Extension search has no paging; page 1 only.
                        if (pageToLoad == 1) source.bridge.search(q) else emptyList()
                    }
                }
                results = if (reset) found else results + found
                if (found.isNotEmpty()) page = pageToLoad
                searchedQuery = q
            } catch (e: Exception) {
                if (reset) error = e.message ?: "Search failed"
            }
            loading = false
            loadingMore = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Search",
            color = VegaText,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text(
                    text = if (source != null) {
                        "Search ${source.displayName}…"
                    } else {
                        "Search…"
                    },
                    color = VegaSubText
                )
            },
            leadingIcon = {
                VegaIcon(name = VegaIconName.Search, tint = VegaSubText, size = 20.dp)
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = VegaSurface,
                unfocusedContainerColor = VegaSurface,
                focusedBorderColor = Tomato,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Tomato,
                focusedTextColor = VegaText,
                unfocusedTextColor = VegaText
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch(reset = true) }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            VegaButton(text = "Search") { runSearch(reset = true) }
            if (source != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "in ${source.displayName}",
                    color = VegaSubText,
                    fontSize = 13.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        when {
            source == null -> Text(
                text = "No source selected yet.",
                color = VegaSubText,
                fontSize = 14.sp
            )

            error != null -> ErrorCard(message = error!!, onRetry = { runSearch(reset = true) })
            loading -> LoadingBox()
            searchedQuery == null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VegaIcon(name = VegaIconName.Search, tint = VegaSurfaceHigh, size = 64.dp)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Search movies & shows",
                    color = VegaSubText,
                    fontSize = 15.sp
                )
            }

            results.isEmpty() -> Text(
                text = "No results for \"$searchedQuery\"",
                color = VegaSubText,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp)
            )

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(results) { post ->
                        PosterCard(post = post) {
                            when (source) {
                                is ActiveSource.Vega -> onOpenPost(source.entry, post)
                                is ActiveSource.Ext -> onOpenExtPost(source.bridge, post)
                            }
                        }
                    }
                }
                if (source is ActiveSource.Vega) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (loadingMore) {
                            LoadingBox()
                        } else {
                            VegaButton(text = "Load more") { runSearch(reset = false) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Watchlist tab: bookmarked items persisted by WatchlistStore.
// ---------------------------------------------------------------------------

@Composable
fun WatchlistTab(
    watchlist: WatchlistStore,
    onOpen: (WatchlistItem) -> Unit
) {
    val savedItems = watchlist.items
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            VegaIcon(name = VegaIconName.Bookmark, tint = Tomato, size = 26.dp, filled = true)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Watchlist",
                color = VegaText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            if (savedItems.isNotEmpty()) {
                Spacer(modifier = Modifier.width(10.dp))
                MetaChip(text = "${savedItems.size}")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (savedItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VegaIcon(name = VegaIconName.Bookmark, tint = VegaSurfaceHigh, size = 64.dp)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Nothing saved yet",
                    color = VegaText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tap the bookmark icon on any title to save it here.",
                    color = VegaSubText,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(savedItems, key = { it.sourceId + "|" + it.link }) { item ->
                    Box {
                        PosterCard(post = item.toPost()) { onOpen(item) }
                        // Remove affordance.
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .clickable { watchlist.remove(item.sourceId, item.link) },
                            contentAlignment = Alignment.Center
                        ) {
                            VegaIcon(name = VegaIconName.Close, tint = Color.White, size = 14.dp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Downloads tab: files present in the app downloads folder. Tap plays the
// local file, the trash icon deletes it.
// ---------------------------------------------------------------------------

@Composable
fun DownloadsTab(
    context: android.content.Context,
    onPlayLocal: (DownloadedFile) -> Unit
) {
    var downloadedFiles by remember { mutableStateOf<List<DownloadedFile>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        downloadedFiles = DownloadsHelper.listDownloads(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            VegaIcon(name = VegaIconName.Download, tint = Tomato, size = 26.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Downloads",
                color = VegaText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Refresh",
                color = Tomato,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { refreshKey++ }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Downloads run through Android's download manager — progress " +
                "shows in the notification shade. Finished files appear here.",
            color = VegaSubText,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (downloadedFiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VegaIcon(name = VegaIconName.Download, tint = VegaSurfaceHigh, size = 64.dp)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "No downloads yet",
                    color = VegaText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Use the download icon next to a stream to save it here.",
                    color = VegaSubText,
                    fontSize = 13.sp
                )
            }
        } else {
            val dateFormat = remember {
                SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(downloadedFiles, key = { it.path }) { file ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayLocal(file) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = VegaSurface)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(VegaSurfaceHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                VegaIcon(name = VegaIconName.File, tint = Tomato, size = 22.dp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    color = VegaText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatFileSize(file.size) + " • " +
                                        dateFormat.format(Date(file.lastModified)),
                                    color = VegaSubText,
                                    fontSize = 12.sp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        DownloadsHelper.delete(file)
                                        refreshKey++
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                VegaIcon(
                                    name = VegaIconName.Delete,
                                    tint = VegaSubText,
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

// ---------------------------------------------------------------------------
// Settings tab: Vega-style list (dark cards, leading icon, title, subtitle,
// chevron) with working entries only.
// ---------------------------------------------------------------------------

@Composable
fun SettingsTab(
    onProviders: () -> Unit,
    onExtensions: () -> Unit,
    onAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Settings",
            color = VegaText,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "CONTENT",
            color = VegaSubText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = VegaSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                SettingsRow(
                    icon = VegaIconName.Grid,
                    title = "Providers",
                    subtitle = "Choose the active provider or extension source",
                    onClick = onProviders
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(1.dp)
                        .background(VegaBorder)
                )
                SettingsRow(
                    icon = VegaIconName.Puzzle,
                    title = "CloudStream Extensions",
                    subtitle = "Add repositories and install .cs3 extensions",
                    onClick = onExtensions
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = "APP",
            color = VegaSubText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = VegaSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsRow(
                icon = VegaIconName.Info,
                title = "About",
                subtitle = "StreamOne • version 1.0.1",
                onClick = onAbout
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: VegaIconName,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VegaSurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            VegaIcon(name = icon, tint = Tomato, size = 22.dp)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = VegaText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(text = subtitle, color = VegaSubText, fontSize = 13.sp)
        }
        VegaIcon(name = VegaIconName.ChevronRight, tint = VegaSubText, size = 20.dp)
    }
}

// ---------------------------------------------------------------------------
// Providers screen (Settings → Providers): every Vega provider and every
// loaded CloudStream extension; tapping one makes it the active source.
// ---------------------------------------------------------------------------

@Composable
fun ProvidersScreen(
    providers: List<ProviderEntry>?,
    providersError: String?,
    bridges: List<ExtensionBridge>,
    activeSource: ActiveSource?,
    onRetryProviders: () -> Unit,
    onSelect: (ActiveSource) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
    ) {
        SubHeader(title = "Providers", onBack = onBack)
        when {
            providersError != null && providers == null -> ErrorCard(
                message = providersError,
                onRetry = onRetryProviders
            )

            providers == null -> LoadingBox()
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                item {
                    Text(
                        text = "VEGA PROVIDERS",
                        color = Tomato,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                items(providers) { provider ->
                    val selected =
                        (activeSource as? ActiveSource.Vega)?.entry?.id == provider.id
                    ProviderRow(
                        name = provider.displayName,
                        subtitle = if (provider.source == "udai") {
                            "Your providers"
                        } else {
                            "Zenda-Cross"
                        } + if (provider.version.isNotBlank()) {
                            " • v${provider.version}"
                        } else {
                            ""
                        },
                        iconUrl = provider.icon,
                        selected = selected,
                        onClick = { onSelect(ActiveSource.Vega(provider)) }
                    )
                }
                if (bridges.isNotEmpty()) {
                    item {
                        Text(
                            text = "CLOUDSTREAM EXTENSIONS",
                            color = Tomato,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                        )
                    }
                    items(bridges) { bridge ->
                        val selected =
                            (activeSource as? ActiveSource.Ext)?.bridge?.id == bridge.id
                        ProviderRow(
                            name = bridge.displayName,
                            subtitle = "CloudStream extension • ${bridge.language}",
                            iconUrl = "",
                            selected = selected,
                            onClick = { onSelect(ActiveSource.Ext(bridge)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    name: String,
    subtitle: String,
    iconUrl: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) VegaSurfaceHigh else VegaSurface
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconUrl.isNotBlank()) {
                AsyncImage(
                    model = iconUrl,
                    contentDescription = name,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = VegaText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = subtitle, color = VegaSubText, fontSize = 13.sp)
            }
            if (selected) {
                VegaIcon(name = VegaIconName.Check, tint = Tomato, size = 22.dp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// About screen.
// ---------------------------------------------------------------------------

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
    ) {
        SubHeader(title = "About", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(VegaSurface),
                contentAlignment = Alignment.Center
            ) {
                VegaIcon(name = VegaIconName.Play, tint = Tomato, size = 40.dp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "StreamOne",
                color = VegaText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            MetaChip(text = "Version 1.0.1", accent = true)
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = VegaSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "All your providers. One app.",
                        color = VegaText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Interface styled after the Vega app. Vega providers " +
                            "run in a WebView sandbox engine with real axios/cheerio; " +
                            "CloudStream extensions run through the .cs3 plugin engine.",
                        color = VegaSubText,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
