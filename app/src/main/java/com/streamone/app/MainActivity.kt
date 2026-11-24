package com.streamone.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.streamone.app.engine.Post
import com.streamone.app.engine.ProviderEngine
import com.streamone.app.engine.ProviderEntry
import com.streamone.app.engine.ProviderRepository
import com.streamone.app.engine.StreamItem
import com.streamone.app.extensions.ExtensionBridge
import com.streamone.app.extensions.PluginEngine
import com.streamone.app.ui.AboutScreen
import com.streamone.app.ui.ActiveSource
import com.streamone.app.ui.DownloadsTab
import com.streamone.app.ui.ExtensionsScreen
import com.streamone.app.ui.ExtInfoScreen
import com.streamone.app.ui.HomeTab
import com.streamone.app.ui.InfoScreen
import com.streamone.app.ui.PlayerArgs
import com.streamone.app.ui.PlayerScreen
import com.streamone.app.ui.ProvidersScreen
import com.streamone.app.ui.SearchTab
import com.streamone.app.ui.SettingsTab
import com.streamone.app.ui.StreamOneTheme
import com.streamone.app.ui.Tomato
import com.streamone.app.ui.UpdateChecker
import com.streamone.app.ui.UpdateDialog
import com.streamone.app.ui.UpdateInfo
import com.streamone.app.ui.VegaBlack
import com.streamone.app.ui.VegaBorder
import com.streamone.app.ui.VegaDpSpring
import com.streamone.app.ui.VegaSelectionSpring
import com.streamone.app.ui.vegaTweenSpec
import com.streamone.app.ui.VegaIcon
import com.streamone.app.ui.VegaIconName
import com.streamone.app.ui.VegaSubText
import com.streamone.app.ui.WatchlistItem
import com.streamone.app.ui.WatchlistStore
import com.streamone.app.ui.WatchlistTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class Tab { Home, Search, Watchlist, Downloads, Settings }

/** Full-screen destinations stacked over the tab shell. */
private sealed interface Dest {
    data class Info(val provider: ProviderEntry, val post: Post) : Dest
    data class ExtInfo(val bridge: ExtensionBridge, val post: Post) : Dest
    data class Player(val args: PlayerArgs) : Dest
    data object Extensions : Dest
    data object Providers : Dest
    data object About : Dest
}

class MainActivity : ComponentActivity() {

    private lateinit var repository: ProviderRepository
    private lateinit var engine: ProviderEngine
    private lateinit var pluginEngine: PluginEngine
    private lateinit var watchlist: WatchlistStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ProviderRepository(applicationContext)
        engine = ProviderEngine(applicationContext, repository)
        pluginEngine = PluginEngine(applicationContext)
        watchlist = WatchlistStore(applicationContext)
        // The vendored CloudStream library resolves the app context through
        // this helper (WebView-based extractors need it).
        com.lagradost.api.setContext(applicationContext)
        // Load installed CloudStream extensions in the background.
        lifecycleScope.launch {
            pluginEngine.loadAllInstalled()
        }
        setContent {
            StreamOneTheme {
                StreamOneApp(engine, pluginEngine, watchlist)
            }
        }
    }
}

