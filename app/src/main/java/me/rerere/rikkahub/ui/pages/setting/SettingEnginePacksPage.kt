package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.llamacpp.EnginePackRegistry
import me.rerere.rikkahub.data.enginepack.EnginePackReleases

/**
 * US-007: Engine Packs pane. Lists every pack on disk with its verification verdict (accepted
 * packs by id/version/backend; declined dirs by named reason - a silent absence is not an
 * option here), and installs from the pinned GitHub release through the resumable pipeline.
 *
 * Strings are hardcoded English for the internal track; localize before master merge, where
 * lint actually runs.
 */
@Composable
fun SettingEnginePacksPage() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var scan by remember {
        mutableStateOf(emptyList<EnginePackRegistry.EnginePack>() to emptyList<EnginePackRegistry.PackDecline>())
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            EnginePackRegistry.appRoot()?.let { scan = EnginePackRegistry.scan(it) }
        }
    }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Engine Packs") },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Signed native engine packs. Packs are verified (Ed25519 + sha256) before " +
                        "any library is loaded; a pack that fails verification is declined by " +
                        "name and the built-in engine keeps running.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            status = "Downloading " + EnginePackReleases.PACK_RELEASE_TAG + " ..."
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    EnginePackRegistry.appRoot()?.let {
                                        EnginePackReleases.installFromPinnedRelease(it)
                                    }
                                }
                                status = when (outcome) {
                                    is me.rerere.llamacpp.EnginePackDownloader.Outcome.Installed ->
                                        "Installed " + outcome.pack.id + " v" + outcome.pack.version
                                    is me.rerere.llamacpp.EnginePackDownloader.Outcome.RefusedDowngrade ->
                                        "Refused downgrade: installed v" + outcome.installedVersion +
                                            " >= candidate v" + outcome.candidateVersion
                                    is me.rerere.llamacpp.EnginePackDownloader.Outcome.Failed ->
                                        "Failed at " + outcome.stage + ": " + outcome.detail
                                    null -> "No storage root available"
                                }
                                withContext(Dispatchers.IO) {
                                    EnginePackRegistry.appRoot()?.let { scan = EnginePackRegistry.scan(it) }
                                }
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "Installing..." else "Install from release") }
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                EnginePackRegistry.appRoot()?.let { scan = EnginePackRegistry.scan(it) }
                            }
                        }
                    }) { Text("Rescan") }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            status?.let { s ->
                item {
                    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(s, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            scan.first.forEach { p ->
                item("ok-" + p.id + p.version) {
                    ListItem(
                        headlineContent = { Text(p.id) },
                        supportingContent = {
                            Text("v" + p.version + "  " + p.abi + "  backends=" +
                                p.backends.joinToString(",") { it.name.lowercase() } +
                                "  pin=" + p.enginePin.take(10))
                        },
                    )
                }
            }
            scan.second.forEach { d ->
                item("no-" + d.dir.name) {
                    ListItem(
                        headlineContent = { Text(d.dir.name) },
                        supportingContent = {
                            Text("declined: " + d.reason.name.lowercase() +
                                if (d.detail.isNotEmpty()) " - " + d.detail else "")
                        },
                    )
                }
            }
            if (scan.first.isEmpty()) {
                item {
                    Text(
                        "No verified packs installed - the built-in engine is in use.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item { Text("Release tag: " + EnginePackReleases.PACK_RELEASE_TAG,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }
    }
}
