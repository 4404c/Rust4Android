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

import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    container: AppContainer,
    projectName: String,
    onOpenDeps: (String) -> Unit,
    onOpenTerminal: (String) -> Unit = {},
    initialFile: String? = null,
    onOpenSettings: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    val vm: EditorViewModel = viewModel(
        key = "editor-$projectName",
        factory = EditorViewModel.factory(container, projectName, initialFile),
    )
    val tabs by vm.tabs.collectAsState()
    val activeIndex by vm.activeIndex.collectAsState()
    val activeText by vm.activeText.collectAsState()
    val tree by vm.tree.collectAsState()
    val running by vm.running.collectAsState()
    val problems by vm.problems.collectAsState()
    val lastResult by vm.lastResult.collectAsState()
    val bottomTab by vm.bottomTab.collectAsState()
    val jump by vm.jump.collectAsState()
    val showCloseConfirm by vm.showCloseConfirm.collectAsState()
    val allFiles by vm.treeAllFiles.collectAsState()
    val consoleLines by vm.console.lines.collectAsState()

    // new-file dialog state: name lives locally; success/error come from the
    // VM (one-shot createdFile closes the dialog, createFileError shows inline)
    val createdFile by vm.createdFile.collectAsState()
    val createFileError by vm.createFileError.collectAsState()
    var showNewFile by remember { mutableStateOf(false) }

    // delete-file dialog state: the row long-press picks the target; the
    // VM's one-shot deletedFile closes the dialog, deleteFileError shows
    // inline (e.g. the root Cargo.toml guard)
    val deletedFile by vm.deletedFile.collectAsState()
    val deleteFileError by vm.deleteFileError.collectAsState()
    var pendingDelete by remember { mutableStateOf<FileNode?>(null) }

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

    val dark = isSystemInDarkTheme()
    val palette = LocalEditorPalette.current

    // IME-aware layout: editor focus + visible keyboard => hide the console
    // panel so the script gets the whole space above the IME (see bottom
    // panel below). Track View-level focus from sora-editor.
    var editorFocused by remember { mutableStateOf(false) }
    // R4A: folders folded in the Files tree (relative paths)
    var collapsedDirs by remember { mutableStateOf(emptySet<String>()) }
    // R4A: the sora editor view, so the symbol bar can type into it
    var codeEditor by remember { mutableStateOf<io.github.rosemoe.sora.widget.CodeEditor?>(null) }

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
    LaunchedEffect(drawerState) {
        androidx.compose.runtime.snapshotFlow { drawerState.isOpen }
            .collect { open -> if (open) vm.refreshTree() }
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
    androidx.activity.compose.BackHandler {
        if (drawerState.isOpen) {
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
    EdgeDrawer(
        state = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(RdIcons.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.editor_files), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { showNewFile = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.editor_new_file),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { vm.toggleAllFiles() }) {
                            Text(if (allFiles) stringResource(R.string.editor_sources) else stringResource(R.string.editor_all_files))
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
                                onDelete = { pendingDelete = row.node },
                            )
                        }
                    }
                    // R4A: project actions pinned to the bottom of the drawer
                    androidx.compose.material3.HorizontalDivider()
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(stringResource(R.string.common_settings), fontSize = 12.sp) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        selected = false,
                        onClick = {
                            drawerScope.launch { drawerState.close() }
                            onOpenSettings()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
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
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
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
                    title = { Text(projectName.substringAfterLast('/')) },
                    actions = {
                        TextButton(onClick = { onOpenDeps(projectName) }) { Text(stringResource(R.string.editor_deps)) }
                        // v0.2: per-project terminal from where the script is
                        // written — the session starts IN this project's
                        // directory (cargo run / fetch / test with no cd).
                        IconButton(onClick = { onOpenTerminal(projectName) }) {
                            Icon(RdIcons.Terminal, contentDescription = stringResource(R.string.editor_open_terminal_cd))
                        }
                        if (running) {
                            IconButton(onClick = { vm.stop() }) {
                                Icon(RdIcons.Stop, contentDescription = stringResource(R.string.editor_stop), tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(onClick = { vm.run() }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.editor_run), tint = MaterialTheme.colorScheme.primary)
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
                            .height(30.dp)
                            .horizontalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        tabs.forEachIndexed { i, tab ->
                            val selected = i == activeIndex
                            val indicator = MaterialTheme.colorScheme.primary
                            val activeBg = MaterialTheme.colorScheme.surface
                            Row(
                                Modifier
                                    .height(30.dp)
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
                                    .padding(horizontal = 10.dp),
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
                                Text(
                                    tab.relativePath.substringAfterLast('/'),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
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
                            onFocusChanged = { editorFocused = it },
                            onEditorReady = { codeEditor = it },
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
                val hideBottom = imeVisible && editorFocused
                val errorCount = problems.count { it.severity == Severity.ERROR }
                val warningCount = problems.count { it.severity == Severity.WARNING }
                if (!hideBottom) {
                    Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth()) {
                        TabRow(selectedTabIndex = if (bottomTab == EditorViewModel.BottomTab.CONSOLE) 0 else 1) {
                            Tab(
                                selected = bottomTab == EditorViewModel.BottomTab.CONSOLE,
                                onClick = { vm.selectBottomTab(EditorViewModel.BottomTab.CONSOLE) },
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
                                selected = bottomTab == EditorViewModel.BottomTab.PROBLEMS,
                                onClick = { vm.selectBottomTab(EditorViewModel.BottomTab.PROBLEMS) },
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
                        Box(
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

    if (showNewFile) {
        NewFileDialog(
            error = createFileError,
            onCreate = { vm.createFile(it) },
            onDismiss = {
                vm.clearNewFileSignals()
                showNewFile = false
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
    error: String?,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_new_file)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.editor_new_file_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.editor_file_name)) },
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
 * a folder (R4A); long-press offers deletion via [onDelete]. The drawer
 * sheet is pure Compose, so combinedClickable's long-press does not fight
 * the sora-editor interop view (which only ever sits under the CLOSED
 * drawer — see the EdgeDrawer note above).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    row: TreeRow,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val node = row.node
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val col = TreeColumn
    val startPad = 10.dp
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onDelete)
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
            StdinBar(onSend = onSend)
        }
    }
}

@Composable
private fun StdinBar(onSend: (String) -> Unit) {
    val palette = LocalEditorPalette.current
    var input by remember { mutableStateOf("") }

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
            modifier = Modifier.weight(1f),
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
