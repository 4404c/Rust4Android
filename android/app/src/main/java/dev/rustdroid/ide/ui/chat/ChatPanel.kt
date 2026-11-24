package dev.rustdroid.ide.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rustdroid.ide.R
import dev.rustdroid.ide.ai.AiSettings
import dev.rustdroid.ide.ui.components.RdIcons
import dev.rustdroid.ide.ui.theme.LocalEditorPalette

/** R4A: pestaña Chat del panel de abajo (fase 1: solo conversa). */
@Composable
fun ChatPanel(
    vm: ChatViewModel,
    onInputFocusChanged: (Boolean) -> Unit,
) {
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val aiState by AiSettings.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()

    // seguir el final mientras llega texto
    val lastLen = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastLen) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
    }

    Column(Modifier.fillMaxSize()) {
        // cabecera: modelo + limpiar
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // tocar el nombre → elegir otra API key guardada
            val active = aiState.active
            var pickerOpen by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                Text(
                    if (active?.ready == true) "${active.name} · ${active.model}  ▾"
                    else stringResource(R.string.ai_chat_not_ready),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(enabled = !busy && aiState.connections.size > 1) { pickerOpen = true }
                        .padding(vertical = 6.dp),
                )
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    aiState.connections.forEach { c ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(c.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${c.provider.label} · ${c.model.ifBlank { stringResource(R.string.ai_settings_model_none) }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingIcon = { RadioButton(selected = c.id == active?.id, onClick = null) },
                            onClick = {
                                AiSettings.setActive(context, c.id)
                                pickerOpen = false
                            },
                        )
                    }
                }
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = { vm.clear() }, enabled = messages.isNotEmpty(), modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.ai_chat_clear),
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Text(
                    stringResource(R.string.ai_chat_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(messages) { _, m -> MessageBubble(m) }
                    }
                }
            }
        }

        ChatInput(
            busy = busy,
            onSend = { vm.send(it) },
            onStop = { vm.stop() },
            onFocusChanged = onInputFocusChanged,
        )
    }
}

@Composable
private fun MessageBubble(m: ChatViewModel.ChatMessage) {
    val palette = LocalEditorPalette.current
    // R4A fase 2: lo que hizo la IA con el proyecto, como una línea chiquita
    if (m.role == ChatViewModel.Role.TOOL) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("›", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                m.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val isUser = m.role == ChatViewModel.Role.USER
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(
            Modifier
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = 520.dp)
                .background(
                    when (m.role) {
                        ChatViewModel.Role.USER -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        ChatViewModel.Role.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ChatViewModel.Role.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        ChatViewModel.Role.TOOL -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val color = if (m.role == ChatViewModel.Role.ERROR) MaterialTheme.colorScheme.error else palette.stdout
            // ```bloques``` en monoespaciado; el resto como texto normal
            splitCodeBlocks(m.text.ifEmpty { "…" }).forEach { (isCode, part) ->
                if (isCode) {
                    Text(
                        part,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = palette.stdout,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.consoleBg, RoundedCornerShape(6.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                } else if (part.isNotBlank()) {
                    Text(part.trim('\n'), style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
        }
    }
}

/** Parte el texto en (esCódigo, texto) según los ``` de markdown. */
private fun splitCodeBlocks(text: String): List<Pair<Boolean, String>> {
    val parts = text.split("```")
    return parts.mapIndexed { i, part ->
        if (i % 2 == 1) {
            // primera línea = lenguaje (```rust); si el bloque sigue abierto, igual se muestra
            true to part.substringAfter('\n', part).trimEnd('\n')
        } else {
            false to part
        }
    }
}

@Composable
private fun ChatInput(
    busy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val palette = LocalEditorPalette.current
    var input by rememberSaveable { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { onFocusChanged(false) } }

    fun submit() {
        if (input.isNotBlank() && !busy) {
            onSend(input)
            input = ""
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
            if (input.isEmpty()) {
                Text(
                    stringResource(R.string.ai_chat_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.stdout),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onFocusChanged(it.isFocused) },
            )
        }
        Spacer(Modifier.width(8.dp))
        if (busy) {
            IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                Icon(
                    RdIcons.Stop,
                    contentDescription = stringResource(R.string.ai_chat_stop),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            IconButton(onClick = { submit() }, enabled = input.isNotBlank(), modifier = Modifier.size(32.dp)) {
                Icon(
                    RdIcons.Send,
                    contentDescription = stringResource(R.string.ai_chat_send),
                    tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
