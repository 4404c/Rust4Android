package dev.rustdroid.ide.ui.gate

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import dev.rustdroid.ide.R
import androidx.compose.ui.res.stringResource
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ToolchainState
import dev.rustdroid.ide.toolchain.ToolchainDistro
import dev.rustdroid.ide.toolchain.ToolchainInstallService
import dev.rustdroid.ide.ui.components.CheckRow
import dev.rustdroid.ide.ui.components.RdIcons
import dev.rustdroid.ide.util.Fs

@Composable
fun GateScreen(
    container: AppContainer,
    onReady: () -> Unit,
) {
    val manager = container.toolchainManager
    val state by manager.state.collectAsStateWithLifecycle()

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless: FGS works without visible notification */ }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            manager.launchImport(uri)
        }
    }

    // Scaffold paints the theme background (the window behind it can be the
    // XML theme's gray) and insets the content below the status bar, so the
    // hero sits comfortably in the middle of the screen instead of the very
    // top edge.
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Centered when content is short; scrolls when it grows
            // (progress states + log tail on small screens).
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                RdIcons.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "R4A",
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = dev.rustdroid.ide.ui.theme.PixelFamily),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.gate_tagline, ToolchainDistro.RUST_VERSION),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    is ToolchainState.NotInstalled -> InstallPrompt(
                        onDownload = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            ToolchainInstallService.start(container.context)
                        },
                        onImport = {
                            zipPicker.launch(
                                arrayOf("application/zip", "application/x-zip-compressed")
                            )
                        },
                        // Rescue path: files from an install whose VERIFICATION
                        // failed (e.g. a false-negative check) sit unverified on
                        // disk — the rollback of a first install has nothing to
                        // restore, so it leaves them in place without a ready
                        // marker. Re-running the health check costs no download;
                        // if it passes, this prefix is promoted to Ready.
                        hasExistingFiles = manager.paths.isInstalled(),
                        onReverifyExisting = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            ToolchainInstallService.startReverify(container.context)
                        },
                    )

                    is ToolchainState.Downloading -> DownloadCard(state as ToolchainState.Downloading)

                    is ToolchainState.Extracting -> ExtractCard(state as ToolchainState.Extracting)

                    is ToolchainState.Verifying -> VerifyCard(state as ToolchainState.Verifying)

                    is ToolchainState.Failed -> FailedCard(
                        state as ToolchainState.Failed,
                        onRetry = { ToolchainInstallService.start(container.context) },
                        onImport = { zipPicker.launch(arrayOf("application/zip")) },
                    )

                    is ToolchainState.Ready -> ReadyCard()
                }

                // Navigation is driven from AppRoot; the card is the visual
                // confirmation between the state flip and the screen change.
                if (state is ToolchainState.Ready) {
                    LaunchedEffect(Unit) { onReady() }
                }

                LogTail(manager)
            }
        }
    }
}

@Composable
private fun ReadyCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        )
    ) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.gate_verified_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.gate_verified_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun InstallPrompt(
    onDownload: () -> Unit,
    onImport: () -> Unit,
    hasExistingFiles: Boolean = false,
    onReverifyExisting: () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.gate_first_run),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.gate_download_body, Fs.humanBytes(ToolchainDistro.expectedSizeBytes)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (ToolchainDistro.isPinned) {
                Text(
                    stringResource(R.string.gate_integrity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.gate_download_btn, Fs.humanBytes(ToolchainDistro.expectedSizeBytes))) }
            if (hasExistingFiles) {
                OutlinedButton(
                    onClick = onReverifyExisting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.gate_verify_existing)) }
                Text(
                    stringResource(R.string.gate_verify_existing_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.gate_import_zip)) }
            Text(
                stringResource(R.string.gate_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadCard(state: ToolchainState.Downloading) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.gate_downloading),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val knownTotal = state.total != null && state.total > 0
            val pct = if (knownTotal) {
                (state.bytes * 100f / state.total!!).coerceIn(0f, 100f)
            } else null
            if (pct != null) {
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${pct.toInt()}% — ${Fs.humanBytes(state.bytes)} of ${Fs.humanBytes(state.total!!)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "${Fs.humanBytes(state.bytes)} downloaded",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                stringResource(R.string.gate_download_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExtractCard(state: ToolchainState.Extracting) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.gate_extracting),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
            val entries = state.done
            Text(
                if (entries > 0) stringResource(R.string.gate_entries_placed, entries) else stringResource(R.string.gate_starting),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.gate_extract_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VerifyCard(state: ToolchainState.Verifying) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(vertical = 12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.gate_verifying),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                stringResource(R.string.gate_verifying_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            state.checks.forEach { CheckRow(it) }
        }
    }
}

@Composable
private fun FailedCard(state: ToolchainState.Failed, onRetry: () -> Unit, onImport: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.gate_failed_at, state.stage.toString()),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text(stringResource(R.string.gate_retry)) }
                OutlinedButton(onClick = onImport) { Text(stringResource(R.string.gate_import_short)) }
            }
        }
    }
}

@Composable
private fun LogTail(manager: dev.rustdroid.ide.toolchain.ToolchainManager) {
    // immutable snapshot — safe to read from the UI thread while install
    // threads keep appending (a live ArrayDeque would CME under iteration)
    val log = manager.logTailSnapshot()
    if (log.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.gate_log),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (line in log.takeLast(8)) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
    }
}
