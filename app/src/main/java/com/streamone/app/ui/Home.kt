package com.streamone.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamone.app.engine.CatalogItem
import com.streamone.app.engine.Post
import com.streamone.app.engine.ProviderEngine
import com.streamone.app.engine.ProviderEntry
import com.streamone.app.extensions.ExtSection
import com.streamone.app.extensions.ExtensionBridge
import kotlinx.coroutines.delay

private data class HomeRowState(
    val title: String,
    val posts: List<Post>? = null,
    val error: String? = null
)

/**
 * Vega Home: provider selector chips on top, a hero slider of featured posts,
 * then one horizontal poster row per catalog entry of the active provider.
 * When a CloudStream extension is the active source, its homepage sections
 * become the rows instead.
 */
@Composable
fun HomeTab(
    engine: ProviderEngine,
    providers: List<ProviderEntry>?,
    providersError: String?,
    bridges: List<ExtensionBridge>,
    activeSource: ActiveSource?,
    onSelectSource: (ActiveSource) -> Unit,
    onRetryProviders: () -> Unit,
    onOpenPost: (ProviderEntry, Post) -> Unit,
    onOpenExtPost: (ExtensionBridge, Post) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
    ) {
        // Wordmark + provider selector (Vega keeps the active provider one
        // tap away at the top of Home).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Stream",
                color = VegaText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "One",
                color = Tomato,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        SourceChips(
            providers = providers,
            bridges = bridges,
            activeSource = activeSource,
            onSelectSource = onSelectSource
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                providersError != null && providers == null -> ErrorCard(
                    message = providersError,
                    onRetry = onRetryProviders
                )

                providers == null -> Column {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        corner = 0.dp
                    )
                    SkeletonRow()
                    SkeletonRow()
                }

                else -> when (val source = activeSource) {
                    is ActiveSource.Vega -> ProviderHomeBody(
                        engine = engine,
                        provider = source.entry,
                        onOpenPost = { onOpenPost(source.entry, it) }
                    )

                    is ActiveSource.Ext -> ExtSectionsBody(
                        bridge = source.bridge,
                        onPost = { onOpenExtPost(source.bridge, it) }
                    )

                    null -> Text(
                        text = "No providers available. Check your connection.",
                        color = VegaSubText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceChips(
    providers: List<ProviderEntry>?,
    bridges: List<ExtensionBridge>,
    activeSource: ActiveSource?,
    onSelectSource: (ActiveSource) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.padding(bottom = 12.dp)
    ) {
        if (providers != null) {
            items(providers) { provider ->
                val selected = (activeSource as? ActiveSource.Vega)?.entry?.id == provider.id
                SourceChip(
                    label = provider.displayName,
                    iconUrl = provider.icon,
                    selected = selected,
                    onClick = { onSelectSource(ActiveSource.Vega(provider)) }
                )
            }
        }
        items(bridges) { bridge ->
            val selected = (activeSource as? ActiveSource.Ext)?.bridge?.id == bridge.id
            SourceChip(
                label = bridge.displayName,
                iconUrl = "",
                selected = selected,
                onClick = { onSelectSource(ActiveSource.Ext(bridge)) }
            )
        }
    }
}

@Composable
private fun SourceChip(
    label: String,
    iconUrl: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) Tomato else VegaSurface)
            .pressClickable(pressedScale = 0.94f) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconUrl.isNotBlank()) {
            AsyncImage(
                model = iconUrl,
                contentDescription = label,
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = label,
            color = if (selected) Color.White else VegaSubText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// Provider home: hero + one row per catalog entry.
// ---------------------------------------------------------------------------

@Composable
private fun ProviderHomeBody(
    engine: ProviderEngine,
    provider: ProviderEntry,
    onOpenPost: (Post) -> Unit
) {
    var catalogs by remember(provider.id) { mutableStateOf<List<CatalogItem>?>(null) }
    var rows by remember(provider.id) { mutableStateOf<List<HomeRowState>>(emptyList()) }
    var catalogError by remember(provider.id) { mutableStateOf<String?>(null) }
    var retryKey by remember(provider.id) { mutableStateOf(0) }

    LaunchedEffect(provider.id, retryKey) {
        catalogError = null
        rows = emptyList()
        try {
            val cats = engine.getCatalog(provider)
            catalogs = cats
            // Load rows progressively so the hero and first rows appear fast.
            for (catalog in cats) {
                try {
                    val posts = engine.getPosts(provider, catalog.filter, 1)
                    rows = rows + HomeRowState(title = catalog.title, posts = posts)
                } catch (e: Exception) {
                    rows = rows + HomeRowState(
                        title = catalog.title,
                        error = e.message ?: "Failed to load"
                    )
                }
            }
        } catch (e: Exception) {
            catalogError = e.message ?: "Failed to load catalog"
        }
    }

    val heroPosts = rows.firstOrNull { !it.posts.isNullOrEmpty() }
        ?.posts?.take(8) ?: emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (catalogError != null) {
            item {
                ErrorCard(message = catalogError!!, onRetry = { retryKey++ })
            }
        } else if (catalogs == null) {
            item {
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    corner = 0.dp
                )
            }
            item { SkeletonRow() }
            item { SkeletonRow() }
        } else {
            if (heroPosts.isNotEmpty()) {
                item {
                    HeroPager(posts = heroPosts, onOpen = onOpenPost)
                }
            }
            itemsIndexed(rows) { index, row ->
                StaggeredIn(index = index + 1) {
                    Column(modifier = Modifier.padding(top = 18.dp)) {
                    Text(
                        text = row.title,
                        color = VegaText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    when {
                        row.error != null -> Text(
                            text = row.error,
                            color = VegaSubText,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        row.posts == null -> SkeletonRow()
                        row.posts.isEmpty() -> Text(
                            text = "Nothing here yet.",
                            color = VegaSubText,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        else -> LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            items(row.posts ?: emptyList()) { post ->
                                PosterCard(
                                    post = post,
                                    modifier = Modifier.width(112.dp),
                                    onClick = { onOpenPost(post) }
                                )
                            }
                        }
                    }
                    }
                }
            }
            if (rows.isEmpty() && catalogs != null) {
                item { SkeletonRow() }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hero slider (mirrors Vega's 410dp hero: full-bleed backdrop, bottom
// gradient, title + tag, tomato Play button, page dots, auto-advance).
// ---------------------------------------------------------------------------

@Composable
private fun HeroPager(posts: List<Post>, onOpen: (Post) -> Unit) {
    val pagerState = rememberPagerState(initialPage = 0) { posts.size }

    LaunchedEffect(posts.size) {
        while (posts.size > 1) {
            delay(5000)
            if (!pagerState.isScrollInProgress) {
                val next = (pagerState.currentPage + 1) % posts.size
                pagerState.animateScrollToPage(next)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val post = posts[page]
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                // Ken Burns: the current page's artwork slowly zooms in
                // across its dwell time; other pages rest at scale 1.
                val isCurrent = pagerState.currentPage == page
                var zoomArmed by remember(post.link) { mutableStateOf(false) }
                LaunchedEffect(isCurrent) { zoomArmed = isCurrent }
                val zoom by animateFloatAsState(
                    targetValue = if (zoomArmed) 1.08f else 1f,
                    animationSpec = if (zoomArmed) {
                        tween(durationMillis = 5200, easing = LinearEasing)
                    } else {
                        vegaTweenSpec(300)
                    },
                    label = "heroKenBurns"
                )
                AsyncImage(
                    model = post.image,
                    contentDescription = post.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(zoom)
                        .background(VegaSurface)
                )
                // Bottom gradient into black, like Vega's hero.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.55f),
                                    VegaBlack
                                ),
                                startY = 120f
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    val tag = post.tag
                    if (!tag.isNullOrBlank()) {
                        MetaChip(text = tag, accent = true)
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Text(
                        text = post.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VegaButton(text = "▶  Play") { onOpen(post) }
                        GhostButton(text = "Details") { onOpen(post) }
                    }
                }
            }
        }

        // Page dots — the active one stretches into a tomato pill.
        if (posts.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(posts.size) { index ->
                    HeroDot(active = index == pagerState.currentPage)
                }
            }
        }
    }
}

@Composable
private fun HeroDot(active: Boolean) {
    val width by animateDpAsState(
        targetValue = if (active) 18.dp else 6.dp,
        animationSpec = VegaDpSpring,
        label = "heroDot"
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(6.dp)
            .clip(CircleShape)
            .background(
                if (active) Tomato else Color.White.copy(alpha = 0.4f)
            )
    )
}

// ---------------------------------------------------------------------------
// Extension home: the plugin's homepage sections as poster rows.
// ---------------------------------------------------------------------------

@Composable
fun ExtSectionsBody(bridge: ExtensionBridge, onPost: (Post) -> Unit) {
    var sections by remember(bridge.id) { mutableStateOf<List<ExtSection>?>(null) }
    var error by remember(bridge.id) { mutableStateOf<String?>(null) }
    var retryKey by remember(bridge.id) { mutableStateOf(0) }

    LaunchedEffect(bridge.id, retryKey) {
        error = null
        try {
            sections = bridge.homeSections()
        } catch (e: Exception) {
            error = e.message ?: "Failed to load homepage"
        }
    }

    when {
        error != null -> ErrorCard(message = error!!, onRetry = { retryKey++ })
        sections == null -> Column {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                corner = 0.dp
            )
            SkeletonRow()
            SkeletonRow()
        }

        sections!!.isEmpty() -> Text(
            text = "This extension has no homepage. Use Search.",
            color = VegaSubText,
            fontSize = 14.sp,
            modifier = Modifier.padding(16.dp)
        )

        else -> {
            val heroPosts = sections!!.firstOrNull { it.posts.isNotEmpty() }
                ?.posts?.take(8) ?: emptyList()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (heroPosts.isNotEmpty()) {
                    item { HeroPager(posts = heroPosts, onOpen = onPost) }
                }
                itemsIndexed(sections!!) { index, section ->
                    StaggeredIn(index = index + 1) {
                        Column(modifier = Modifier.padding(top = 18.dp)) {
                        Text(
                            text = section.title,
                            color = VegaText,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            items(section.posts) { post ->
                                PosterCard(
                                    post = post,
                                    modifier = Modifier.width(112.dp),
                                    onClick = { onPost(post) }
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
