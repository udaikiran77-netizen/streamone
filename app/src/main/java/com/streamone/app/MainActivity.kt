package com.streamone.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.ui.draw.clip
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

    fun pop() {
        if (stack.isNotEmpty()) stack = stack.dropLast(1)
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
            stateHolder.SaveableStateProvider(tab.name) {
                when (tab) {
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

            // Full-screen destinations. All of them stay composed so an
            // Info screen keeps its loaded state under the player.
            for (dest in stack) {
                key(dest) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(VegaBlack)
                    ) {
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
    val tint = if (selected) Tomato else VegaSubText
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelect(tab) }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VegaIcon(name = icon, tint = tint, size = 24.dp, filled = selected)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
