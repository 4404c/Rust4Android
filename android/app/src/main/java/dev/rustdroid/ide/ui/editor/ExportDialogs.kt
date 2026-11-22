package dev.rustdroid.ide.ui.editor

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.rustdroid.ide.R
import dev.rustdroid.ide.export.ApkExporter
import java.io.File

/** R4A: name, package and version of the APK to export. */
@Composable
fun ExportApkDialog(
    initial: ApkExporter.Options,
    iconFile: File,
    onPickIcon: (android.net.Uri, (Boolean) -> Unit) -> Unit,
    onClearIcon: () -> Unit,
    onExport: (ApkExporter.Options) -> Unit,
    onDismiss: () -> Unit,
) {
    // R4A: launcher icon — bumped after each change to reload the preview
    var iconVersion by remember { mutableStateOf(0) }
    var iconError by remember { mutableStateOf(false) }
    val icon = remember(iconVersion) {
        iconFile.takeIf { it.isFile }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
            ?.asImageBitmap()
    }
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) onPickIcon(uri) { ok -> iconError = !ok; iconVersion++ }
    }
    var name by remember { mutableStateOf(initial.appName) }
    var pkg by remember { mutableStateOf(initial.packageName) }
    var versionName by remember { mutableStateOf(initial.versionName) }
    var versionCode by remember { mutableStateOf(initial.versionCode.toString()) }

    val pkgOk = ApkExporter.isValidPackage(pkg)
    val codeOk = versionCode.toIntOrNull()?.let { it > 0 } == true
    val ok = name.isNotBlank() && pkgOk && codeOk && versionName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_apk_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (icon != null) {
                        Image(
                            icon, contentDescription = stringResource(R.string.export_icon),
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                        )
                    } else {
                        DefaultGameIcon(Modifier.size(56.dp))
                    }
                    Column {
                        Text(stringResource(R.string.export_icon), style = MaterialTheme.typography.labelLarge)
                        Row {
                            // narrow dialog: tight padding, one line each
                            val tight = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                            TextButton(onClick = { pickImage.launch("image/*") }, contentPadding = tight) {
                                Text(stringResource(R.string.export_icon_pick), maxLines = 1, softWrap = false)
                            }
                            if (icon != null) {
                                TextButton(onClick = { onClearIcon(); iconVersion++ }, contentPadding = tight) {
                                    Text(stringResource(R.string.export_icon_clear), maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        if (iconError) {
                            Text(
                                stringResource(R.string.export_icon_error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.export_app_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pkg, onValueChange = { pkg = it.trim() },
                    label = { Text(stringResource(R.string.export_package)) },
                    singleLine = true,
                    isError = !pkgOk,
                    supportingText = { Text(stringResource(R.string.export_package_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = versionName, onValueChange = { versionName = it.trim() },
                    label = { Text(stringResource(R.string.export_version_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = versionCode, onValueChange = { versionCode = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.export_version_code)) },
                    singleLine = true,
                    isError = !codeOk,
                    supportingText = { Text(stringResource(R.string.export_version_code_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    stringResource(R.string.export_key_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ok,
                onClick = {
                    onExport(ApkExporter.Options(name.trim(), pkg, versionCode.toInt(), versionName))
                },
            ) { Text(stringResource(R.string.export_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** The template's own icon (dark rounded square, orange ball). */
@Composable
private fun DefaultGameIcon(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        drawRoundRect(
            androidx.compose.ui.graphics.Color(0xFF281E3C),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.23f),
        )
        drawCircle(androidx.compose.ui.graphics.Color(0xFFFF9800), radius = size.minDimension * 0.23f)
    }
}

/** R4A: the APK is ready — install it here or share it. */
@Composable
fun ExportedApkDialog(apk: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_ready_title)) },
        text = { Text(stringResource(R.string.export_ready_text, apk.absolutePath)) },
        confirmButton = {
            TextButton(onClick = { installApk(context, apk); onDismiss() }) {
                Text(stringResource(R.string.export_install))
            }
        },
        dismissButton = {
            TextButton(onClick = { shareApk(context, apk); onDismiss() }) {
                Text(stringResource(R.string.export_share))
            }
        },
    )
}

private const val APK_MIME = "application/vnd.android.package-archive"

private fun apkUri(context: Context, apk: File) =
    FileProvider.getUriForFile(context, "dev.rustdroid.ide.export", apk)

private fun installApk(context: Context, apk: File) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(apkUri(context, apk), APK_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun shareApk(context: Context, apk: File) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(APK_MIME)
        .putExtra(Intent.EXTRA_STREAM, apkUri(context, apk))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, apk.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
