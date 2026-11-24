package dev.rustdroid.ide.ui.editor

import androidx.compose.foundation.background
import dev.rustdroid.ide.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons


import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ExitToApp


import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ConsoleLine
import dev.rustdroid.ide.ui.components.RdIcons
import dev.rustdroid.ide.ui.components.FileTypeIcon
import dev.rustdroid.ide.ui.components.EdgeDrawer
import dev.rustdroid.ide.ui.components.rememberEdgeDrawerState
import dev.rustdroid.ide.model.Diagnostic
import dev.rustdroid.ide.model.FileNode
import dev.rustdroid.ide.model.Severity
import dev.rustdroid.ide.model.Stream
import dev.rustdroid.ide.runtime.ConsoleClassifier
import dev.rustdroid.ide.runtime.ConsoleKind
import dev.rustdroid.ide.ui.theme.LocalEditorPalette
import dev.rustdroid.ide.ui.theme.MonoSmall
import dev.rustdroid.ide.ui.chat.AiBadge
import dev.rustdroid.ide.ui.chat.ChatDrawerSheet

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    container: AppContainer,
    projectName: String,
    onOpenDeps: (String) -> Unit,
    onOpenTerminal: (String) -> Unit = {},
    initialFile: String? = null,
    onOpenSettings: () -> Unit = {},
    /** R4A: entrar con el chat de la IA ya abierto (botón AI de la tarjeta en Inicio). */
    openChat: Boolean = false,
    /** R4A: abrir otro proyecto (uno que creó la IA). */
    onOpenProject: (String) -> Unit = {},
    onExit: () -> Unit = {},
) {
    val vm: EditorViewModel = viewModel(
        key = "editor-$projectName",
        factory = EditorViewModel.factory(container, projectName, initialFile),
    )
    // R4A: la charla con la IA de ESTE proyecto (se guarda en el teléfono).
    // Mientras el editor está abierto, sus herramientas trabajan sobre él.
    // (attach/detach los hace el VM: ver EditorViewModel.chat)
    val chat = vm.chat
    val chatEnabled by dev.rustdroid.ide.util.AppSettings.aiChatEnabled.collectAsState()
    val chatDrawerState = rememberEdgeDrawerState()
    var moreMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (openChat) chatDrawerState.open() }
    val tabs by vm.tabs.collectAsState()
    val activeIndex by vm.activeIndex.collectAsState()
    val activeText by vm.activeText.collectAsState()
    val tree by vm.tree.collectAsState()
    val running by vm.running.collectAsState()
    val gameRunning by vm.gameRunning.collectAsState()
    // R4A: find / replace bar under the tabs
    var searchOpen by remember { mutableStateOf(false) }
    // R4A: export APK (games only)
    val exportedApk by vm.exported.collectAsState()
    var showExport by remember { mutableStateOf(false) }
    // re-read when the drawer opens: Deps may have added macroquad meanwhile
    var isGame by remember { mutableStateOf(vm.isGameProject()) }
    if (showExport) {
        val iconScope = androidx.compose.runtime.rememberCoroutineScope()
        ExportApkDialog(
            initial = remember { vm.exportDefaults() },
            iconFile = vm.exportIconFile,
            onLoadIcon = { uri, done -> iconScope.launch { done(vm.loadIconSource(uri)) } },
            onSaveIcon = { src, rect, done -> iconScope.launch { done(vm.saveExportIcon(src, rect)) } },
            onClearIcon = { vm.clearExportIcon() },
            onExport = { opts ->
                showExport = false
                vm.exportApk(opts)
            },
            onDismiss = { showExport = false },
        )
    }
    exportedApk?.let { apk ->
        ExportedApkDialog(apk, onDismiss = { vm.clearExported() })
    }
    val problems by vm.problems.collectAsState()
    val lastResult by vm.lastResult.collectAsState()
    val bottomTab by vm.bottomTab.collectAsState()
    val panelCollapsed by vm.panelCollapsed.collectAsState()
    // R4A: errors while typing, for the active file
    val live by vm.live.collectAsState()
    val activeRel = tabs.getOrNull(activeIndex)?.relativePath
    val liveForFile = remember(live, activeRel) {
        live?.diagnostics?.filter { it.file == activeRel }.orEmpty()
    }
    // R4A: Settings > Appearance > file type icons (on by default)
    val showFileIcons by dev.rustdroid.ide.util.AppSettings.fileIcons.collectAsState()
    val jump by vm.jump.collectAsState()
    val showCloseConfirm by vm.showCloseConfirm.collectAsState()
    val consoleLines by vm.console.lines.collectAsState()

    // new-file dialog state: name lives locally; success/error come from the
    // VM (one-shot createdFile closes the dialog, createFileError shows inline)
    val createdFile by vm.createdFile.collectAsState()
    val createFileError by vm.createFileError.collectAsState()
    var showNewFile by remember { mutableStateOf(false) }
    // R4A: el + pregunta: archivo o carpeta
    var newIsFolder by remember { mutableStateOf(false) }
    var newMenuOpen by remember { mutableStateOf(false) }
    // R4A: importar archivos del teléfono → elegir carpeta del proyecto
    var pendingImport by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) pendingImport = uris }

    // delete-file dialog state: the row long-press picks the target; the
    // VM's one-shot deletedFile closes the dialog, deleteFileError shows
    // inline (e.g. the root Cargo.toml guard)
    val deletedFile by vm.deletedFile.collectAsState()
    val deleteFileError by vm.deleteFileError.collectAsState()
    var pendingDelete by remember { mutableStateOf<FileNode?>(null) }
    // R4A: long-press menu (rename/move, delete) and the rename/move dialog
    var fileMenu by remember { mutableStateOf<FileNode?>(null) }
    var pendingMove by remember { mutableStateOf<FileNode?>(null) }
    val movedFile by vm.movedFile.collectAsState()
    val moveFileError by vm.moveFileError.collectAsState()

    // Re-entering this screen (e.g. back from the Deps screen) re-runs this
    // effect: pull fresh bytes for any tab that has no unsaved edits AND
    // re-list the file tree, so files changed on disk elsewhere (another
    // app, or — for folders opened in place — the user on a PC) are
    // reflected here instead of shown stale (or clobbered on save).
    LaunchedEffect(Unit) { vm.reloadUnchangedTabs(); vm.refreshTree() }

    // Deep-linked file ("open with" import): the VM init already opened it
    // for fresh instances; this covers re-entering an existing VM for the
    // same project with a different file. openFile is idempotent (focuses
    // the tab when already open).
    LaunchedEffect(initialFile) {
        initialFile?.let { vm.openFile(it) }
    }

    val dark = dev.rustdroid.ide.ui.theme.LocalDarkTheme.current
    val palette = LocalEditorPalette.current

    // IME-aware layout: editor focus + visible keyboard => hide the console
    // panel so the script gets the whole space above the IME (see bottom
    // panel below). Track View-level focus from sora-editor.
    // R4A: true while the console's stdin field has focus (see hideBottom)
    var stdinFocused by remember { mutableStateOf(false) }
    // R4A: folders folded in the Files tree (relative paths)
    var collapsedDirs by remember { mutableStateOf(emptySet<String>()) }
    // R4A: the sora editor view, so the symbol bar can type into it
    var codeEditor by remember { mutableStateOf<io.github.rosemoe.sora.widget.CodeEditor?>(null) }
    // R4A: undo/redo buttons in the top bar, greyed out when there is nothing
    // to undo/redo (refreshed on every content change of the editor)
    var canUndo by remember { mutableStateOf(false) }
    // R4A: ⓘ toggle — code suggestions while typing
    val autoComplete by dev.rustdroid.ide.util.AppSettings.autoComplete.collectAsState()
    val appContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(autoComplete, codeEditor) {
        codeEditor?.getComponent(
            io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java
        )?.let { completion ->
            completion.isEnabled = autoComplete
            if (!autoComplete) completion.hide()
        }
    }
    var canRedo by remember { mutableStateOf(false) }
    DisposableEffect(codeEditor) {
        val ed = codeEditor
        // The event fires while undo()/redo() are still updating the history,
        // so read the state on the next loop turn (post), not inside the event.
        val refresh = Runnable {
            canUndo = ed?.canUndo() == true
            canRedo = ed?.canRedo() == true
        }
        val receipt = ed?.subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) { _, _ ->
            ed.post(refresh)
        }
        canUndo = ed?.canUndo() == true
        canRedo = ed?.canRedo() == true
        onDispose { receipt?.unsubscribe() }
    }

    // v0.2.1 (review P2): the console panel's visibility is decided by the IME
    // inset's DIRECTION, not by a fixed fraction of the screen. The v0.2 rule
    // ("inset > 12% of screen height = keyboard up") was tuned to a full-height
    // phone keyboard and inverted for anything shorter: a floating/split IME
    // holding well under 12% read as CLOSED for the entire time it was open,
    // so the console panel popped back while the user was still typing — the
    // original v0.2 complaint, reversed. Direction distinguishes a small
    // keyboard from no keyboard, and closing on the first SHRINKING frame is
    // what delivers the v0.2 latency fix. See ImeDeltaTracker (pure state
    // machine, unit-tested in ImeDeltaTrackerTest).
    val imeTracker = remember { ImeDeltaTracker() }
    val imeVisible = imeTracker.update(
        WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    )

    val drawerState = rememberEdgeDrawerState()
    // fresh tree every time the drawer starts opening (button or swipe): the
    // folder may have changed on disk (opened-in-place projects are shared)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerState) {
        androidx.compose.runtime.snapshotFlow { drawerState.isOpen }
            .collect { open ->
                if (open) {
                    vm.refreshTree()
                    isGame = vm.isGameProject()
                    // R4A: opening Files closes the keyboard and any editor
                    // popup (error card, completion) so they don't sit on top
                    codeEditor?.hideEditorWindows()
                    // hideEditorWindows() does not include the error card
                    codeEditor?.getComponent(
                        io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow::class.java
                    )?.dismiss()
                    codeEditor?.hideSoftInput()
                    keyboard?.hide()
                }
            }
    }
    val drawerScope = androidx.compose.runtime.rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // R4A: leaving the project — from the drawer's "Salir" button or the
    // system back gesture. Unsaved edits used to be dropped silently on back.
    var exitUnsaved by remember { mutableStateOf<List<String>?>(null) }
    val requestExit: () -> Unit = {
        val unsaved = vm.unsavedFiles()
        if (unsaved.isEmpty()) onExit() else exitUnsaved = unsaved
    }
    // R4A: first live check when the project opens, and again whenever the
    // setting is switched back on (switching it off clears the squiggles)
    val liveCheckEnabled by dev.rustdroid.ide.util.AppSettings.liveCheck.collectAsState()
    LaunchedEffect(liveCheckEnabled) { vm.scheduleLiveCheck(500) }
    // R4A: a macroquad game finished building — show it full screen
    LaunchedEffect(vm) {
        vm.launchGame.collect { launch -> showGame(appContext, launch) }
    }
    androidx.activity.compose.BackHandler {
        if (chatDrawerState.isOpen) {
            drawerScope.launch { chatDrawerState.close() }
        } else if (drawerState.isOpen) {
            drawerScope.launch { drawerState.close() }
        } else {
            requestExit()
        }
    }

    // auto-scroll console to bottom on new lines
    val consoleListState = rememberLazyListState()
    LaunchedEffect(consoleLines.size) {
        if (consoleLines.isNotEmpty()) {
            consoleListState.scrollToItem(consoleLines.size - 1)
        }
    }
    // clear the jump request after the editor consumed it
    LaunchedEffect(jump, activeText) {
        if (jump != null) vm.clearJump()
    }
    // close the new-file dialog once the VM reports the file was created
    LaunchedEffect(createdFile) {
        if (createdFile != null) {
            showNewFile = false
            vm.clearNewFileSignals()
        }
    }
    LaunchedEffect(movedFile) {
        if (movedFile != null) {
            pendingMove = null
            vm.clearMoveSignals()
        }
    }
    // close the delete dialog once the VM reports the file is gone
    LaunchedEffect(deletedFile) {
        if (deletedFile != null) {
            pendingDelete = null
            vm.clearDeleteSignals()
        }
    }

    // R4A: EdgeDrawer instead of Material's ModalNavigationDrawer. The code
    // editor is a sora-editor Android View: M3's drawer drag detector, when
    // enabled while closed, watched the same pointer stream over the editor,
    // stole long-presses (text selection never fired) and read scrolling as
    // a horizontal drag. EdgeDrawer only listens on a thin left-edge strip
    // while closed, and the drawer follows the finger.
    // R4A: el Chat IA es otro EdgeDrawer, desde la derecha, que envuelve al de Files.
    EdgeDrawer(
        state = chatDrawerState,
        fromEnd = true,
        widthFraction = 0.9f,
        maxSheetWidth = 460.dp,
        edgeEnabled = chatEnabled,
        drawerContent = {
            ChatDrawerSheet(
                chat = chat,
                onClose = { drawerScope.launch { chatDrawerState.close() } },
                onOpenFile = { rel, line ->
                    drawerScope.launch { chatDrawerState.close() }
                    // abre el archivo y va (con un destello) a la primera línea que cambió
                    if (line != null) vm.revealLine(rel, line) else vm.openFile(rel)
                },
                onOpenProject = { ref ->
                    // la charla sigue en el proyecto nuevo; se guarda todo antes de salir
                    val dir = container.projectRepository.resolve(ref)
                    container.chatStore.copyTo(chat.current.value, dir, dir.name)
                    vm.saveAllThen { onOpenProject(ref) }
                },
            )
        },
    ) {
    EdgeDrawer(
        state = drawerState,
        drawerContent = {
            // R4A: Files con un verde suave (no blanco) en modo claro
            ModalDrawerSheet(
                drawerContainerColor = if (dark) MaterialTheme.colorScheme.surfaceContainerLow
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(RdIcons.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.editor_files), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(
                                onClick = { newMenuOpen = true },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.editor_new_item),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            DropdownMenu(expanded = newMenuOpen, onDismissRequest = { newMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_new_file)) },
                                    leadingIcon = { Icon(RdIcons.FileGeneric, null) },
                                    onClick = { newMenuOpen = false; newIsFolder = false; showNewFile = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_new_folder)) },
                                    leadingIcon = { Icon(RdIcons.Folder, null) },
                                    onClick = { newMenuOpen = false; newIsFolder = true; showNewFile = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_import_files)) },
                                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                                    onClick = { newMenuOpen = false; importLauncher.launch(arrayOf("*/*")) },
                                )
                            }
                        }
                    }
                    // R4A: tree-style guides + collapsible folders
                    val treeRows = remember(tree, collapsedDirs) {
                        FileTreeGuides.rows(tree, collapsedDirs)
                    }
                    LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                        items(treeRows, key = { it.node.relativePath }) { row ->
                            FileTreeRow(
                                row,
                                showFileIcon = showFileIcons,
                                onClick = {
                                    val node = row.node
                                    if (node.isDirectory) {
                                        val p = node.relativePath
                                        collapsedDirs = if (p in collapsedDirs) collapsedDirs - p else collapsedDirs + p
                                    } else {
                                        vm.openFile(node.relativePath)
                                        drawerScope.launch { drawerState.close() }
                                    }
                                },
                                onLongPress = { fileMenu = row.node },
                            )
                        }
                    }
                    // R4A: project tools (moved here from the top bar). Each
                    // group is a Column with one fixed gap between rows, so
                    // the spacing stays even whether or not Export shows up.
                    val rowMod = Modifier.padding(horizontal = 12.dp).height(40.dp)
                    androidx.compose.material3.HorizontalDivider()
                    Column(
                        Modifier.padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // games can be exported as an installable APK
                        if (isGame) {
                            androidx.compose.material3.NavigationDrawerItem(
                                label = { Text(stringResource(R.string.export_apk_title), fontSize = 12.sp) },
                                icon = { Icon(RdIcons.Android, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                selected = false,
                                onClick = {
                                    drawerScope.launch { drawerState.close() }
                                    showExport = true
                                },
                                modifier = rowMod,
                            )
                        }
                        androidx.compose.material3.NavigationDrawerItem(
                            label = { Text(stringResource(R.string.editor_deps), fontSize = 12.sp) },
                            icon = { Icon(RdIcons.Package, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            selected = false,
                            onClick = {
                                drawerScope.launch { drawerState.close() }
                                onOpenDeps(projectName)
                            },
                            modifier = rowMod,
                        )
                        androidx.compose.material3.NavigationDrawerItem(
                            label = { Text(stringResource(R.string.terminal_title), fontSize = 12.sp) },
                            icon = { Icon(RdIcons.Console, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            selected = false,
                            onClick = {
                                drawerScope.launch { drawerState.close() }
                                // the session starts IN this project's directory
                                onOpenTerminal(projectName)
                            },
                            modifier = rowMod,
                        )
                    }
                    // R4A: project actions pinned to the bottom of the drawer
                    androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                    Column(
                        Modifier.padding(top = 6.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        androidx.compose.material3.NavigationDrawerItem(
                            label = { Text(stringResource(R.string.common_settings), fontSize = 12.sp) },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            selected = false,
                            onClick = {
                                drawerScope.launch { drawerState.close() }
                                onOpenSettings()
                            },
                            // compact rows (Material default is 56dp each)
                            modifier = rowMod,
                        )
                        androidx.compose.material3.NavigationDrawerItem(
                            label = { Text(stringResource(R.string.editor_exit_project), fontSize = 12.sp) },
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            selected = false,
                            onClick = {
                                drawerScope.launch { drawerState.close() }
                                requestExit()
                            },
                            modifier = rowMod,
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    // R4A: en modo claro la barra de arriba va en verde suave (el código, en blanco)
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (dark) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    navigationIcon = {
                        IconButton(onClick = {
                            // fresh tree every open: the folder may have
                            // changed on disk since (opened-in-place projects
                            // are shared with the rest of the system)
                            vm.refreshTree()
                            drawerScope.launch { drawerState.open() }
                        }) {
                            Icon(RdIcons.Folder, contentDescription = stringResource(R.string.editor_files))
                        }
                    },
                    title = {
                        Text(
                            projectName.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        // R4A: Deps and Terminal moved to the Files drawer
                        // R4A: el Chat IA se abre desde acá (o deslizando desde la derecha).
                        // Con el chat desactivado queda el hueco del botón, así el
                        // título no cambia de largo al prender/apagar el chat.
                        if (chatEnabled) {
                            IconButton(onClick = {
                                codeEditor?.hideEditorWindows()
                                codeEditor?.hideSoftInput()
                                keyboard?.hide()
                                drawerScope.launch { chatDrawerState.open() }
                            }) {
                                AiBadge(contentDescription = stringResource(R.string.ai_chat_open))
                            }
                        } else {
                            Spacer(Modifier.size(48.dp))
                        }
                        if (tabs.isNotEmpty()) {
                            IconButton(onClick = { codeEditor?.undo() }, enabled = canUndo) {
                                Icon(RdIcons.Undo, contentDescription = stringResource(R.string.editor_undo))
                            }
                            IconButton(onClick = { codeEditor?.redo() }, enabled = canRedo) {
                                Icon(RdIcons.Redo, contentDescription = stringResource(R.string.editor_redo))
                            }
                        }
                        if (running) {
                            IconButton(onClick = { vm.stop() }) {
                                Icon(RdIcons.Stop, contentDescription = stringResource(R.string.editor_stop), tint = MaterialTheme.colorScheme.error)
                            }
                        } else if (gameRunning) {
                            // R4A: a game is open behind the code — go back to it
                            // (it slides in from the right) or close it
                            IconButton(onClick = { showGame(appContext, null) }) {
                                Icon(RdIcons.Gamepad, contentDescription = stringResource(R.string.editor_back_to_game), tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { vm.stopGame() }) {
                                Icon(RdIcons.Stop, contentDescription = stringResource(R.string.editor_stop_game), tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(onClick = {
                                // R4A: hide the keyboard so the console is visible
                                codeEditor?.hideEditorWindows()
                                codeEditor?.hideSoftInput()
                                keyboard?.hide()
                                vm.run()
                            }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.editor_run), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        // R4A: ⋮ — buscar, Chat IA y ayuda para programar
                        Box {
                            IconButton(onClick = { moreMenuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.editor_more))
                            }
                            DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.search_title)) },
                                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                                    enabled = tabs.isNotEmpty(),
                                    onClick = {
                                        moreMenuOpen = false
                                        searchOpen = !searchOpen
                                        if (!searchOpen) codeEditor?.searcher?.stopSearch()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ai_chat_menu)) },
                                    leadingIcon = { AiBadge() },
                                    trailingIcon = { androidx.compose.material3.Checkbox(checked = chatEnabled, onCheckedChange = null) },
                                    onClick = {
                                        val on = !chatEnabled
                                        dev.rustdroid.ide.util.AppSettings.setAiChatEnabled(appContext, on)
                                        if (!on) drawerScope.launch { chatDrawerState.close() }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_coding_help)) },
                                    leadingIcon = {
                                        Icon(if (autoComplete) RdIcons.Info else RdIcons.InfoOutline, null)
                                    },
                                    trailingIcon = { androidx.compose.material3.Checkbox(checked = autoComplete, onCheckedChange = null) },
                                    onClick = {
                                        dev.rustdroid.ide.util.AppSettings.setAutoComplete(appContext, !autoComplete)
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            // imePadding on the whole column: with edge-to-edge + decor not
            // fitting system windows, adjustResize is neutralized — IME
            // insets flow through WindowInsets and must be applied manually.
            // Children (editor / bottom panel) then always sit above the
            // keyboard, whichever of them owns the input focus.
            // R4A: consume the scaffold insets first, otherwise imePadding adds the
            // navigation-bar height again (empty strip above the keyboard)
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                // ---- tabs row (compact 30dp strip — M3 Tab's 48dp minimum
                // + padding ate a quarter of the screen; every dp counts on a
                // phone with the keyboard open) ----
                if (tabs.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp) // R4A: was 30dp — easier to read and tap
                            .horizontalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        tabs.forEachIndexed { i, tab ->
                            val selected = i == activeIndex
                            val indicator = MaterialTheme.colorScheme.primary
                            val activeBg = MaterialTheme.colorScheme.surface
                            Row(
                                Modifier
                                    .height(38.dp)
                                    .background(if (selected) activeBg else Color.Transparent)
                                    .drawBehind {
                                        if (selected) {
                                            val h = 2.dp.toPx()
                                            drawRect(
                                                color = indicator,
                                                topLeft = Offset(0f, size.height - h),
                                                size = Size(size.width, h),
                                            )
                                        }
                                    }
                                    // R4A: tap selects, long-press closes (no
                                    // small X that is easy to hit by accident)
                                    .combinedClickable(
                                        onClick = { vm.setActive(i) },
                                        onLongClickLabel = stringResource(R.string.editor_close_tab),
                                        onLongClick = {
                                            haptics.performHapticFeedback(
                                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                            )
                                            vm.closeTab(i, force = false)
                                        },
                                    )
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (tab.dirty) {
                                    Box(
                                        Modifier
                                            .width(6.dp).height(6.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                if (showFileIcons) {
                                    FileTypeIcon(tab.relativePath.substringAfterLast('/'), size = 15.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    tab.relativePath.substringAfterLast('/'),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 14.sp), // R4A: was 11sp
                                    color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ---- find / replace (R4A) ----
                val editorForSearch = codeEditor
                if (searchOpen && tabs.isNotEmpty() && editorForSearch != null) {
                    EditorSearchBar(
                        editor = editorForSearch,
                        fileKey = activeRel,
                        onClose = {
                            searchOpen = false
                            editorForSearch.searcher.stopSearch()
                        },
                    )
                }

                // ---- editor ----
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (tabs.isEmpty()) {
                        Text(
                            stringResource(R.string.editor_open_from_drawer),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        CodeEditorPane(
                            text = activeText,
                            dark = dark,
                            onTextChange = { vm.onTextChange(it) },
                            jumpRequest = jump,
                            onEditorReady = { codeEditor = it },
                            liveDiagnostics = liveForFile,
                            checkedText = live?.checkedText?.get(activeRel),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // ---- symbol bar (R4A) ----
                // Sits between the editor and the bottom panel: with the
                // keyboard open the panel hides, so the bar ends up right above
                // the keyboard; without it, the bar is above Console/Problems.
                if (tabs.isNotEmpty()) {
                    SymbolBar(
                        onTab = { codeEditor?.indentOrCommitTab() },
                        onSymbol = { codeEditor?.commitText(it) },
                    )
                }

                // ---- bottom panel ----
                // Hidden while the editor is focused with the IME open: the
                // keyboard already eats half the screen, and a 220dp console
                // + its tab row on top of that left only a sliver of code
                // visible (the "tiny portion of the script" report). The
                // panel comes back the moment the keyboard closes. Typing in
                // the console's stdin bar keeps the panel (it owns focus
                // there) with the console shrunk to 88dp instead of 220dp.
                //
                // imeVisible now comes from ImeDeltaTracker (above), which
                // closes on the first SHRINKING frame rather than waiting for
                // the inset to reach zero (~300ms+ on many OEM skins — the
                // v0.2 "console takes a second to come back" report) and
                // treats a small but held-open keyboard as open (the
                // floating-IME inversion the threshold rule had).
                // R4A: hide whenever the keyboard is up, unless the user is
                // typing into the console's stdin field. It used to require
                // the editor to hold focus, so coming back from the terminal
                // with the keyboard still open (editor not focused) showed
                // the panel right above the keyboard.
                val hideBottom = imeVisible && !stdinFocused
                val errorCount = problems.count { it.severity == Severity.ERROR }
                val warningCount = problems.count { it.severity == Severity.WARNING }
                if (!hideBottom) {
                    Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth()) {
                        // R4A: folded panel -> neither tab looks selected (no
                        // indicator, unselected colors) until one is tapped
                        val tabIndex = bottomTab.ordinal // CONSOLE, PROBLEMS
                        TabRow(
                            selectedTabIndex = tabIndex,
                            indicator = { positions ->
                                if (!panelCollapsed) {
                                    androidx.compose.material3.TabRowDefaults.SecondaryIndicator(
                                        Modifier.tabIndicatorOffset(positions[tabIndex])
                                    )
                                }
                            },
                        ) {
                            Tab(
                                selected = !panelCollapsed && bottomTab == EditorViewModel.BottomTab.CONSOLE,
                                onClick = { vm.onBottomTabClicked(EditorViewModel.BottomTab.CONSOLE) },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(RdIcons.Terminal, null, Modifier.height(14.dp).width(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.editor_console))
                                        lastResult?.let {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                if (it.success) "· ok" else stringResource(R.string.editor_exit_code, it.exitCode),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (it.success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                },
                            )
                            Tab(
                                selected = !panelCollapsed && bottomTab == EditorViewModel.BottomTab.PROBLEMS,
                                onClick = { vm.onBottomTabClicked(EditorViewModel.BottomTab.PROBLEMS) },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Warning, null, Modifier.height(14.dp).width(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.editor_problems))
                                        if (errorCount + warningCount > 0) {
                                            Spacer(Modifier.width(6.dp))
                                            Badge { Text("$errorCount/$warningCount") }
                                        }
                                    }
                                },
                            )
                        }
                        // R4A: folded panel keeps only the tab buttons
                        if (!panelCollapsed) Box(
                            Modifier
                                .fillMaxWidth()
                                .height(if (imeVisible) 88.dp else 220.dp)
                                .background(palette.consoleBg)
                        ) {
                            when (bottomTab) {
                                EditorViewModel.BottomTab.CONSOLE -> ConsolePanel(
                                    lines = consoleLines,
                                    running = running,
                                    onSend = { vm.sendStdin(it) },
                                    onClear = { vm.clearConsole() },
                                    listState = consoleListState,
                                    onStdinFocusChanged = { stdinFocused = it },
                                )
                                EditorViewModel.BottomTab.PROBLEMS -> ProblemsPanel(
                                    problems = problems,
                                    onJump = { vm.jumpTo(it) },
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
    } // chat drawer

    if (showCloseConfirm) {
        AlertDialog(
            // R4A: tapping outside cancels (it used to DISCARD the edits)
            onDismissRequest = { vm.cancelClose() },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_close_tab_body)) },
            confirmButton = { TextButton(onClick = { vm.confirmClose(save = true) }) { Text(stringResource(R.string.editor_save_close)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.cancelClose() }) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = { vm.confirmClose(save = false) }) { Text(stringResource(R.string.editor_dont_save)) }
                }
            },
        )
    }

    exitUnsaved?.let { files ->
        AlertDialog(
            onDismissRequest = { exitUnsaved = null },
            title = { Text(stringResource(R.string.editor_exit_title)) },
            text = {
                Text(
                    if (files.size == 1) stringResource(R.string.editor_exit_one, files[0])
                    else stringResource(R.string.editor_exit_many, files.size, files.joinToString(", "))
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    exitUnsaved = null
                    vm.saveAllThen(onExit)
                }) { Text(stringResource(R.string.editor_save_exit)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { exitUnsaved = null }) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = {
                        exitUnsaved = null
                        onExit()
                    }) { Text(stringResource(R.string.editor_dont_save)) }
                }
            },
        )
    }

    pendingImport?.let { uris ->
        ImportFolderDialog(
            count = uris.size,
            folders = listOf("") + tree.filter { it.isDirectory }.map { it.relativePath },
            onPick = { folder ->
                pendingImport = null
                vm.importFiles(uris, folder) { copied, failed ->
                    val msg = if (failed.isEmpty()) appContext.getString(R.string.editor_import_done, copied, folder.ifBlank { "/" })
                    else appContext.getString(R.string.editor_import_partial, copied, failed.joinToString(", "))
                    android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { pendingImport = null },
        )
    }

    if (showNewFile) {
        NewFileDialog(
            folder = newIsFolder,
            error = createFileError,
            onCreate = { vm.createFile(it, folder = newIsFolder) },
            onDismiss = {
                vm.clearNewFileSignals()
                showNewFile = false
            },
        )
    }

    fileMenu?.let { node ->
        FileActionsDialog(
            node = node,
            onMove = { fileMenu = null; pendingMove = node },
            onDelete = { fileMenu = null; pendingDelete = node },
            onDismiss = { fileMenu = null },
        )
    }

    pendingMove?.let { node ->
        MoveFileDialog(
            node = node,
            error = moveFileError,
            onMove = { to -> vm.moveFile(node.relativePath, to) },
            onDismiss = {
                vm.clearMoveSignals()
                pendingMove = null
            },
        )
    }

    pendingDelete?.let { node ->
        DeleteFileDialog(
            node = node,
            error = deleteFileError,
            onDelete = { vm.deleteFile(node.relativePath) },
            onDismiss = {
                vm.clearDeleteSignals()
                pendingDelete = null
            },
        )
    }
}

/**
 * Creates an empty file inside the project. Accepts a path relative to the
 * project root ("src/foo.rs" or "notes.md"); the name is validated for
 * traversal/escape and must not already exist (errors show inline).
 */
@Composable
private fun NewFileDialog(
    folder: Boolean = false,
    error: String?,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (folder) R.string.editor_new_folder else R.string.editor_new_file)) },
        text = {
            Column {
                Text(
                    stringResource(if (folder) R.string.editor_new_folder_hint else R.string.editor_new_file_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(if (folder) R.string.editor_folder_name else R.string.editor_file_name)) },
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error, color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (trimmed.isNotEmpty()) onCreate(trimmed) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) { Text(stringResource(R.string.common_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * One drawer row. Tap opens a file (and closes the drawer) or folds/unfolds
 * a folder (R4A); long-press opens the file menu (rename/move, delete). The drawer
 * sheet is pure Compose, so combinedClickable's long-press does not fight
 * the sora-editor interop view (which only ever sits under the CLOSED
 * drawer — see the EdgeDrawer note above).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    row: TreeRow,
    showFileIcon: Boolean = true,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val node = row.node
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val col = TreeColumn
    val startPad = 10.dp
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            // `tree`-style guides: one column per level; ancestors that still
            // have siblings below get a full vertical line, the row's own
            // column gets ├ or └ (see FileTreeGuides).
            .drawBehind {
                val stroke = 1.dp.toPx()
                val colPx = col.toPx()
                val x0 = startPad.toPx()
                val midY = size.height / 2f
                row.continues.forEachIndexed { level, cont ->
                    if (cont) {
                        val x = x0 + level * colPx + colPx / 2f
                        drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), stroke)
                    }
                }
                val x = x0 + node.depth * colPx + colPx / 2f
                drawLine(guideColor, Offset(x, 0f), Offset(x, if (row.isLast) midY else size.height), stroke)
                drawLine(guideColor, Offset(x, midY), Offset(x + colPx / 2f + 2.dp.toPx(), midY), stroke)
            }
            .padding(
                start = startPad + col * (node.depth + 1) + 4.dp,
                top = 7.dp, bottom = 7.dp, end = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isDirectory) {
            Icon(
                if (row.expanded) RdIcons.FolderOpen else RdIcons.Folder, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        } else if (showFileIcon) {
            // R4A: icon by file type (Rust gear for .rs, etc.)
            FileTypeIcon(node.file.name, size = 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (node.isDirectory) node.file.name + "/" else node.file.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (node.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Width of one indentation level in the Files tree. */
private val TreeColumn = 14.dp

/** R4A: what to do with a file or folder of the Files tree (long-press). */
@Composable
private fun FileActionsDialog(
    node: FileNode,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (node.isDirectory) node.relativePath + "/" else node.relativePath,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                TextButton(onClick = onMove, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.files_rename_move), modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * R4A: rename or move [node]: the whole path is editable, so changing the
 * name renames and changing the folders moves. The cursor starts on the
 * name (without the extension) — the usual thing to change.
 */
@Composable
private fun MoveFileDialog(
    node: FileNode,
    error: String?,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = node.relativePath
    val nameStart = initial.lastIndexOf('/') + 1
    val nameEnd = if (node.isDirectory) initial.length
    else initial.lastIndexOf('.').takeIf { it > nameStart } ?: initial.length
    var value by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                initial, androidx.compose.ui.text.TextRange(nameStart, nameEnd),
            ),
        )
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val target = value.text.trim()
    val ok = target.isNotEmpty() && target != initial
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_rename_move)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.files_move_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.files_path)) },
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error, color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onMove(target) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(target) }, enabled = ok) { Text(stringResource(R.string.files_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * Confirms deleting [node] (a whole subtree when it is a directory).
 * Errors from the repository guards (root Cargo.toml, root itself, ...)
 * show inline and keep the dialog open, mirroring NewFileDialog.
 */
@Composable
private fun DeleteFileDialog(
    node: FileNode,
    error: String?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (node.isDirectory) stringResource(R.string.editor_delete_folder_q) else stringResource(R.string.editor_delete_file_q)) },
        text = {
            Column {
                Text(
                    node.relativePath +
                        if (node.isDirectory) stringResource(R.string.editor_and_everything_inside) else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.editor_cannot_undo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ConsolePanel(
    lines: List<ConsoleLine>,
    running: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onStdinFocusChanged: (Boolean) -> Unit = {},
) {
    val palette = LocalEditorPalette.current
    val clipboard = LocalClipboardManager.current
    val kinds = androidx.compose.runtime.remember(lines) { ConsoleClassifier.classify(lines) }
    Column(Modifier.fillMaxSize()) {
        // Console toolbar: guaranteed copy path. Long-press selection
        // (SelectionContainer below) can be fiddly on some devices and is
        // limited to on-screen lines — "Copy all" always works.
        Row(
            Modifier
                .fillMaxWidth()
                .background(palette.consoleBg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.editor_lines, lines.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(lines.joinToString("\n") { it.text }))
                },
                enabled = lines.isNotEmpty(),
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    RdIcons.Copy,
                    contentDescription = stringResource(R.string.editor_copy_all),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = onClear,
                enabled = lines.isNotEmpty() && !running,
                modifier = Modifier
                    .size(32.dp)
                    .padding(end = 6.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.editor_clear_console),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Selectable: long-press any build/run output to copy it (error
        // messages, compiler output, backtraces).
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    // R4A: color by meaning, not just by stream — cargo prints
                    // all of its progress on stderr, which used to be all red.
                    val color = when (kinds.getOrNull(index)) {
                        ConsoleKind.OUTPUT -> palette.stdout
                        ConsoleKind.STATUS -> palette.status
                        ConsoleKind.ERROR -> palette.stderr
                        ConsoleKind.WARNING -> palette.warning
                        ConsoleKind.NOTE -> palette.system
                        ConsoleKind.NOISE -> palette.noise
                        ConsoleKind.SYSTEM -> palette.system
                        ConsoleKind.STDERR, null -> when (line.stream) {
                            Stream.STDOUT -> palette.stdout
                            Stream.STDERR -> palette.stderr
                            Stream.SYSTEM -> palette.system
                        }
                    }
                    Text(
                        line.text.ifEmpty { " " },
                        style = MonoSmall,
                        color = color,
                    )
                }
            }
        }
        // stdin is only meaningful while a program is actually running —
        // hiding it otherwise removes the dead send button confusion.
        if (running) {
            StdinBar(onSend = onSend, onFocusChanged = onStdinFocusChanged)
        }
    }
}

@Composable
private fun StdinBar(onSend: (String) -> Unit, onFocusChanged: (Boolean) -> Unit = {}) {
    val palette = LocalEditorPalette.current
    var input by remember { mutableStateOf("") }
    // the bar disappears when the program ends — never leave "focused" stuck
    DisposableEffect(Unit) { onDispose { onFocusChanged(false) } }

    fun submit() {
        val text = input
        if (text.isNotEmpty()) {
            onSend(text)
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
        Text("> ", color = MaterialTheme.colorScheme.primary, style = MonoSmall)
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            textStyle = MonoSmall.copy(color = palette.stdout),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChanged(it.isFocused) },
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { submit() },
            enabled = input.isNotEmpty(),
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                RdIcons.Send,
                contentDescription = stringResource(R.string.editor_send_stdin),
                tint = if (input.isNotEmpty()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ProblemsPanel(
    problems: List<Diagnostic>,
    onJump: (Diagnostic) -> Unit,
) {
    if (problems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.editor_no_problems),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(problems) { _, d ->
            val tint = when (d.severity) {
                Severity.ERROR -> MaterialTheme.colorScheme.error
                Severity.WARNING -> Color(0xFFF5A623)
                Severity.NOTE -> MaterialTheme.colorScheme.tertiary
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = d.file != null) { onJump(d) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        buildString {
                            append(d.severity.name.lowercase())
                            d.code?.let { append(" [$it]") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                    Text(
                        d.message,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                    if (d.file != null) {
                        Text(
                            "${d.file}:${d.line ?: "?"}:${d.col ?: "?"}",
                            style = MonoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** R4A: symbols that are awkward to reach on a phone keyboard, Rust-first. */
private val BarSymbols = listOf(
    ";", ":", "{", "}", "(", ")", "[", "]", "\"", "'", "&", "*", "<", ">",
    "=", "!", "?", "|", "_", ".", ",", "#", "-", "+", "/", "%", "@", "$",
    "^", "~", "\\", "`",
)

/**
 * R4A: one-tap symbol row. Taps go through detectTapGestures rather than
 * clickable/Button so the row never takes focus away from the editor — the
 * keyboard stays open while typing symbols.
 */
@Composable
private fun SymbolBar(onTab: () -> Unit, onSymbol: (String) -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val keys = listOf("Tab") + BarSymbols
        keys.forEachIndexed { i, key ->
            if (i > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .widthIn(min = if (key == "Tab") 52.dp else 38.dp)
                    .pointerInput(key) {
                        detectTapGestures {
                            haptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                            )
                            if (key == "Tab") onTab() else onSymbol(key)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    key,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * R4A: slide the game in from the right. With [launch] it starts a new
 * game; without it, it brings back the one already running (its own task,
 * singleTask: nothing is reloaded).
 */
private fun showGame(context: android.content.Context, launch: EditorViewModel.GameLaunch?) {
    val intent = android.content.Intent(context, dev.rustdroid.ide.game.GameActivity::class.java)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (launch != null) {
        intent.putExtra(dev.rustdroid.ide.game.GameActivity.EXTRA_LIBRARY, launch.library)
            .putExtra(dev.rustdroid.ide.game.GameActivity.EXTRA_LOG, launch.log)
    }
    val slide = android.app.ActivityOptions.makeCustomAnimation(
        context, R.anim.r4a_slide_in_right, R.anim.r4a_slide_out_left,
    )
    context.startActivity(intent, slide.toBundle())
}


/** R4A: a qué carpeta del proyecto van los archivos importados. */
@Composable
private fun ImportFolderDialog(
    count: Int,
    folders: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_import_title, count)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(folders.size) { i ->
                    val f = folders[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(f) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(RdIcons.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (f.isEmpty()) stringResource(R.string.editor_import_root) else f,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
