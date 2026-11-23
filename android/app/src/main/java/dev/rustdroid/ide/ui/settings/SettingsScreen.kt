package dev.rustdroid.ide.ui.settings

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
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
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
    val state by vm.toolchainState.collectAsState()
    val storage by vm.storage.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()

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
            // ---- toolchain ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_toolchain), style = MaterialTheme.typography.titleMedium)
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

            // ---- language (R4A) ----
            LanguageCard()

            // ---- appearance (R4A) ----
            AppearanceCard(onOpenCodeThemes)

            // ---- game signing key (R4A) ----
            SigningKeyCard()

            // ---- storage ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_storage), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    storage.forEach { (label, bytes) -> StorageRow(label, bytes) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.clearCaches() }, enabled = !busy) {
                        Text(stringResource(R.string.settings_clear_caches))
                    }
                }
            }

            // ---- about ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
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
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
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
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    fun toast(id: Int, vararg args: Any) =
        android.widget.Toast.makeText(context, context.getString(id, *args), android.widget.Toast.LENGTH_LONG).show()

    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val key = dev.rustdroid.ide.export.GameSigningKey.loadOrCreate(dir)
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(dev.rustdroid.ide.export.GameSigningKey.backup(dir))
                    } ?: error("no output")
                    dev.rustdroid.ide.export.GameSigningKey.fingerprint(key)
                }.getOrNull()
            }
            if (ok != null) { fingerprint = ok; toast(R.string.signing_saved) } else toast(R.string.signing_save_failed)
        }
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingRestore = uri }

    pendingRestore?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.signing_restore_title)) },
            text = { Text(stringResource(R.string.signing_restore_text)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        val fp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: error("no input")
                                dev.rustdroid.ide.export.GameSigningKey.fingerprint(
                                    dev.rustdroid.ide.export.GameSigningKey.restore(dir, bytes),
                                )
                            }.getOrNull()
                        }
                        if (fp != null) { fingerprint = fp; toast(R.string.signing_restored) } else toast(R.string.signing_restore_failed)
                    }
                }) { Text(stringResource(R.string.signing_restore_confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingRestore = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.signing_title), style = MaterialTheme.typography.titleMedium)
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
                OutlinedButton(onClick = { save.launch("r4a-clave-juegos.r4akey") }) {
                    Text(stringResource(R.string.signing_save))
                }
                OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.signing_restore))
                }
            }
        }
    }
}

/** R4A: appearance / editor toggles. Changes apply right away (AppSettings flows). */
@Composable
private fun AppearanceCard(onOpenCodeThemes: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fileIcons by dev.rustdroid.ide.util.AppSettings.fileIcons.collectAsState()
    val liveCheck by dev.rustdroid.ide.util.AppSettings.liveCheck.collectAsState()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
            ThemeModeRow()
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

/** R4A: fila que lleva a la lista de temas de código (para que Opciones no sea eterna). */
@Composable
private fun CodeThemeRow(onClick: () -> Unit) {
    val current by dev.rustdroid.ide.util.AppSettings.codeTheme.collectAsState()
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
    val mode by dev.rustdroid.ide.util.AppSettings.themeMode.collectAsState()
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
    val colors by dev.rustdroid.ide.util.AppSettings.customColors.collectAsState()
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
private fun SwitchRow(
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