@Composable
private fun StreamOneApp(
    engine: ProviderEngine,
    pluginEngine: PluginEngine,
    watchlist: WatchlistStore
) {
    val context = LocalContext.current

    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var stack by remember { mutableStateOf<List<Dest>>(emptyList()) }

    // Provider manifests + loaded extension bridges, hoisted so Home,
    // Search, Watchlist and Settings → Providers all share one copy.
    var providers by remember { mutableStateOf<List<ProviderEntry>?>(null) }
    var providersError by remember { mutableStateOf<String?>(null) }
    var providersRetry by remember { mutableStateOf(0) }
    var bridges by remember { mutableStateOf<List<ExtensionBridge>>(emptyList()) }

    var activeVegaId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeExtId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(providersRetry) {
        try {
            providers = engine.fetchManifests()
            providersError = null
        } catch (e: Exception) {
            providersError = e.message ?: "Could not load providers"
        }
    }

    // Plugins load in the background at startup; poll briefly so extension
    // sources appear once they are ready, and refresh whenever the user
    // returns from a full-screen destination (installs/uninstalls happen
    // on the Extensions screen).
    LaunchedEffect(stack.isEmpty()) {
        repeat(12) {
            bridges = pluginEngine.bridges()
            if (bridges.isNotEmpty()) return@LaunchedEffect
            delay(600)
        }
        bridges = pluginEngine.bridges()
    }

    // In-app updates: once per launch, when the auto-check preference is
    // on, silently ask GitHub for the latest release and surface the
    // update dialog when one is newer than the installed version. The
    // manual check lives in Settings (see ui/Tabs.kt) and shares the
    // dialog. Failures are silent here by design.
    var startupUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        if (UpdateChecker.isAutoUpdateEnabled(context)) {
            UpdateChecker.checkForUpdate(context).onSuccess { info ->
                if (info != null) startupUpdate = info
            }
        }
    }

    val activeSource: ActiveSource? = remember(providers, bridges, activeVegaId, activeExtId) {
        val providerList = providers
        val ext = bridges.firstOrNull { it.id == activeExtId }
        when {
            ext != null -> ActiveSource.Ext(ext)
            providerList != null -> {
                val entry = providerList.firstOrNull { it.id == activeVegaId }
                    ?: providerList.firstOrNull()
                entry?.let { ActiveSource.Vega(it) }
            }

            else -> null
        }
    }

    fun selectSource(source: ActiveSource) {
        when (source) {
            is ActiveSource.Vega -> {
                activeVegaId = source.entry.id
                activeExtId = null
            }

            is ActiveSource.Ext -> {
                activeExtId = source.bridge.id
            }
        }
    }

    fun push(dest: Dest) {
        stack = stack + dest
    }

    // Popping is deferred: the top destination first plays its exit
    // animation (it stays composed, marked as exiting), then it is
    // actually removed from the stack.
    var exiting by remember { mutableStateOf<Dest?>(null) }

    fun pop() {
        if (exiting != null) return
        exiting = stack.lastOrNull()
    }

    LaunchedEffect(exiting) {
        val leaving = exiting ?: return@LaunchedEffect
        delay(240)
        stack = stack - leaving
        exiting = null
    }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    val stateHolder = rememberSaveableStateHolder()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Tab content; per-tab saveable state survives tab switches.
            // Switching tabs crossfades the content, Vega-style.
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    fadeIn(animationSpec = vegaTweenSpec(280)) togetherWith
                        fadeOut(animationSpec = vegaTweenSpec(180))
                },
                label = "tabContent",
                modifier = Modifier.fillMaxSize()
            ) { targetTab ->
                stateHolder.SaveableStateProvider(targetTab.name) {
                    when (targetTab) {
                    Tab.Home -> HomeTab(
                        engine = engine,
                        providers = providers,
                        providersError = providersError,
                        bridges = bridges,
                        activeSource = activeSource,
                        onSelectSource = { selectSource(it) },
                        onRetryProviders = { providersRetry++ },
                        onOpenPost = { provider, post -> push(Dest.Info(provider, post)) },
                        onOpenExtPost = { bridge, post -> push(Dest.ExtInfo(bridge, post)) }
                    )

                    Tab.Search -> SearchTab(
                        engine = engine,
                        activeSource = activeSource,
                        onOpenPost = { provider, post -> push(Dest.Info(provider, post)) },
                        onOpenExtPost = { bridge, post -> push(Dest.ExtInfo(bridge, post)) }
                    )

                    Tab.Watchlist -> WatchlistTab(
                        watchlist = watchlist,
                        onOpen = { item ->
                            when (item.kind) {
                                "extension" -> bridges
                                    .firstOrNull { it.id == item.sourceId }
                                    ?.let { push(Dest.ExtInfo(it, item.toPost())) }

                                else -> providers
                                    ?.firstOrNull { it.id == item.sourceId }
                                    ?.let { push(Dest.Info(it, item.toPost())) }
                            }
                        }
                    )

                    Tab.Downloads -> DownloadsTab(
                        context = context,
                        onPlayLocal = { file ->
                            val uri = Uri.fromFile(File(file.path)).toString()
                            push(
                                Dest.Player(
                                    PlayerArgs(
                                        title = file.name,
                                        subtitle = "Downloads",
                                        streams = listOf(
                                            StreamItem(
                                                server = "Local file",
                                                link = uri,
                                                type = "file"
                                            )
                                        )
                                    )
                                )
                            )
                        }
                    )

                    Tab.Settings -> SettingsTab(
                        onProviders = { push(Dest.Providers) },
                        onExtensions = { push(Dest.Extensions) },
                        onAbout = { push(Dest.About) }
                    )
                    }
                }
            }

            // Full-screen destinations. All of them stay composed so an
            // Info screen keeps its loaded state under the player. Each
            // one rises + fades in on entry and reverses on exit.
            for (dest in stack) {
                key(dest) {
                    AnimatedDestHost(visible = dest != exiting) {
                        when (dest) {
                            is Dest.Info -> InfoScreen(
                                engine = engine,
                                provider = dest.provider,
                                post = dest.post,
                                watchlist = watchlist,
                                onBack = { pop() },
                                onPlay = { push(Dest.Player(it)) }
                            )

                            is Dest.ExtInfo -> ExtInfoScreen(
                                bridge = dest.bridge,
                                post = dest.post,
                                watchlist = watchlist,
                                onBack = { pop() },
                                onPlay = { push(Dest.Player(it)) }
                            )

                            is Dest.Player -> PlayerScreen(
                                args = dest.args,
                                onBack = { pop() }
                            )

                            is Dest.Extensions -> ExtensionsScreen(
                                pluginEngine = pluginEngine,
                                onBack = { pop() }
                            )

                            is Dest.Providers -> ProvidersScreen(
                                providers = providers,
                                providersError = providersError,
                                bridges = bridges,
                                activeSource = activeSource,
                                onRetryProviders = { providersRetry++ },
                                onSelect = { source ->
                                    selectSource(source)
                                    stack = emptyList()
                                    tab = Tab.Home
                                },
                                onBack = { pop() }
                            )

                            is Dest.About -> AboutScreen(onBack = { pop() })
                        }
                    }
                }
            }
        }

        if (stack.isEmpty()) {
            TabBar(current = tab, onSelect = { tab = it })
        }
    }

    startupUpdate?.let { info ->
        UpdateDialog(info = info, onDismiss = { startupUpdate = null })
    }
}

