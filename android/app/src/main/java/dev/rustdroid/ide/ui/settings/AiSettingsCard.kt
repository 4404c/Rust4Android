package dev.rustdroid.ide.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.R
import dev.rustdroid.ide.ai.AiClient
import dev.rustdroid.ide.ai.AiSettings
import dev.rustdroid.ide.di.AppContainer
import kotlinx.coroutines.launch

/**
 * R4A: Opciones → Inteligencia artificial. Proveedor, API key (se guarda
 * cifrada) y modelo, que se elige de la lista que devuelve el proveedor.
 */
@Composable
fun AiSettingsCard(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by AiSettings.config.collectAsState()
    val client = remember { AiClient(container.http) }

    var keyInput by remember { mutableStateOf("") }
    var urlInput by remember(config.provider) { mutableStateOf(config.customUrl) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>?>(null) }

    fun loadModels() {
        val key = AiSettings.apiKey(context) ?: return
        loading = true
        error = null
        scope.launch {
            try {
                models = client.listModels(AiSettings.config.value.baseUrl, key)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ai_settings_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ai_settings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- proveedor ----
            Text(stringResource(R.string.ai_settings_provider), style = MaterialTheme.typography.labelLarge)
            AiSettings.Provider.entries.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = p == config.provider,
                            role = Role.RadioButton,
                            onClick = {
                                AiSettings.setProvider(context, p)
                                keyInput = ""
                                error = null
                                models = null
                            },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = p == config.provider, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (p == AiSettings.Provider.CUSTOM) stringResource(R.string.ai_provider_custom) else p.label,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (config.provider == AiSettings.Provider.CUSTOM) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.ai_settings_url)) },
                    placeholder = { Text("http://192.168.0.10:11434/v1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (urlInput.trim() != config.customUrl) {
                    OutlinedButton(onClick = { AiSettings.setCustomUrl(context, urlInput) }) {
                        Text(stringResource(R.string.ai_settings_save_url))
                    }
                }
            }

            // ---- API key ----
            Text(stringResource(R.string.ai_settings_key), style = MaterialTheme.typography.labelLarge)
            if (config.hasKey) {
                Text(
                    stringResource(R.string.ai_settings_key_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(onClick = {
                    AiSettings.setApiKey(context, "")
                    models = null
                }) { Text(stringResource(R.string.ai_settings_key_remove)) }
            } else {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(stringResource(R.string.ai_settings_key_paste)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            AiSettings.setApiKey(context, keyInput)
                            keyInput = ""
                            loadModels()
                        },
                        enabled = keyInput.isNotBlank(),
                    ) { Text(stringResource(R.string.ai_settings_key_save)) }
                    config.provider.keyUrl?.let { url ->
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text(stringResource(R.string.ai_settings_key_get)) }
                    }
                }
            }

            // ---- modelo ----
            Text(stringResource(R.string.ai_settings_model), style = MaterialTheme.typography.labelLarge)
            Text(
                config.model.ifBlank { stringResource(R.string.ai_settings_model_none) },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { loadModels() },
                    enabled = config.hasKey && config.baseUrl.isNotBlank() && !loading,
                ) { Text(stringResource(R.string.ai_settings_model_pick)) }
                if (loading) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    models?.let { list ->
        ModelPickerDialog(
            models = list,
            current = config.model,
            onPick = {
                AiSettings.setModel(context, it)
                models = null
            },
            onDismiss = { models = null },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(models, filter) {
        if (filter.isBlank()) models else models.filter { it.contains(filter.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_settings_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                .selectable(selected = id == current, role = Role.RadioButton, onClick = { onPick(id) })
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = id == current, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(id, style = MaterialTheme.typography.bodySmall)
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
