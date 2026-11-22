package dev.rustdroid.ide.ui.editor

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
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
    onExport: (ApkExporter.Options) -> Unit,
    onDismiss: () -> Unit,
) {
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