/**
 * Host for one full-screen destination: rises slightly and fades in when it
 * appears, reverses when [visible] flips to false (the pop is deferred in
 * StreamOneApp until the exit animation has played).
 */
@Composable
private fun AnimatedDestHost(visible: Boolean, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown && visible,
        enter = fadeIn(animationSpec = vegaTweenSpec(320)) +
            slideInVertically(animationSpec = vegaTweenSpec(350)) { it / 12 },
        exit = fadeOut(animationSpec = vegaTweenSpec(220)) +
            slideOutVertically(animationSpec = vegaTweenSpec(240)) { it / 12 }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VegaBlack)
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom tab bar in Vega's StreamingTabBar style: black bar, subtle top
// border, tomato icon + label for the active tab, grey for the rest.
// ---------------------------------------------------------------------------

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VegaBlack)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(VegaBorder)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            TabButton(
                tab = Tab.Home,
                icon = VegaIconName.Home,
                label = "Home",
                current = current,
                onSelect = onSelect
            )
            TabButton(
                tab = Tab.Search,
                icon = VegaIconName.Search,
                label = "Search",
                current = current,
                onSelect = onSelect
            )
            TabButton(
                tab = Tab.Watchlist,
                icon = VegaIconName.Bookmark,
                label = "Watchlist",
                current = current,
                onSelect = onSelect
            )
            TabButton(
                tab = Tab.Downloads,
                icon = VegaIconName.Download,
                label = "Downloads",
                current = current,
                onSelect = onSelect
            )
            TabButton(
                tab = Tab.Settings,
                icon = VegaIconName.Settings,
                label = "Settings",
                current = current,
                onSelect = onSelect
            )
        }
    }
}

@Composable
private fun RowScope.TabButton(
    tab: Tab,
    icon: VegaIconName,
    label: String,
    current: Tab,
    onSelect: (Tab) -> Unit
) {
    val selected = tab == current
    // Selection progress drives the whole button: icon crossfade + scale,
    // pill indicator width and the label tint all ride the same spring,
    // mirroring Vega's AnimatedTabIcon (withTiming draw-in on selection).
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = VegaSelectionSpring,
        label = "tabProgress"
    )
    val pillWidth by animateDpAsState(
        targetValue = if (selected) 20.dp else 0.dp,
        animationSpec = VegaDpSpring,
        label = "tabPill"
    )
    val tint = lerp(VegaSubText, Tomato, progress)
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelect(tab) }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Tomato pill indicator in a fixed-height slot (no layout jump).
        Box(
            modifier = Modifier.height(7.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .width(pillWidth)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Tomato)
                    .alpha(progress)
            )
        }
        Box(
            modifier = Modifier.scale(0.9f + 0.14f * progress),
            contentAlignment = Alignment.Center
        ) {
            VegaIcon(
                name = icon,
                tint = tint,
                size = 24.dp,
                filled = false,
                modifier = Modifier.alpha(1f - progress)
            )
            VegaIcon(
                name = icon,
                tint = tint,
                size = 24.dp,
                filled = true,
                modifier = Modifier.alpha(progress)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.alpha(0.6f + 0.4f * progress)
        )
    }
}
