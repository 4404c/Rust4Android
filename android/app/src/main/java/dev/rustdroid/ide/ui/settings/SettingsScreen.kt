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
fun SettingsScreen(container: AppContainer, onOpenLicenses: () -> Unit = {}) {
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
            AppearanceCard()

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

/** R4A: appearance / editor toggles. Changes apply right away (AppSettings flows). */
@Composable
private fun AppearanceCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fileIcons by dev.rustdroid.ide.util.AppSettings.fileIcons.collectAsState()
    val liveCheck by dev.rustdroid.ide.util.AppSettings.liveCheck.collectAsState()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
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
