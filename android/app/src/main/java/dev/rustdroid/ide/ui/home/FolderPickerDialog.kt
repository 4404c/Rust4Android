package dev.rustdroid.ide.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.R
import dev.rustdroid.ide.projects.FolderBrowser
import dev.rustdroid.ide.ui.components.RdIcons
import java.io.File

/**
 * R4A: R4A's own folder picker for "open folder as project". Android's
 * system picker refuses some folders ("to protect your privacy, choose
 * another folder") on several phones; this one lists shared storage with
 * the plain File API (R4A has the storage permission at targetSdk 28).
 * Tap a folder to go in, "↑" to go up, then "Use this folder". (The
 * system picker is not offered at all: on this kind of phone it refused
 * ordinary folders like Scripts/<project>.)
 */
@Composable
fun FolderPickerDialog(
    root: File,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    // R4A: almost full screen — the folder list is what matters here
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    var current by remember { mutableStateOf(root) }
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(current, reload) {
        folders = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FolderBrowser.folders(current) }
    }
    val rootLabel = stringResource(R.string.folders_storage)

    if (creating) {
        NewFolderDialog(
            parent = current,
            onCreated = { created -> creating = false; current = created },
            onDismiss = { creating = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.folders_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // where we are: Almacenamiento › Scripts › Juego
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    FolderBrowser.crumbs(root, current).forEachIndexed { i, f ->
                        if (i > 0) Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (i == 0) rootLabel else f.name,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = if (f == current) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { current = f },
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().height((screenH * 0.55f).dp)) {
                    if (FolderBrowser.canGoUp(root, current)) {
                        item {
                            FolderRow(name = "↑  " + stringResource(R.string.folders_up), icon = false) {
                                current.parentFile?.let { current = it }
                            }
                        }
                    }
                    items(folders, key = { it.path }) { f ->
                        FolderRow(name = f.name, icon = true) { current = f }
                    }
                    if (folders.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.folders_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                HorizontalDivider()
                TextButton(onClick = { creating = true }) { Text(stringResource(R.string.folders_new)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text(stringResource(R.string.folders_use_this)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun FolderRow(name: String, icon: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(RdIcons.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NewFolderDialog(parent: File, onCreated: (File) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                singleLine = true,
                label = { Text(stringResource(R.string.common_name)) },
                isError = error != null,
                supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    runCatching { FolderBrowser.createFolder(parent, name) }
                        .onSuccess(onCreated)
                        .onFailure { error = it.message }
                },
            ) { Text(stringResource(R.string.common_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
