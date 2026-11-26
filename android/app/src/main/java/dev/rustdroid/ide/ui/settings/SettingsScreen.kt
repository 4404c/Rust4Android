package dev.rustdroid.ide.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import dev.rustdroid.ide.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.SegmentedButton
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ToolchainState
import dev.rustdroid.ide.toolchain.ToolchainDistro
import dev.rustdroid.ide.ui.components.CheckRow
import dev.rustdroid.ide.ui.components.StorageRow

// Same green CheckRow uses for PASS — readable on both light and dark.
private val HealthyGreen = Color(0xFF4CAF50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenLicenses: () -> Unit = {},
    onOpenCodeThemes: () -> Unit = {},
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by vm.toolchainState.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    LaunchedEffect(Unit) { vm.refreshStorage() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.common_settings)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // R4A: grupos con título, lo más usado primero
            SettingsHeader(stringResource(R.string.settings_group_ai))
            AiSettingsCard(container)

            SettingsHeader(stringResource(R.string.settings_group_editor))
            EditorCard(onOpenCodeThemes)

            SettingsHeader(stringResource(R.string.settings_group_security))
            PhoneAccessCard(container)

            SettingsHeader(stringResource(R.string.settings_group_general))
            LanguageCard()
            AppearanceCard()

            SettingsHeader(stringResource(R.string.settings_group_export))
            SigningKeyCard()

            SettingsHeader(stringResource(R.string.settings_group_system))
            // ---- toolchain ----
            ExpandableCard(stringResource(R.string.settings_toolchain)) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state) {
                        is ToolchainState.Ready -> {
                            Text(
                                stringResource(R.string.settings_healthy),
                                style = MaterialTheme.typography.bodyMedium,
                                color = HealthyGreen,
                            )
                            Text(
                                (state as ToolchainState.Ready).rustcVersion,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                (state as ToolchainState.Ready).cargoVersion,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "distro: ${ToolchainDistro.RELEASE_TAG} · ${ToolchainDistro.RUST_VERSION} · aarch64-linux-android",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is ToolchainState.Verifying -> {
                            Text(stringResource(R.string.settings_reverifying), style = MaterialTheme.typography.bodySmall)
                            (state as ToolchainState.Verifying).checks.forEach { CheckRow(it) }
                        }
                        is ToolchainState.Failed -> {
                            Text(
                                "FAILED (${(state as ToolchainState.Failed).stage}): " +
                                    (state as ToolchainState.Failed).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> Text(stringResource(R.string.settings_not_installed), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { vm.reverify() }, enabled = !busy) {
                            Text(stringResource(R.string.settings_reverify))
                        }
                        Button(onClick = { vm.uninstall() }, enabled = !busy) {
                            Text(stringResource(R.string.settings_uninstall))
                        }
                    }
                    Text(
                        stringResource(R.string.settings_reverify_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- storage ----
            ExpandableCard(stringResource(R.string.settings_storage)) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Spacer(Modifier.height(4.dp))
                    storage.forEach { (label, bytes) -> StorageRow(label, bytes) }
                    Spacer(Modifier.height(8.dp))
                    // R4A (auditoría 2, E4): antes borraba al toque, sin preguntar
                    var confirmClear by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = !busy) {
                        Text(stringResource(R.string.settings_clear_caches))
                    }
                    if (confirmClear) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { confirmClear = false },
                            title = { Text(stringResource(R.string.settings_clear_caches_title)) },
                            text = { Text(stringResource(R.string.settings_clear_caches_text)) },
                            confirmButton = {
                                TextButton(onClick = { confirmClear = false; vm.clearCaches() }) {
                                    Text(stringResource(R.string.common_delete))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
                            },
                        )
                    }
                }
            }

            // ---- about ----
            ExpandableCard(stringResource(R.string.settings_about)) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.settings_about_app), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.settings_about_toolchain, ToolchainDistro.RUST_VERSION),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_about_editor),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Plan §7.5: the full per-component license surface —
                    // including the REQUIRED BusyBox GPL-2.0 source offer.
                    OutlinedButton(onClick = onOpenLicenses) {
                        Text(stringResource(R.string.settings_licenses))
                    }
                }
            }
        }
    }
}

