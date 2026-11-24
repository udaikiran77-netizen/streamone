package com.streamone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.plugins.Repository
import com.streamone.app.extensions.InstalledPlugin
import com.streamone.app.extensions.PluginEngine
import com.streamone.app.extensions.RepoPlugin
import kotlinx.coroutines.launch

/**
 * CloudStream Extensions management (Settings → CloudStream Extensions):
 * add a repository by URL (cloudstreamrepo:// links are normalised by the
 * engine), browse its extensions, install / update / uninstall .cs3 plugins.
 * Logic is unchanged from the previous UI — only re-homed and restyled.
 */
@Composable
fun ExtensionsScreen(
    pluginEngine: PluginEngine,
    onBack: () -> Unit
) {
    var repoUrlText by remember { mutableStateOf("") }
    var fetchedRepo by remember { mutableStateOf<Repository?>(null) }
    var fetchedRepoUrl by remember { mutableStateOf("") }
    var repoPlugins by remember { mutableStateOf<List<RepoPlugin>>(emptyList()) }
    var storedRepos by remember { mutableStateOf(pluginEngine.storedRepos()) }
    var installed by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        installed = pluginEngine.installedPlugins()
        storedRepos = pluginEngine.storedRepos()
    }

    fun isInstalled(plugin: RepoPlugin): InstalledPlugin? =
        installed.firstOrNull {
            it.internalName == plugin.plugin.internalName && it.repositoryUrl == plugin.repo.url
        }

    fun loadRepository(url: String) {
        busy = true
        status = null
        scope.launch {
            try {
                val repo = pluginEngine.addRepository(url)
                fetchedRepo = repo
                fetchedRepoUrl = url
                repoPlugins = pluginEngine.fetchRepoPlugins(url, repo)
                status = "Loaded ${repoPlugins.size} extensions from ${repo.name}."
                refreshKey++
            } catch (e: Exception) {
                status = "Failed to load repository: ${e.message}"
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack)
    ) {
        SubHeader(title = "CloudStream Extensions", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "Add a CloudStream repository URL, then install extensions " +
                    "(.cs3) from it. cloudstreamrepo:// links work too.",
                color = VegaSubText,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = repoUrlText,
                    onValueChange = { repoUrlText = it },
                    label = { Text("Repository URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = VegaSurface,
                        unfocusedContainerColor = VegaSurface,
                        focusedBorderColor = Tomato,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Tomato,
                        focusedTextColor = VegaText,
                        unfocusedTextColor = VegaText,
                        focusedLabelColor = Tomato,
                        unfocusedLabelColor = VegaSubText
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                VegaButton(text = "Add") {
                    val normalized = pluginEngine.normalizeRepoUrl(repoUrlText)
                    if (normalized == null) {
                        status = "That doesn't look like a repository URL."
                    } else {
                        loadRepository(normalized)
                    }
                }
            }
            if (busy) LoadingBox()
            if (status != null) {
                Text(
                    text = status!!,
                    color = VegaSubText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (storedRepos.isNotEmpty() && fetchedRepo == null) {
                item {
                    Text(
                        text = "YOUR REPOSITORIES",
                        color = Tomato,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(storedRepos) { stored ->
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = VegaSurface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stored.name,
                                    color = VegaText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stored.url,
                                    color = VegaSubText,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { loadRepository(stored.url) }) {
                                Text(text = "Open", color = Tomato, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            if (installed.isNotEmpty()) {
                item {
                    Text(
                        text = "INSTALLED",
                        color = Tomato,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(installed) { plugin ->
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = VegaSurface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plugin.name,
                                    color = VegaText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "v${plugin.version}" +
                                        (plugin.language?.let { " • $it" } ?: ""),
                                    color = VegaSubText,
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(onClick = {
                                busy = true
                                scope.launch {
                                    pluginEngine.uninstall(plugin)
                                    refreshKey++
                                    busy = false
                                }
                            }) {
                                Text(text = "Uninstall", color = Tomato, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            if (fetchedRepo != null) {
                item {
                    Text(
                        text = fetchedRepo!!.name.uppercase(),
                        color = Tomato,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(repoPlugins) { repoPlugin ->
                    val existing = isInstalled(repoPlugin)
                    val plugin = repoPlugin.plugin
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = VegaSurface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plugin.name,
                                    color = VegaText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val sub = listOfNotNull(
                                    "v${plugin.version}",
                                    plugin.language,
                                    plugin.authors.takeIf { it.isNotEmpty() }
                                        ?.joinToString(", ")
                                ).joinToString(" • ")
                                Text(text = sub, color = VegaSubText, fontSize = 12.sp)
                                if (!plugin.description.isNullOrBlank()) {
                                    Text(
                                        text = plugin.description,
                                        color = VegaSubText,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (existing != null && existing.version >= plugin.version) {
                                Text(
                                    text = "Installed",
                                    color = VegaSubText,
                                    fontSize = 13.sp
                                )
                            } else {
                                VegaButton(
                                    text = if (existing != null) "Update" else "Install"
                                ) {
                                    busy = true
                                    status = null
                                    scope.launch {
                                        try {
                                            pluginEngine.install(repoPlugin)
                                            status = "Installed ${plugin.name}."
                                            refreshKey++
                                        } catch (e: Exception) {
                                            status =
                                                "Install failed for ${plugin.name}: ${e.message}"
                                        }
                                        busy = false
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
