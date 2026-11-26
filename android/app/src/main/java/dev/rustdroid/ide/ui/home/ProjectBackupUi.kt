package dev.rustdroid.ide.ui.home

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.R
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.projects.ProjectBackup
import dev.rustdroid.ide.projects.ProjectLeftovers
import dev.rustdroid.ide.util.Fs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * R4A: «Copia de seguridad…» de un proyecto. Arma un .zip (ver
 * [ProjectBackup]) y lo guarda con el «Guardar como…» de Android
 * (`CreateDocument`): no hace falta permiso de almacenamiento, anda en el
 * modo seguro. Devuelve la función que abre el diálogo para un proyecto.
 */
@Composable
fun rememberProjectBackup(): (dir: File, name: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<Pair<File, String>?>(null) }
    var withChats by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<Triple<File, String, Boolean>?>(null) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var done by remember { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val job = pending
        pending = null
        if (uri == null || job == null) return@rememberLauncherForActivityResult
        val (dir, name, chats) = job
        progress = 0L to 1L
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val chatsDir = if (chats) chatsDirOf(context, dir) else null
                    val total = ProjectBackup.sizeToBackUp(dir, chatsDir).coerceAtLeast(1)
                    progress = 0L to total
                    val out = context.contentResolver.openOutputStream(uri, "w") ?: error("no output")
                    val counting = CountingStream(out)
                    counting.use { ProjectBackup.write(dir, name, chatsDir, it) { n -> progress = n to total } }
                    counting.count
                }.onFailure {
                    // no dejar un .zip roto donde el usuario lo guardó
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
            }
            progress = null
            done = result.fold(
                onSuccess = { bytes ->
                    context.getString(R.string.backup_done_text, displayName(context, uri) ?: ProjectBackup.suggestedFileName(name), Fs.humanBytes(bytes))
                },
                onFailure = { context.getString(R.string.backup_failed, it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    target?.let { (dir, name) ->
        val hasChats = remember(dir) { chatsDirOf(context, dir).list()?.isNotEmpty() == true }
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(stringResource(R.string.backup_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_text))
                    if (hasChats) {
                        CheckRow(stringResource(R.string.backup_with_chats), withChats) { withChats = it }
                    } else {
                        Text(stringResource(R.string.backup_no_chats), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    target = null
                    pending = Triple(dir, name, hasChats && withChats)
                    runCatching { save.launch(ProjectBackup.suggestedFileName(name)) }
                        .onFailure { pending = null; done = context.getString(R.string.backup_failed, it.message ?: "") }
                }) { Text(stringResource(R.string.backup_save)) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    progress?.let { (n, total) -> ProgressDialog(stringResource(R.string.backup_saving), n.toFloat() / total) }
    done?.let { msg ->
        AlertDialog(
            onDismissRequest = { done = null },
            title = { Text(stringResource(R.string.backup_done_title)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { done = null }) { Text(stringResource(R.string.common_dismiss)) } },
        )
    }
    return { dir, name -> withChats = true; target = dir to name }
}

/**
 * R4A: «Restaurar copia (.zip)». Elegís el .zip con el selector de Android
 * (`OpenDocument`, sin permiso), se lee su nombre y se pide uno libre:
 * nunca se pisa un proyecto. [onRestored] recibe el ref del proyecto nuevo.
 */
@Composable
fun rememberProjectRestore(container: AppContainer, onRestored: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = container.projectRepository
    var picked by remember { mutableStateOf<Pair<Uri, ProjectBackup.Info>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val readingMsg = stringResource(R.string.restore_reading)
    val restoringMsg = stringResource(R.string.restore_restoring)

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = readingMsg
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { ProjectBackup.readInfo(it) } }
            }
            busy = null
            info.fold(
                onSuccess = { picked = uri to it },
                onFailure = { onError(context.getString(R.string.restore_failed, it.message ?: "")) },
            )
        }
    }

    picked?.let { (uri, info) ->
        var name by remember(uri) { mutableStateOf(repo.freeProjectName(info.name)) }
        var chats by remember(uri) { mutableStateOf(info.hasChats) }
        val nameOk = name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))
        val taken = nameOk && repo.projectNameTaken(name)
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(stringResource(R.string.restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.restore_text))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.common_name)) },
                        isError = !nameOk || taken,
                        supportingText = {
                            when {
                                !nameOk -> Text(stringResource(R.string.common_name_rule))
                                taken -> Text(stringResource(R.string.restore_taken))
                            }
                        },
                    )
                    if (info.hasChats) CheckRow(stringResource(R.string.restore_with_chats), chats) { chats = it }
                }
            },
            confirmButton = {
                TextButton(enabled = nameOk && !taken, onClick = {
                    picked = null
                    busy = restoringMsg
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val dir = context.contentResolver.openInputStream(uri)!!.use {
                                    repo.restoreBackup(it, name, chats) { d -> chatsDirOf(context, d) }
                                }
                                val canonical = ProjectLeftovers.canonical(dir)
                                // cachés viejos de un proyecto borrado con el mismo nombre: se rearman
                                ProjectLeftovers.caches(context.filesDir, canonical).forEach { Fs.deleteRecursively(it) }
                                withContext(Dispatchers.Main) { container.chatStore.forget(canonical) }
                                repo.refOf(dir)
                            }
                        }
                        busy = null
                        result.fold(
                            onSuccess = onRestored,
                            onFailure = { onError(context.getString(R.string.restore_failed, it.message ?: "")) },
                        )
                    }
                }) { Text(stringResource(R.string.restore_confirm)) }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    busy?.let { ProgressDialog(it, null) }
    return {
        runCatching { open.launch(arrayOf("application/zip", "application/x-zip-compressed")) }
            .onFailure { onError(context.getString(R.string.restore_failed, it.message ?: "")) }
    }
}

private fun chatsDirOf(context: Context, projectDir: File): File =
    ProjectLeftovers.chatsDir(context.filesDir, ProjectLeftovers.canonical(projectDir))

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
private fun ProgressDialog(text: String, fraction: Float?) {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text)
                if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
    )
}

/** Cuenta los bytes que llegan al archivo (el tamaño final del .zip). */
private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
}