/**
 * R4A: in-app language picker. Saving the choice and recreating the activity
 * re-runs MainActivity.attachBaseContext, which applies it (AppLanguage).
 */
@Composable
private fun LanguageCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val current = remember { dev.rustdroid.ide.util.AppLanguage.get(context) }
    ExpandableCard(stringResource(R.string.settings_language)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                dev.rustdroid.ide.util.AppLanguage.Option.ES to R.string.lang_es,
                dev.rustdroid.ide.util.AppLanguage.Option.EN to R.string.lang_en,
            ).forEach { (option, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option == current,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = {
                                if (option != current) {
                                    dev.rustdroid.ide.util.AppLanguage.set(context, option)
                                    (context as? android.app.Activity)?.recreate()
                                }
                            },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = option == current, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                stringResource(R.string.settings_language_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * R4A: the key that signs exported games. Android only installs an update
 * signed with the same key, so it can be saved to a file and restored
 * (after reinstalling R4A, or on another phone).
 */
@Composable
private fun SigningKeyCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dir = remember { java.io.File(context.filesDir, "signing") }
    var fingerprint by remember {
        mutableStateOf(
            if (dev.rustdroid.ide.export.GameSigningKey.exists(dir))
                runCatching { dev.rustdroid.ide.export.GameSigningKey.fingerprint(dev.rustdroid.ide.export.GameSigningKey.loadOrCreate(dir)) }.getOrNull()
            else null,
        )
    }
    // R4A (auditoría M6): el respaldo .r4akey va cifrado con una contraseña
    var pendingRestore by remember { mutableStateOf<Pair<ByteArray, Boolean>?>(null) }
    var askSavePassword by remember { mutableStateOf(false) }
    var savePassword by remember { mutableStateOf<CharArray?>(null) }
    fun toast(id: Int, vararg args: Any) =
        android.widget.Toast.makeText(context, context.getString(id, *args), android.widget.Toast.LENGTH_LONG).show()

    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val password = savePassword
        savePassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val key = dev.rustdroid.ide.export.GameSigningKey.loadOrCreate(dir)
                    val bytes = dev.rustdroid.ide.export.GameSigningKey.backup(dir, password)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no output")
                    dev.rustdroid.ide.export.GameSigningKey.fingerprint(key)
                }.getOrNull()
            }
            java.util.Arrays.fill(password, ' ')
            if (ok != null) { fingerprint = ok; toast(R.string.signing_saved) } else toast(R.string.signing_save_failed)
        }
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) toast(R.string.signing_restore_failed)
            else pendingRestore = bytes to dev.rustdroid.ide.export.GameSigningKey.isEncrypted(bytes)
        }
    }

    if (askSavePassword) {
        var pw1 by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        val minLen = dev.rustdroid.ide.export.GameSigningKey.MIN_PASSWORD
        val tooShort = pw1.length < minLen
        val mismatch = pw2.isNotEmpty() && pw1 != pw2
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askSavePassword = false },
            title = { Text(stringResource(R.string.signing_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.signing_password_text), style = MaterialTheme.typography.bodySmall)
                    PasswordField(pw1, { pw1 = it }, stringResource(R.string.signing_password_hint))
                    PasswordField(pw2, { pw2 = it }, stringResource(R.string.signing_password_repeat))
                    if (pw1.isNotEmpty() && tooShort) ErrorLine(stringResource(R.string.signing_password_short, minLen))
                    else if (mismatch) ErrorLine(stringResource(R.string.signing_password_mismatch))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !tooShort && pw1 == pw2,
                    onClick = {
                        savePassword = pw1.toCharArray()
                        askSavePassword = false
                        save.launch("r4a-clave-juegos.r4akey")
                    },
                ) { Text(stringResource(R.string.signing_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { askSavePassword = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    pendingRestore?.let { pending ->
        val (bytes, encrypted) = pending
        var pw by remember(pending) { mutableStateOf("") }
        var wrong by remember(pending) { mutableStateOf(false) }
        var working by remember(pending) { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!working) pendingRestore = null },
            title = { Text(stringResource(R.string.signing_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.signing_restore_text))
                    if (encrypted) {
                        PasswordField(pw, { pw = it; wrong = false }, stringResource(R.string.signing_password_hint))
                        if (wrong) ErrorLine(stringResource(R.string.signing_wrong_password))
                    } else {
                        Text(
                            stringResource(R.string.signing_old_backup),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !working && (!encrypted || pw.isNotEmpty()),
                    onClick = {
                        working = true
                        val password = pw.toCharArray()
                        scope.launch {
                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    dev.rustdroid.ide.export.GameSigningKey.fingerprint(
                                        dev.rustdroid.ide.export.GameSigningKey.restore(dir, bytes, password.takeIf { encrypted }),
                                    )
                                }
                            }
                            java.util.Arrays.fill(password, ' ')
                            working = false
                            val fp = result.getOrNull()
                            when {
                                fp != null -> { fingerprint = fp; pendingRestore = null; toast(R.string.signing_restored) }
                                result.exceptionOrNull() is dev.rustdroid.ide.export.GameSigningKey.WrongPasswordException -> wrong = true
                                else -> { pendingRestore = null; toast(R.string.signing_restore_failed) }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.signing_restore_confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(enabled = !working, onClick = { pendingRestore = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    ExpandableCard(stringResource(R.string.signing_title)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                fingerprint?.let { stringResource(R.string.signing_current, it) }
                    ?: stringResource(R.string.signing_none),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.signing_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { askSavePassword = true }) {
                    Text(stringResource(R.string.signing_save))
                }
                OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.signing_restore))
                }
            }
        }
    }
}

/** R4A (auditoría M6): campo de contraseña (oculta). */
@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorLine(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

/** R4A: appearance / editor toggles. Changes apply right away (AppSettings flows). */
@Composable
private fun AppearanceCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ferrdroidVisible by dev.rustdroid.ide.util.AppSettings.ferrdroidVisible.collectAsStateWithLifecycle()
    ExpandableCard(stringResource(R.string.settings_appearance)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeModeRow()
            SwitchRow(
                title = stringResource(R.string.settings_ferrdroid),
                description = stringResource(R.string.settings_ferrdroid_desc),
                checked = ferrdroidVisible,
            ) { dev.rustdroid.ide.util.AppSettings.setFerrdroidVisible(context, it) }
            // R4A: el tour de Ferrdroid de nuevo: vuelve atrás y arranca en Inicio
            val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        dev.rustdroid.ide.util.AppSettings.setTourSeen(context, false)
                        back?.onBackPressedDispatcher?.onBackPressed()
                    }
                    .padding(vertical = 10.dp),
            ) {
                Text(stringResource(R.string.settings_tour), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_tour_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * R4A (alcance de archivos): qué pueden tocar la IA y los programas.
 * "Solo el proyecto" = R4A sin permiso de almacenamiento (por defecto).
 */
@Composable
private fun PhoneAccessCard(container: AppContainer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val phoneAccess by dev.rustdroid.ide.util.AppSettings.phoneAccess.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmAccess by remember { mutableStateOf(false) }
    var copyQuestion by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<dev.rustdroid.ide.ui.home.PhoneAccess.GoSafeResult?>(null) }
    var denied by remember { mutableStateOf(false) }
    // se vuelve a mirar al volver de los ajustes de Android
    var hasPermission by remember { mutableStateOf(dev.rustdroid.ide.ui.home.PhoneAccess.hasStoragePermission(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermission = dev.rustdroid.ide.ui.home.PhoneAccess.hasStoragePermission(context)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) dev.rustdroid.ide.util.AppSettings.setPhoneAccess(context, true) else denied = true
    }
    fun goSafe(copy: Boolean) {
        busy = true
        scope.launch {
            result = dev.rustdroid.ide.ui.home.PhoneAccess.goSafe(container, copy)
            busy = false
            hasPermission = dev.rustdroid.ide.ui.home.PhoneAccess.hasStoragePermission(context)
        }
    }

    ExpandableCard(stringResource(R.string.phone_access_title)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.phone_access_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PhoneAccessOption(
                selected = !phoneAccess,
                title = stringResource(R.string.phone_access_safe),
                description = stringResource(R.string.phone_access_safe_desc),
            ) {
                if (!phoneAccess || busy) return@PhoneAccessOption
                val names = dev.rustdroid.ide.ui.home.PhoneAccess.readableExternal(container).map { it.name }
                if (names.isEmpty()) goSafe(copy = false) else copyQuestion = names
            }
            PhoneAccessOption(
                selected = phoneAccess,
                title = stringResource(R.string.phone_access_phone),
                description = stringResource(R.string.phone_access_phone_desc),
            ) {
                if (!phoneAccess && !busy) confirmAccess = true
            }
            if (!phoneAccess && hasPermission) {
                // eligió modo seguro pero el permiso todavía está (falta cerrar R4A)
                Text(
                    stringResource(R.string.phone_access_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { goSafe(copy = false) }, enabled = !busy) {
                    Text(stringResource(R.string.phone_access_apply_now))
                }
            }
            Text(
                stringResource(R.string.phone_access_ai_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmAccess) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmAccess = false },
            title = { Text(stringResource(R.string.phone_access_confirm_title)) },
            text = { Text(stringResource(R.string.phone_access_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAccess = false
                    if (dev.rustdroid.ide.ui.home.PhoneAccess.hasStoragePermission(context)) {
                        dev.rustdroid.ide.util.AppSettings.setPhoneAccess(context, true)
                    } else {
                        permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }) { Text(stringResource(R.string.phone_access_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAccess = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    if (denied) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { denied = false },
            text = { Text(stringResource(R.string.phone_access_denied)) },
            confirmButton = {
                TextButton(onClick = { denied = false; dev.rustdroid.ide.ui.home.PhoneAccess.openAppSettings(context) }) {
                    Text(stringResource(R.string.phone_access_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { denied = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    copyQuestion?.let { names ->
        dev.rustdroid.ide.ui.home.SafeModeQuestionDialog(
            externalNames = names,
            onSafe = { copyQuestion = null; goSafe(copy = true) },
            onKeepAccess = { copyQuestion = null },
            onDismiss = { copyQuestion = null },
        )
    }
    if (busy) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            text = { Text(stringResource(R.string.phone_access_copying)) },
            confirmButton = {},
        )
    }
    result?.let { dev.rustdroid.ide.ui.home.SafeModeResultDialog(it) { result = null } }
}

@Composable
private fun PhoneAccessOption(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** R4A: cosas del editor: tema de código, íconos, revisión en vivo. */
@Composable
private fun EditorCard(onOpenCodeThemes: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fileIcons by dev.rustdroid.ide.util.AppSettings.fileIcons.collectAsStateWithLifecycle()
    val liveCheck by dev.rustdroid.ide.util.AppSettings.liveCheck.collectAsStateWithLifecycle()
    ExpandableCard(stringResource(R.string.settings_editor_card)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CodeThemeRow(onOpenCodeThemes)
            SwitchRow(
                title = stringResource(R.string.settings_file_icons),
                description = stringResource(R.string.settings_file_icons_desc),
                checked = fileIcons,
            ) { dev.rustdroid.ide.util.AppSettings.setFileIcons(context, it) }
            SwitchRow(
                title = stringResource(R.string.settings_live_check),
                description = stringResource(R.string.settings_live_check_desc),
                checked = liveCheck,
            ) { dev.rustdroid.ide.util.AppSettings.setLiveCheck(context, it) }
        }
    }
}

/** R4A: título de un grupo de opciones (como en los ajustes de Android). */
@Composable
private fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** R4A: fila que lleva a la lista de temas de código (para que Opciones no sea eterna). */
@Composable
private fun CodeThemeRow(onClick: () -> Unit) {
    val current by dev.rustdroid.ide.util.AppSettings.codeTheme.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_code_theme), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(codeThemeName(current)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** R4A: tema de la app — Sistema / Claro / Oscuro (se aplica al instante). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mode by dev.rustdroid.ide.util.AppSettings.themeMode.collectAsStateWithLifecycle()
    val options = listOf(
        dev.rustdroid.ide.util.AppSettings.ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        dev.rustdroid.ide.util.AppSettings.ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        dev.rustdroid.ide.util.AppSettings.ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
        dev.rustdroid.ide.util.AppSettings.ThemeMode.CUSTOM to stringResource(R.string.settings_theme_custom),
    )
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        // Estilo de botones segmentados de Material, pero cada botón con el ancho
        // de su texto: con 4 iguales "Personalizado" no entraba.
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        ) {
            options.forEachIndexed { i, (value, label) ->
                val selected = mode == value
                if (i > 0) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline),
                    )
                }
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .weight(label.length * 7f + (if (selected) 24f else 0f) + 14f)
                        .fillMaxHeight()
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .selectable(
                            selected = selected,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { dev.rustdroid.ide.util.AppSettings.setThemeMode(context, value) },
                        ),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (selected) {
                            Icon(
                                androidx.compose.material.icons.Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        if (mode == dev.rustdroid.ide.util.AppSettings.ThemeMode.CUSTOM) CustomColorsEditor()
    }
}

/** R4A: los 4 colores del tema Personalizado; tocar uno abre el selector. */
@Composable
private fun CustomColorsEditor() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors by dev.rustdroid.ide.util.AppSettings.customColors.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) } // 0 principal, 1 secundario, 2 fondo, 3 texto
    val labels = listOf(
        stringResource(R.string.custom_primary) to stringResource(R.string.custom_primary_desc),
        stringResource(R.string.custom_secondary) to stringResource(R.string.custom_secondary_desc),
        stringResource(R.string.custom_background) to stringResource(R.string.custom_background_desc),
        stringResource(R.string.custom_text) to stringResource(
            if (colors.text == null) R.string.custom_text_auto_desc else R.string.custom_text_desc,
        ),
    )
    // el texto automático se muestra con el color que realmente se usa
    val effectiveText = dev.rustdroid.ide.ui.theme.CustomScheme.textColor(colors).toArgb()
    val values = listOf(colors.primary, colors.secondary, colors.background, colors.text ?: effectiveText)
    Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
        labels.forEachIndexed { i, (title, desc) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { editing = i }
                    .padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(36.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Color(values[i]))
                        .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (i == 3 && colors.text == null) stringResource(R.string.custom_text_auto) else values[i].toHex(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    editing?.let { i ->
        ColorPickerDialog(
            title = labels[i].first,
            initial = values[i],
            onDismiss = { editing = null },
            onAuto = if (i == 3) {
                {
                    dev.rustdroid.ide.util.AppSettings.setCustomColors(context, colors.copy(text = null))
                    editing = null
                }
            } else null,
            onPick = { picked ->
                val next = when (i) {
                    0 -> colors.copy(primary = picked)
                    1 -> colors.copy(secondary = picked)
                    2 -> colors.copy(background = picked)
                    else -> colors.copy(text = picked)
                }
                dev.rustdroid.ide.util.AppSettings.setCustomColors(context, next)
                editing = null
            },
        )
    }
}

/** One setting: title + description + switch; the whole row toggles. */
@Composable
internal fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = onChange,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
}
