package dev.rustdroid.ide.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import dev.rustdroid.ide.R
import androidx.compose.ui.res.stringResource
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit

import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ProjectSummary
import dev.rustdroid.ide.projects.RsImport
import dev.rustdroid.ide.ui.components.ConfirmDialog
import dev.rustdroid.ide.ui.components.EmptyState
import dev.rustdroid.ide.ui.components.RdIcons
import dev.rustdroid.ide.ui.components.ferrdroidActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val projects by vm.projects.collectAsState()
    val creating by vm.creating.collectAsState()
    val createLog by vm.createLog.collectAsState()
    val error by vm.error.collectAsState()

    var showNewDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var folderCandidate by remember { mutableStateOf<File?>(null) }
    var adopting by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    // ---- R4A: Ferrdroid, la mascota ----
    val ferrdroidOn by dev.rustdroid.ide.util.AppSettings.ferrdroidVisible.collectAsState()
    val ferrdroid = dev.rustdroid.ide.ui.components.rememberFerrdroidState()
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val tips = remember { res.getStringArray(R.array.ferrdroid_tips).toList() }
    val wakeLines = remember { res.getStringArray(R.array.ferrdroid_wake).toList() }
    val createdMsg = stringResource(R.string.ferrdroid_created)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // saluda una vez por sesión de la app; sin proyectos, explica cómo empezar
        kotlinx.coroutines.delay(400)
        if (projects.isEmpty()) {
            ferrdroid.say(res.getString(R.string.ferrdroid_empty))
        } else if (!FerrdroidGreeting.done) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            ferrdroid.say(
                res.getString(
                    when (hour) {
                        in 6..12 -> R.string.ferrdroid_morning
                        in 13..19 -> R.string.ferrdroid_afternoon
                        else -> R.string.ferrdroid_night
                    },
                ),
            )
        }
        FerrdroidGreeting.done = true
    }
    val ferrdroidHeader: @Composable () -> Unit = {
        if (ferrdroidOn) {
            dev.rustdroid.ide.ui.components.Ferrdroid(
                state = ferrdroid,
                tips = tips,
                wakeLines = wakeLines,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }

    // ---- open folder in place ----
    // Permission first (legacy storage model at targetSdk 28 — see
    // build.gradle.kts), then R4A's own folder picker (FolderPickerDialog):
    // the system one refused ordinary folders on some phones.
    val storageRequiredMsg = stringResource(R.string.home_storage_required)
    val genericFailedMsg = stringResource(R.string.common_failed)
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showFolderPicker = true
        } else {
            vm.reportError(
                storageRequiredMsg,
            )
        }
    }
    // R4A: R4A's own folder picker (the system one refuses some folders
    // "to protect your privacy"); the system picker stays as a fallback
    val openFolderPicker = {
        when (val perm = vm.storagePermissionToRequest()) {
            null -> showFolderPicker = true
            else -> storagePermission.launch(perm)
        }
    }
    if (showFolderPicker) {
        FolderPickerDialog(
            root = vm.storageRoot,
            onPick = { dir ->
                showFolderPicker = false
                vm.useFolder(dir, onReady = { folderCandidate = it }, onError = { vm.reportError(it) })
            },
            onDismiss = { showFolderPicker = false },
        )
    }

    // R4A: chat de Inicio (crear proyectos con la IA), en un cajón desde la derecha
    // el chat de Inicio está siempre: la casilla "Chat IA" del editor solo esconde el del editor
    val homeChats = remember { container.chatStore.home }
    val homeTools = remember { dev.rustdroid.ide.ai.HomeTools(container.projectRepository) }
    androidx.compose.runtime.DisposableEffect(homeChats) {
        homeChats.attach(homeTools)
        homeChats.markVisitStart()
        onDispose { homeChats.detach(homeTools) }
    }
    val chatDrawerState = dev.rustdroid.ide.ui.components.rememberEdgeDrawerState()
    val chatScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.activity.compose.BackHandler(enabled = chatDrawerState.isOpen) {
        chatScope.launch { chatDrawerState.close() }
    }
    dev.rustdroid.ide.ui.components.EdgeDrawer(
        state = chatDrawerState,
        fromEnd = true,
        widthFraction = 0.9f,
        maxSheetWidth = 460.dp,
        edgeEnabled = true,
        drawerContent = {
            dev.rustdroid.ide.ui.chat.ChatDrawerSheet(
                chat = homeChats,
                onClose = { chatScope.launch { chatDrawerState.close() } },
                onOpenFile = { _, _ -> },
                onOpenProject = { ref ->
                    // la charla sigue en el proyecto nuevo
                    val dir = container.projectRepository.resolve(ref)
                    container.chatStore.copyTo(homeChats.current.value, dir, dir.name)
                    homeChats.newChat() // Inicio queda listo para el próximo proyecto
                    chatScope.launch { chatDrawerState.close() }
                    vm.refresh()
                    onOpenProject(ref)
                },
                home = true,
            )
        },
    ) {
    Scaffold(
        modifier = Modifier.ferrdroidActivity(ferrdroid),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "𝚁𝟺𝙰", // 𝚁𝟺𝙰 (R, 4 y A monoespaciadas de Unicode, a pedido)
                        color = MaterialTheme.colorScheme.primary,
                        // sin negrita ni Courier: así se ve la forma propia de esos caracteres
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                        fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp),
                    )
                },
                actions = {
                    // v0.2: the terminal moved OUT of the Home toolbar and
                    // ONTO each project card (plus the editor toolbar) — it
                    // is a per-project tool, not a global menu entry.
                    IconButton(onClick = { chatScope.launch { chatDrawerState.open() } }) {
                        dev.rustdroid.ide.ui.chat.AiBadge(contentDescription = stringResource(R.string.ai_chat_open))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (!creating) Row(verticalAlignment = Alignment.CenterVertically) {
                // R4A: abrir carpeta, abajo junto a Nuevo proyecto
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = openFolderPicker,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Icon(RdIcons.FolderOpen, contentDescription = stringResource(R.string.home_open_folder_cd))
                }
                Spacer(Modifier.width(12.dp))
                ExtendedFloatingActionButton(
                    onClick = { showNewDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.home_new_project)) },
                    // R4A: el verde de la marca vive acá (en Personalizado, el color principal)
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.padding(horizontal = 16.dp)) { ferrdroidHeader() }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EmptyState(
                        icon = RdIcons.Folder,
                        title = stringResource(R.string.home_empty_title),
                        subtitle = stringResource(R.string.home_empty_subtitle),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = openFolderPicker) {
                        Icon(RdIcons.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_open_folder))
                    }
                }
            }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "ferrdroid") { ferrdroidHeader() }
                items(projects, key = { it.dir.absolutePath }) { project ->
                    ProjectCard(
                        project = project,
                        onClick = {
                            onOpenProject(container.projectRepository.refOf(project.dir))
                        },
                        onOpenTerminal = {
                            onOpenTerminal(container.projectRepository.refOf(project.dir))
                        },
                        onDelete = { deleteTarget = project },
                        onRename = { if (!project.external) renameTarget = project },
                    )
                }
            }
        }
    }
    } // chat de Inicio

    if (showNewDialog) {
        NewProjectDialog(
            busy = creating,
            log = createLog,
            onCreate = { name, isLib, game ->
                vm.create(name, isLib, game) { ok ->
                    if (ok) {
                        showNewDialog = false
                        ferrdroid.cheer(2500)
                        ferrdroid.say(createdMsg)
                    }
                }
            },
            onDismiss = { if (!creating) showNewDialog = false },
        )
    }

    deleteTarget?.let { target ->
        if (target.external) {
            // external folders belong to the user — removing the entry must
            // never delete the underlying files
            ConfirmDialog(
                title = stringResource(R.string.home_remove_title, target.name),
                text = stringResource(R.string.home_remove_body),
                confirmLabel = stringResource(R.string.common_remove),
                onConfirm = { vm.removeExternal(target); deleteTarget = null },
                onDismiss = { deleteTarget = null },
            )
        } else {
            ConfirmDialog(
                title = stringResource(R.string.home_delete_title, target.name),
                text = stringResource(R.string.home_delete_body),
                confirmLabel = stringResource(R.string.common_delete),
                onConfirm = { vm.delete(target); deleteTarget = null },
                onDismiss = { deleteTarget = null },
            )
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.name,
            onRename = { newName ->
                vm.rename(target, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    folderCandidate?.let { dir ->
        AdoptFolderDialog(
            dir = dir,
            busy = adopting,
            onAdopt = { withCargo, packageName ->
                adopting = true
                vm.adoptFolder(dir, withCargo, packageName) { ref, err ->
                    adopting = false
                    folderCandidate = null
                    if (ref != null) onOpenProject(ref) else vm.reportError(err ?: genericFailedMsg)
                }
            },
            onDismiss = { if (!adopting) folderCandidate = null },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title = { Text(stringResource(R.string.common_error)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("OK") } },
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onClick: () -> Unit,
    onOpenTerminal: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (project.external) {
                        Icon(
                            RdIcons.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(16.dp).width(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                Text(
                    stringResource(R.string.home_modified, fmt.format(Date(project.lastModifiedMs))) +
                        when {
                            project.dependencyCount >= 0 -> stringResource(R.string.home_deps_count, project.dependencyCount)
                            project.external -> stringResource(R.string.home_no_manifest)
                            else -> stringResource(R.string.home_manifest_unreadable)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // R4A: every project shows where it lives (folders opened in
                // place AND projects inside R4A's own storage)
                Text(
                    project.dir.path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!project.external) {
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.home_rename_cd), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // v0.2: per-project terminal — the session starts IN this
            // project's directory (cargo run / fetch / test with no cd).
            IconButton(onClick = onOpenTerminal) {
                Icon(
                    RdIcons.Terminal,
                    contentDescription = stringResource(R.string.home_open_terminal_cd, project.name),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = if (project.external) stringResource(R.string.home_remove_cd) else stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Landing dialog for a folder picked to be opened IN PLACE: the folder is
 * never copied or moved — RustDroid edits it where it lives. Folders
 * without a manifest can be turned into a cargo project right there
 * (Cargo.toml + src/main.rs written into the folder, only when missing),
 * or opened for plain editing.
 */
@Composable
private fun AdoptFolderDialog(
    dir: File,
    busy: Boolean,
    onAdopt: (withCargo: Boolean, packageName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasManifest = File(dir, "Cargo.toml").isFile
    var name by remember(dir) {
        mutableStateOf(RsImport.suggestProjectName(dir.name + ".rs"))
    }
    val nameOk = name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.home_open_in_place_title, dir.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.home_open_in_place_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    dir.path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!hasManifest) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        enabled = !busy,
                        label = { Text(stringResource(R.string.home_package_name)) },
                        isError = name.isNotEmpty() && !nameOk,
                        supportingText = {
                            if (name.isNotEmpty() && !nameOk) {
                                Text(stringResource(R.string.common_name_rule))
                            }
                        },
                    )
                }
                if (busy) {
                    Text(stringResource(R.string.home_opening), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (hasManifest) {
                TextButton(
                    onClick = { onAdopt(false, name) },
                    enabled = !busy,
                ) { Text(stringResource(R.string.common_open)) }
            } else {
                TextButton(
                    onClick = { onAdopt(true, name) },
                    enabled = !busy && nameOk,
                ) { Text(stringResource(R.string.home_add_manifest)) }
            }
        },
        dismissButton = {
            if (!hasManifest) {
                // plain-folder mode: edit files, no cargo scaffold
                TextButton(
                    onClick = { onAdopt(false, name) },
                    enabled = !busy,
                ) { Text(stringResource(R.string.home_just_edit)) }
            } else {
                TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun NewProjectDialog(
    busy: Boolean,
    log: String?,
    onCreate: (String, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var isLib by remember { mutableStateOf(false) }
    var isGame by remember { mutableStateOf(false) }
    val nameOk = name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_new_cargo_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.common_name)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = name.isNotEmpty() && !nameOk,
                    supportingText = {
                        if (name.isNotEmpty() && !nameOk) Text(stringResource(R.string.common_name_rule))
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isLib, onCheckedChange = { isLib = it; if (it) isGame = false }, enabled = !busy)
                    Text(stringResource(R.string.home_lib_checkbox))
                }
                // R4A: game template (macroquad), runs as an app
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isGame, onCheckedChange = { isGame = it; if (it) isLib = false }, enabled = !busy)
                    Text(stringResource(R.string.home_game_checkbox))
                }
                if (busy) {
                    Text(stringResource(R.string.home_running_cargo_new), style = MaterialTheme.typography.bodySmall)
                }
                if (log != null) {
                    Text(
                        log.takeLast(800),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 10,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, isLib, isGame) },
                enabled = !busy && nameOk,
            ) { Text(stringResource(R.string.common_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun RenameDialog(initial: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val nameOk = name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = name.isNotEmpty() && !nameOk,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = nameOk) { Text(stringResource(R.string.home_rename_cd)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** R4A: Ferrdroid saluda una sola vez por sesión de la app (no cada vez que volvés al inicio). */
private object FerrdroidGreeting { var done = false }
