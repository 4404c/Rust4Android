package dev.rustdroid.ide.ui.chat

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.clip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
    /** Abrir un archivo en el editor; [line] = primera línea que cambió (para ir ahí). */
    onOpenFile: (String, Int?) -> Unit = { _, _ -> },
    onOpenProject: (String) -> Unit = {},
    emptyText: String? = null,
    /** Chat de Inicio: sin modo Plan/Build. */
    home: Boolean = false,
) {
    // la charla actual del proyecto (cambia con "Nueva charla" o la lista)
    val vm by chats.current.collectAsStateWithLifecycle()
    val chatList by chats.list.collectAsStateWithLifecycle()
    val allMessages by vm.messages.collectAsStateWithLifecycle()
    val hiddenBefore by vm.hiddenBefore.collectAsStateWithLifecycle()
    // lo de visitas anteriores queda oculto hasta deslizar hacia abajo
    val messages = remember(allMessages, hiddenBefore) { allMessages.drop(hiddenBefore) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val canRetry by vm.canRetry.collectAsStateWithLifecycle()
    val aiState by AiSettings.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()

    // charla anterior: cargando (ruedita) y dónde quedó la separación
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loadingOlder by remember(vm) { mutableStateOf(false) }
    var dividerAt by remember(vm) { mutableStateOf<Int?>(null) }

    // los mensajes viejos entran con fundido SOLO un momento después de revelarlos
    // (antes cada mensaje nuevo entraba con fundido: eso era el parpadeo)
    var revealAnim by remember(vm) { mutableStateOf(false) }

    // R4A: el diff de cada tarjeta se calcula una sola vez por charla (al volver
    // a la vista con el scroll ya está, y la tarjeta no cambia de alto de golpe)
    val diffCache = remember(vm) { java.util.concurrent.ConcurrentHashMap<Long, LineDiff.Result>() }

    // seguir el final mientras llega texto, salvo que el usuario haya subido a leer
    var follow by remember(vm) { mutableStateOf(true) }
    // R4A: escuchado con snapshotFlow (leerlo acá rearmaba el panel al empezar y terminar cada scroll)
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    val lastLen = messages.lastOrNull()?.text?.length ?: 0
    // (no depende de hiddenBefore: al revelar lo anterior la vista se queda en lo último)
    LaunchedEffect(allMessages.size, lastLen, busy) {
        if (messages.isEmpty() || !follow) return@LaunchedEffect
        val count = (if (hiddenBefore > 0) 1 else 0) + messages.size +
            (if (dividerAt != null && dividerAt!! < allMessages.size) 1 else 0) +
            (if (busy && messages.lastOrNull()?.role != ChatSession.Role.ASSISTANT) 1 else 0)
        listState.scrollToItem(count - 1, Int.MAX_VALUE)
    }

    Column(Modifier.fillMaxSize()) {
        // cabecera: modelo + limpiar
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // tocar el nombre → elegir clave/proveedor, modelo y esfuerzo para ESTA charla
            val active = aiState.active
            var pickerOpen by remember { mutableStateOf(false) }
            var modelsFor by remember { mutableStateOf<AiSettings.Connection?>(null) }
            var models by remember { mutableStateOf<List<String>?>(null) }
            var modelsError by remember { mutableStateOf<String?>(null) }
            val effort = active?.let { AiSettings.effectiveEffort(it) }.orEmpty()
            val effortLabel = effort.takeIf { it.isNotEmpty() }?.let { " · ${effortName(it)}" }.orEmpty()
            Box(Modifier.weight(1f)) {
                Text(
                    if (active?.ready == true) "${active.name} · ${active.model}$effortLabel  ▾"
                    else stringResource(R.string.ai_chat_not_ready),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(enabled = !busy && aiState.connections.isNotEmpty()) { pickerOpen = true }
                        .padding(vertical = 6.dp),
                )
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    Text(
                        stringResource(R.string.ai_chat_pick_provider),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
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
                    if (active != null) {
                        androidx.compose.material3.HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_chat_change_model)) },
                            onClick = {
                                pickerOpen = false
                                modelsFor = active
                                models = null
                                modelsError = null
                                scope.launch {
                                    try { models = chats.listModels(active) } catch (e: Exception) { modelsError = e.message ?: e.toString() }
                                }
                            },
                        )
                        androidx.compose.material3.HorizontalDivider()
                        Text(
                            stringResource(R.string.ai_chat_effort),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        AiSettings.effortOptions(active.provider, active.model).forEach { e ->
                            DropdownMenuItem(
                                text = { Text(if (e.isEmpty()) stringResource(R.string.ai_chat_effort_auto) else effortName(e)) },
                                leadingIcon = { RadioButton(selected = effort == e, onClick = null) },
                                onClick = {
                                    AiSettings.setEffort(context, active.id, e)
                                    pickerOpen = false
                                },
                            )
                        }
                    }
                }
            }
            modelsFor?.let { c ->
                dev.rustdroid.ide.ui.settings.ModelPickerDialog(
                    connection = c,
                    models = models,
                    error = modelsError,
                    onPick = {
                        AiSettings.setModel(context, c.id, it)
                        modelsFor = null
                    },
                    onDismiss = { modelsFor = null },
                )
            }
            // modo Plan (solo mira y propone) / Build (cambia archivos); no en Inicio
            if (!home) {
                val mode by vm.mode.collectAsStateWithLifecycle()
                val plan = mode == ChatSession.Mode.PLAN
                // PLAN va en el color complementario del principal (BUILD usa el principal)
                val chipColor = if (plan) complementary(MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.primary
                Text(
                    if (plan) "PLAN" else "BUILD",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = chipColor,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(
                            1.dp,
                            chipColor.copy(alpha = 0.6f),
                            RoundedCornerShape(6.dp),
                        )
                        .clickable(enabled = !busy) {
                            vm.setMode(if (plan) ChatSession.Mode.BUILD else ChatSession.Mode.PLAN)
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            // charlas de este proyecto: retomar o borrar
            var listOpen by remember { mutableStateOf(false) }
            var toDelete by remember { mutableStateOf<dev.rustdroid.ide.ai.ProjectChats.Summary?>(null) }
            // botones de la cabecera en blanco (el color de texto del chat);
            // apagados quedan un poco transparentes, pero siempre claros
            val headerIcon = LocalEditorPalette.current.stdout
            Box {
                val listEnabled = chatList.isNotEmpty()
                IconButton(onClick = { listOpen = true }, enabled = listEnabled, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.ai_chat_list),
                        tint = headerIcon.copy(alpha = if (listEnabled) 1f else 0.55f),
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
            val newEnabled = !busy && allMessages.isNotEmpty()
            IconButton(onClick = { chats.newChat() }, enabled = newEnabled, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.ai_chat_new),
                    tint = headerIcon.copy(alpha = if (newEnabled) 1f else 0.55f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // deslizar hacia abajo (estando arriba de todo) muestra la charla anterior:
        // aparece un círculo con una flecha que sigue al dedo, al soltar gira
        // una ruedita y los mensajes viejos entran por arriba con un fundido
        val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        // R4A: derivado → el panel se rearma sólo al cruzar el umbral, no en cada cuadro del tirón
        val pulledEnough by remember(pullState) {
            androidx.compose.runtime.derivedStateOf { pullState.distanceFraction >= 1f }
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = loadingOlder,
            onRefresh = {
                if (hiddenBefore > 0 && !loadingOlder) scope.launch {
                    loadingOlder = true
                    kotlinx.coroutines.delay(450)
                    dividerAt = hiddenBefore
                    revealAnim = true
                    vm.revealOlder()
                    loadingOlder = false
                    // la vista se queda en los últimos mensajes; lo anterior queda arriba
                    kotlinx.coroutines.delay(600)
                    revealAnim = false
                }
            },
            state = pullState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            indicator = {
                if (hiddenBefore > 0 || loadingOlder) {
                    fun frac() = if (loadingOlder) 1f else pullState.distanceFraction.coerceIn(0f, 1.4f)
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
                                val frac = frac()
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
            run {
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
                    // clave estable por mensaje: agregar texto o revelar lo anterior no
                    // redibuja los demás; sin animación de lugar (eso hacía saltar todo)
                    messages.forEachIndexed { i, m ->
                        val abs = hiddenBefore + i
                        if (dividerAt != null && abs == dividerAt) {
                            item(key = "divider") { OlderDivider() }
                        }
                        item(key = m.id, contentType = m.role) {
                            Box(
                                Modifier.animateItem(
                                    fadeInSpec = if (revealAnim) androidx.compose.animation.core.tween(350) else null,
                                    placementSpec = null,
                                    fadeOutSpec = null,
                                ),
                            ) {
                                if (m.role == ChatSession.Role.CHANGE) {
                                    ChangeCard(
                                        m,
                                        diffCache = diffCache,
                                        busy = busy,
                                        change = m.changeId?.let { vm.change(it) },
                                        onOpenAt = { line -> m.path?.let { onOpenFile(it, line) } },
                                        onUndo = { m.changeId?.let { vm.undo(it) } },
                                        onOpenProject = { m.projectRef?.let(onOpenProject) },
                                    )
                                } else {
                                    MessageBubble(
                                        m,
                                        streaming = busy && m.role == ChatSession.Role.ASSISTANT && i == messages.lastIndex,
                                    )
                                }
                            }
                        }
                    }
                    // R4A: el pedido falló: el error queda a la vista y este botón lo vuelve a mandar
                    if (canRetry && !busy && messages.lastOrNull()?.role == ChatSession.Role.ERROR) {
                        item(key = "retry") {
                            androidx.compose.material3.OutlinedButton(
                                onClick = { follow = true; vm.retry() },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                Text(stringResource(R.string.ai_chat_retry))
                            }
                        }
                    }
                    // R4A: un 400 (pedido inválido) suele repetirse; los pasajeros (red, 429, 503) no.
                    // También al reabrir una charla que terminó así (ahí no hay Reintentar).
                    if (!busy && messages.lastOrNull()?.let { it.role == ChatSession.Role.ERROR && isBadRequest(it.text) } == true) {
                        item(key = "retry-hint") {
                            Text(
                                stringResource(R.string.ai_chat_bad_request_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                    // entre paso y paso (usando herramientas) también se ve que sigue trabajando
                    if (busy && messages.lastOrNull()?.role != ChatSession.Role.ASSISTANT) {
                        item(key = "working") {
                            Box(Modifier.padding(horizontal = 14.dp)) { TypingDots() }
                        }
                    }
                }
            }
        }

        // R4A (auditoría M4): la IA pide permiso para correr código de afuera
        val approval by vm.approval.collectAsStateWithLifecycle()
        approval?.let { ApprovalCard(it.ask, onAnswer = vm::answerApproval) }

        // key: cada charla tiene su propio borrador
        androidx.compose.runtime.key(vm) {
            ChatInput(
                busy = busy,
                initialText = remember(vm) { vm.draft() },
                onDraft = { vm.saveDraft(it) },
                onSend = { follow = true; vm.saveDraft(""); vm.send(it) },
                onStop = { vm.stop() },
                onFocusChanged = onInputFocusChanged,
            )
        }
    }
}

/** R4A (auditoría M4): "¿Lo permitís?" con dos botones, arriba del cuadro de texto. */
@Composable
private fun ApprovalCard(ask: dev.rustdroid.ide.ai.ProjectTools.Ask, onAnswer: (Boolean) -> Unit) {
    val delete = ask is dev.rustdroid.ide.ai.ProjectTools.Ask.Delete
    val text = approvalText(ask)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            androidx.compose.material3.TextButton(onClick = { onAnswer(false) }) {
                Text(stringResource(if (delete) R.string.ai_delete_no else R.string.ai_approval_deny))
            }
            androidx.compose.material3.TextButton(onClick = { onAnswer(true) }) {
                Text(stringResource(if (delete) R.string.ai_delete_yes else R.string.ai_approval_allow))
            }
        }
    }
}

/**
 * Un mensaje. [streaming] = la IA lo está escribiendo ahora: el texto aparece
 * letra por letra (con un ritmo parejo aunque lleguen pedazos grandes) y,
 * mientras está vacío, se ven los puntos de "escribiendo…". Mantener apretado
 * → Copiar.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: ChatSession.ChatMessage, streaming: Boolean = false) {
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
    val shownText = if (streaming) typewriter(m.text) else m.text
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Box(Modifier.align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)) {
            Column(
                Modifier
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
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        enabled = m.text.isNotEmpty(),
                        onClick = {},
                        onLongClick = { menu = true },
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                val color = if (m.role == ChatSession.Role.ERROR) MaterialTheme.colorScheme.error else palette.stdout
                if (shownText.isEmpty()) TypingDots()
                else ChatMarkdown(shownText, color)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_chat_copy)) },
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text))
                        menu = false
                    },
                )
            }
        }
    }
}

/**
 * Muestra [full] de a poco: avanza hacia el final con un paso que crece si
 * quedó mucho por mostrar (así nunca se atrasa demasiado).
 */
@Composable
private fun typewriter(full: String): String {
    var shown by remember { mutableStateOf(0) }
    val target = full.length
    LaunchedEffect(target) {
        while (shown < target) {
            val left = target - shown
            shown = (shown + maxOf(1, left / 12)).coerceAtMost(target)
            kotlinx.coroutines.delay(16)
        }
    }
    if (shown > target) shown = target
    return full.substring(0, shown)
}

/** Tres puntos que suben y bajan: la IA está pensando o por escribir. */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val color = LocalEditorPalette.current.stdout
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
    Row(modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(450, delayMillis = i * 150),
                    androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer { alpha = a; translationY = (1f - a) * 3.dp.toPx() }
                    .background(color, androidx.compose.foundation.shape.CircleShape),
            )
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
    diffCache: MutableMap<Long, LineDiff.Result>,
    busy: Boolean,
    change: dev.rustdroid.ide.ai.ProjectTools.Change?,
    onOpenAt: (Int?) -> Unit,
    onUndo: () -> Unit,
    onOpenProject: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    // el diff se calcula una vez; la tarjeta aparece abierta para ver el cambio enseguida
    // R4A (auditoría B11): el diff se calcula en un hilo de fondo (con
    // archivos grandes trababa el chat); aparece apenas está listo
    val diffState = androidx.compose.runtime.produceState(diffCache[m.id], m.id, change) {
        if (value != null) return@produceState
        value = change?.takeIf { !it.isDir }?.let { c ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { LineDiff.compute(c.before, c.after) }
                .also { diffCache[m.id] = it }
        }
    }
    val diff = diffState.value
    var expanded by remember(m.id) { mutableStateOf(true) }
    var fullScreen by remember(m.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (m.undone) 0.15f else 0.45f), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (m.undone) "↶ " + m.text else m.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (m.undone) MaterialTheme.colorScheme.onSurfaceVariant else palette.stdout,
                textDecoration = if (m.undone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = diff != null && !m.undone) { expanded = !expanded }
                    .padding(vertical = 6.dp),
            )
            if (diff != null && !m.undone) {
                Text(
                    if (expanded) "▴" else "▾",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 6.dp),
                )
            }
            if (m.projectRef != null) {
                TextButton(onClick = onOpenProject, enabled = !busy) { Text(stringResource(R.string.ai_change_open_project)) }
            }
            if (m.path != null && !m.undone) {
                TextButton(onClick = { onOpenAt(diff?.firstChangedNewLine) }) { Text(stringResource(R.string.ai_change_open)) }
            }
            if (m.changeId != null && !m.undone) {
                TextButton(onClick = onUndo, enabled = !busy) { Text(stringResource(R.string.ai_change_undo)) }
            }
        }
        if (diff != null && expanded && !m.undone) {
            DiffPreview(diff, maxLines = 30)
            TextButton(onClick = { fullScreen = true }, modifier = Modifier.padding(start = 4.dp)) {
                Text(stringResource(R.string.ai_diff_full), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (fullScreen && diff != null) {
        DiffDialog(title = m.path ?: m.text, result = diff, onDismiss = { fullScreen = false })
    }
}


@Composable
private fun ChatInput(
    busy: Boolean,
    initialText: String,
    onDraft: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val palette = LocalEditorPalette.current
    var input by rememberSaveable { mutableStateOf(initialText) }
    // el borrador se guarda un ratito después de dejar de escribir, y al salir
    LaunchedEffect(input) {
        kotlinx.coroutines.delay(400)
        onDraft(input)
    }
    val latest by androidx.compose.runtime.rememberUpdatedState(input)
    DisposableEffect(Unit) {
        onDispose {
            onDraft(latest)
            onFocusChanged(false)
        }
    }

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

/** Nombre corto del nivel de esfuerzo. */
@Composable
private fun effortName(e: String): String = when (e) {
    AiSettings.EFFORT_OFF -> stringResource(R.string.ai_effort_off)
    "low" -> stringResource(R.string.ai_effort_low)
    "medium" -> stringResource(R.string.ai_effort_medium)
    "high" -> stringResource(R.string.ai_effort_high)
    "xhigh" -> stringResource(R.string.ai_effort_xhigh)
    "max" -> stringResource(R.string.ai_effort_max)
    else -> e
}

/** El color opuesto en la rueda de colores (mismo brillo y saturación). */
private fun complementary(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = (hsv[0] + 180f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * R4A: el error es un 400 ("pedido inválido"): el proveedor rechaza la charla
 * tal como está, así que Reintentar suele no alcanzar. Todos los clientes lo
 * escriben como "Error 400…" (ver AiClient, ChatGptClient, AnthropicChat).
 */
internal fun isBadRequest(error: String): Boolean = Regex("""^Error 400\b""").containsMatchIn(error.trimStart())

/** R4A: el texto del permiso, en el idioma de la app. */
@Composable
private fun approvalText(ask: dev.rustdroid.ide.ai.ProjectTools.Ask): String = when (ask) {
    is dev.rustdroid.ide.ai.ProjectTools.Ask.Delete ->
        if (ask.isDir) androidx.compose.ui.res.pluralStringResource(R.plurals.ai_ask_delete_dir, ask.fileCount, ask.path, ask.fileCount)
        else stringResource(R.string.ai_ask_delete_file, ask.path)
    is dev.rustdroid.ide.ai.ProjectTools.Ask.Run -> stringResource(R.string.ai_ask_run, reachText())
    is dev.rustdroid.ide.ai.ProjectTools.Ask.Build -> stringResource(
        R.string.ai_ask_build,
        ask.files.joinToString(", "),
        if (ask.newCrates.isEmpty()) "" else stringResource(R.string.ai_ask_new_crates, ask.newCrates.joinToString(", ")) + " ",
        reachText(),
    )
}

/** R4A: qué puede tocar lo que corre: tus proyectos y, con permiso de almacenamiento, la memoria del celular. */
@Composable
private fun reachText(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val storage = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    return stringResource(if (storage) R.string.ai_ask_reach_storage else R.string.ai_ask_reach_project)
}
