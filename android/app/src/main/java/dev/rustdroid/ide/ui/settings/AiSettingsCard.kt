package dev.rustdroid.ide.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.R
import dev.rustdroid.ide.ai.AiClient
import dev.rustdroid.ide.ai.AiSettings
import dev.rustdroid.ide.di.AppContainer
import kotlinx.coroutines.launch

/**
 * R4A: Opciones → Inteligencia artificial. Lista de API keys guardadas
 * (cada una con nombre, proveedor y modelo); la marcada es la que usa el
 * Chat. Se pueden agregar más, renombrarlas, cambiarles el modelo y borrarlas.
 */
@Composable
fun AiSettingsCard(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AiSettings.state.collectAsState()
    val client = remember { AiClient(container.http) }
    val claude = remember { dev.rustdroid.ide.ai.AnthropicChat() }
    val chatgpt = remember { dev.rustdroid.ide.ai.ChatGptClient(container.http) }
    val chatgptAuth = remember { dev.rustdroid.ide.ai.ChatGptAuth(container.http) }
    // inicio de sesión con ChatGPT en curso (el diálogo de espera) y su error
    var signInJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var signInError by remember { mutableStateOf<String?>(null) }

    fun openUrl(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    var adding by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AiSettings.Connection?>(null) }
    var deleting by remember { mutableStateOf<AiSettings.Connection?>(null) }
    // modelos: para qué conexión, la lista (null = cargando) y el error
    var modelsFor by remember { mutableStateOf<AiSettings.Connection?>(null) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var modelsError by remember { mutableStateOf<String?>(null) }

    fun openModels(c: AiSettings.Connection) {
        modelsFor = c
        models = null
        modelsError = null
        scope.launch {
            try {
                models = dev.rustdroid.ide.ai.ModelCatalog.list(context, container.http, c)
            } catch (e: Exception) {
                modelsError = e.message ?: e.toString()
            }
        }
    }

    ExpandableCard(stringResource(R.string.ai_settings_title)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.ai_settings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.connections.isEmpty()) {
                Text(stringResource(R.string.ai_settings_no_keys), style = MaterialTheme.typography.bodySmall)
            }
            state.connections.forEach { c ->
                ConnectionRow(
                    c = c,
                    active = c.id == state.active?.id,
                    onSelect = { AiSettings.setActive(context, c.id) },
                    onModels = { openModels(c) },
                    onRename = { renaming = c },
                    onDelete = { deleting = c },
                    onManageUsage = { openUrl(dev.rustdroid.ide.ai.ChatGptAuth.USAGE_URL) },
                )
            }
            OutlinedButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ai_settings_add_key))
            }
            // regla universal: va en el prompt de todos los modelos
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.ai_rule_title), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.ai_rule_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { editingRule = true }) { Text(stringResource(R.string.ai_rule_edit)) }
        }
    }

    if (editingRule) {
        val current by AiSettings.rule.collectAsState()
        var text by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editingRule = false },
            title = { Text(stringResource(R.string.ai_rule_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = { AiSettings.setRule(context, text); editingRule = false }) {
                    Text(stringResource(R.string.ai_settings_key_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { text = AiSettings.DEFAULT_RULE }) { Text(stringResource(R.string.ai_rule_reset)) }
                    TextButton(onClick = { editingRule = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        )
    }

    if (adding) {
        AddKeyDialog(
            onSave = { name, provider, url, key ->
                adding = false
                val c = AiSettings.add(context, name, provider, url, key)
                openModels(c) // elegir el modelo enseguida
            },
            // ChatGPT: en vez de una clave, se inicia sesión en el navegador
            onChatGpt = { name ->
                adding = false
                signInError = null
                signInJob = scope.launch {
                    try {
                        val tokens = chatgptAuth.signIn(context) { uri -> openUrl(uri.toString()) }
                        val c = AiSettings.add(context, name, AiSettings.Provider.CHATGPT, "", tokens)
                        openModels(c)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        signInError = e.message ?: e.toString()
                    } finally {
                        signInJob = null
                    }
                }
            },
            onDismiss = { adding = false },
        )
    }

    renaming?.let { c ->
        RenameDialog(
            current = c.name,
            onSave = { AiSettings.rename(context, c.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.ai_settings_delete_title)) },
            text = { Text(stringResource(R.string.ai_settings_delete_text, c.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        // ChatGPT: avisar a OpenAI que se cierra la sesión
                        if (c.provider == AiSettings.Provider.CHATGPT) chatgptAuth.signOut(context, c.id)
                        AiSettings.remove(context, c.id)
                    }
                }) {
                    Text(stringResource(R.string.ai_settings_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (signInJob != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.ai_chatgpt_waiting_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.ai_chatgpt_waiting_text), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { signInJob?.cancel() }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    signInError?.let { err ->
        AlertDialog(
            onDismissRequest = { signInError = null },
            title = { Text(stringResource(R.string.ai_chatgpt_error_title)) },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { signInError = null }) { Text("OK") } },
        )
    }

    modelsFor?.let { c ->
        ModelPickerDialog(
            connection = c,
            models = models,
            error = modelsError,
            onPick = {
                AiSettings.setModel(context, c.id, it)
                AiSettings.setActive(context, c.id)
                modelsFor = null
            },
            onDismiss = { modelsFor = null },
        )
    }
}

@Composable
private fun ConnectionRow(
    c: AiSettings.Connection,
    active: Boolean,
    onSelect: () -> Unit,
    onModels: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onManageUsage: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = active, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = active, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (c.provider == AiSettings.Provider.CHATGPT) stringResource(R.string.ai_chatgpt_using_plan) else c.provider.label) +
                    " · ${c.model.ifBlank { stringResource(R.string.ai_settings_model_none) }}",
                style = MaterialTheme.typography.bodySmall,
                color = if (c.model.isBlank()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.editor_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_settings_model_pick)) },
                    onClick = { menu = false; onModels() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_settings_rename)) },
                    onClick = { menu = false; onRename() },
                )
                if (c.provider == AiSettings.Provider.CHATGPT) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_chatgpt_manage_usage)) },
                        onClick = { menu = false; onManageUsage() },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (c.provider == AiSettings.Provider.CHATGPT) R.string.ai_chatgpt_sign_out
                                else R.string.ai_settings_delete_confirm,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun AddKeyDialog(
    onSave: (name: String, provider: AiSettings.Provider, url: String, key: String) -> Unit,
    onChatGpt: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var provider by remember { mutableStateOf(AiSettings.Provider.GROQ) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val isChatGpt = provider == AiSettings.Provider.CHATGPT
    val canSave = isChatGpt || key.isNotBlank() && (provider != AiSettings.Provider.CUSTOM || url.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_settings_add_key)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ai_settings_key_name)) },
                    placeholder = { Text(stringResource(R.string.ai_settings_key_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.ai_settings_provider), style = MaterialTheme.typography.labelLarge)
                AiSettings.Provider.entries.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = p == provider, role = Role.RadioButton, onClick = { provider = p })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = p == provider, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (p) {
                                AiSettings.Provider.CUSTOM -> stringResource(R.string.ai_provider_custom)
                                AiSettings.Provider.CHATGPT -> stringResource(R.string.ai_provider_chatgpt)
                                else -> p.label
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (provider == AiSettings.Provider.CUSTOM) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.ai_settings_url)) },
                        placeholder = { Text("http://192.168.0.10:11434/v1") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (isChatGpt) {
                    Text(
                        stringResource(R.string.ai_chatgpt_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.ai_settings_key_paste)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                provider.keyUrl?.let { link ->
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text(stringResource(R.string.ai_settings_key_get)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (isChatGpt) onChatGpt(name) else onSave(name, provider, url, key) },
                enabled = canSave,
            ) {
                Text(stringResource(if (isChatGpt) R.string.ai_chatgpt_continue else R.string.ai_settings_key_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_settings_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.ai_settings_key_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.ai_settings_key_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun ModelPickerDialog(
    connection: AiSettings.Connection,
    models: List<String>?,
    error: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(models, filter) {
        val list = models.orEmpty()
        if (filter.isBlank()) list else list.filter { it.contains(filter.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.ai_settings_models_of, connection.name))
                Text(
                    connection.provider.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    models == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.ai_settings_models_loading), style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            placeholder = { Text(stringResource(R.string.ai_settings_model_filter)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (shown.isEmpty()) {
                            Text(
                                stringResource(R.string.ai_settings_model_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(shown) { id ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(selected = id == connection.model, role = Role.RadioButton, onClick = { onPick(id) })
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = id == connection.model, onClick = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(id, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
