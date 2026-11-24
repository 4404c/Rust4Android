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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.border
import dev.rustdroid.ide.ai.ChatSession
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
import androidx.compose.ui.graphics.graphicsLayer
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

/** R4A: el Chat IA (va en el cajón de la derecha del editor). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    chats: dev.rustdroid.ide.ai.ProjectChats,
    onInputFocusChanged: (Boolean) -> Unit,
    onOpenFile: (String) -> Unit = {},
    onOpenProject: (String) -> Unit = {},
    emptyText: String? = null,
) {
    // la charla actual del proyecto (cambia con "Nueva charla" o la lista)
    val vm by chats.current.collectAsState()
    val chatList by chats.list.collectAsState()
    val allMessages by vm.messages.collectAsState()
    val hiddenBefore by vm.hiddenBefore.collectAsState()
    // lo de visitas anteriores queda oculto hasta deslizar hacia abajo
    val messages = remember(allMessages, hiddenBefore) { allMessages.drop(hiddenBefore) }
    val busy by vm.busy.collectAsState()
    val aiState by AiSettings.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()

    // charla anterior: cargando (ruedita) y dónde quedó la separación
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loadingOlder by remember(vm) { mutableStateOf(false) }
    var dividerAt by remember(vm) { mutableStateOf<Int?>(null) }

    // seguir el final mientras llega texto
    val lastLen = messages.lastOrNull()?.text?.length ?: 0
    // (no depende de hiddenBefore: al revelar lo anterior la vista no salta al final)
    LaunchedEffect(allMessages.size, lastLen) {
        if (messages.isEmpty()) return@LaunchedEffect
        val extra = (if (hiddenBefore > 0) 1 else 0) + (if (dividerAt != null) 1 else 0)
        listState.scrollToItem(messages.lastIndex + extra, Int.MAX_VALUE)
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
            // charlas de este proyecto: retomar o borrar
            var listOpen by remember { mutableStateOf(false) }
            var toDelete by remember { mutableStateOf<dev.rustdroid.ide.ai.ProjectChats.Summary?>(null) }
            Box {
                IconButton(onClick = { listOpen = true }, enabled = chatList.isNotEmpty(), modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.ai_chat_list),
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = listOpen, onDismissRequest = { listOpen = false }) {
                    val fmt = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()) }
                    chatList.forEach { c ->
                        DropdownMenuItem(
                            text = {
                                Column(Modifier.widthIn(max = 240.dp)) {
                                    Text(
                                        c.title.ifBlank { "…" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        stringResource(R.string.ai_chat_list_item, fmt.format(java.util.Date(c.updatedAt)), c.messages),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingIcon = { RadioButton(selected = c.id == vm.id, onClick = null) },
                            trailingIcon = {
                                IconButton(onClick = { listOpen = false; toDelete = c }, enabled = !busy) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.ai_chat_delete), modifier = Modifier.size(18.dp))
                                }
                            },
                            enabled = !busy,
                            onClick = { listOpen = false; chats.open(c.id) },
                        )
                    }
                }
            }
            toDelete?.let { c ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { toDelete = null },
                    title = { Text(stringResource(R.string.ai_chat_delete_title)) },
                    text = { Text(stringResource(R.string.ai_chat_delete_text, c.title.ifBlank { "…" })) },
                    confirmButton = {
                        TextButton(onClick = { chats.delete(c.id); toDelete = null }) {
                            Text(stringResource(R.string.ai_chat_delete), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
            // nueva charla: la actual queda en la lista y la IA arranca de cero
            IconButton(onClick = { chats.newChat() }, enabled = !busy && allMessages.isNotEmpty(), modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.ai_chat_new),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // deslizar hacia abajo (estando arriba de todo) muestra la charla anterior:
        // aparece un círculo con una flecha que sigue al dedo, al soltar gira
        // una ruedita y los mensajes viejos entran por arriba con un fundido
        val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        val pulledEnough = pullState.distanceFraction >= 1f
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = loadingOlder,
            onRefresh = {
                if (hiddenBefore > 0 && !loadingOlder) scope.launch {
                    loadingOlder = true
                    kotlinx.coroutines.delay(450)
                    val h = hiddenBefore
                    dividerAt = h
                    vm.revealOlder()
                    loadingOlder = false
                    // queda la separación arriba y se asoma un poco de lo anterior
                    androidx.compose.runtime.withFrameNanos { }
                    listState.scrollToItem(h)
                    listState.animateScrollBy(-260f)
                }
            },
            state = pullState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            indicator = {
                if (hiddenBefore > 0 || loadingOlder) {
                    val frac = if (loadingOlder) 1f else pullState.distanceFraction.coerceIn(0f, 1.4f)
                    val arrow by androidx.compose.animation.core.animateFloatAsState(
                        if (pulledEnough) 180f else 0f, label = "arrow",
                    )
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .size(36.dp)
                            .graphicsLayer {
                                translationY = (frac * 56.dp.toPx()) - 44.dp.toPx()
                                alpha = frac.coerceAtMost(1f)
                                scaleX = 0.6f + 0.4f * frac.coerceAtMost(1f)
                                scaleY = scaleX
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (loadingOlder) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.graphicsLayer { rotationZ = arrow },
                                )
                            }
                        }
                    }
                }
            },
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    // la lista baja con el dedo y deja lugar al círculo de arriba
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val f = if (loadingOlder) 1f
                        else if (hiddenBefore > 0) pullState.distanceFraction.coerceIn(0f, 1.4f) else 0f
                        translationY = f * 56.dp.toPx()
                    },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (hiddenBefore > 0) {
                        item(key = "hint") {
                            Text(
                                if (pulledEnough) stringResource(R.string.ai_chat_release_older)
                                else stringResource(R.string.ai_chat_pull_older, hiddenBefore),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (pulledEnough) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        alpha = 0.6f + 0.4f * pullState.distanceFraction.coerceIn(0f, 1f)
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                    if (messages.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                emptyText ?: stringResource(R.string.ai_chat_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    // la clave es la posición en TODA la charla: así los mensajes
                    // viejos que aparecen son "nuevos" para la lista y entran con fundido
                    messages.forEachIndexed { i, m ->
                        val abs = hiddenBefore + i
                        if (dividerAt != null && abs == dividerAt) {
                            item(key = "divider") { OlderDivider(Modifier.animateItem()) }
                        }
                        item(key = abs) {
                            Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(350))) {
                                if (m.role == ChatSession.Role.CHANGE) {
                                    ChangeCard(
                                        m,
                                        busy = busy,
                                        onOpen = { m.path?.let(onOpenFile) },
                                        onUndo = { m.changeId?.let { vm.undo(it) } },
                                        onOpenProject = { m.projectRef?.let(onOpenProject) },
                                    )
                                } else {
                                    MessageBubble(m)
                                }
                            }
                        }
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
private fun MessageBubble(m: ChatSession.ChatMessage) {
    val palette = LocalEditorPalette.current
    // R4A fase 2: lo que hizo la IA con el proyecto, como una línea chiquita
    if (m.role == ChatSession.Role.TOOL) {
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
    val isUser = m.role == ChatSession.Role.USER
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(
            Modifier
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = 520.dp)
                .background(
                    when (m.role) {
                        ChatSession.Role.USER -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        ChatSession.Role.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ChatSession.Role.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        ChatSession.Role.TOOL, ChatSession.Role.CHANGE -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val color = if (m.role == ChatSession.Role.ERROR) MaterialTheme.colorScheme.error else palette.stdout
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

/** R4A: separa lo que se habló antes (arriba) de esta visita (abajo). */
@Composable
private fun OlderDivider(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val line = MaterialTheme.colorScheme.outlineVariant
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text(
            stringResource(R.string.ai_chat_divider_now),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

/**
 * R4A fase 3: un cambio de la IA. "Abrir" lleva al archivo en el editor;
 * "Deshacer" lo vuelve atrás. Un proyecto nuevo trae "Abrir proyecto".
 */
@Composable
private fun ChangeCard(
    m: ChatSession.ChatMessage,
    busy: Boolean,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
    onOpenProject: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (m.undone) 0.15f else 0.45f), RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (m.undone) "↶ " + m.text else m.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (m.undone) MaterialTheme.colorScheme.onSurfaceVariant else palette.stdout,
            textDecoration = if (m.undone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        if (m.projectRef != null) {
            TextButton(onClick = onOpenProject, enabled = !busy) { Text(stringResource(R.string.ai_change_open_project)) }
        }
        if (m.path != null && !m.undone) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.ai_change_open)) }
        }
        if (m.changeId != null && !m.undone) {
            TextButton(onClick = onUndo, enabled = !busy) { Text(stringResource(R.string.ai_change_undo)) }
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
