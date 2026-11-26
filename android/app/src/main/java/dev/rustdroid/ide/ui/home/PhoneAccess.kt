package dev.rustdroid.ide.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.rustdroid.ide.R
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.projects.ProjectLeftovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * R4A (alcance de archivos, `memory-bank/alcance-archivos.md`): el modo
 * seguro es "R4A sin permiso de almacenamiento". Sin ese permiso, Android no
 * deja que nada de lo que R4A corre (cargo, build.rs, programas, juegos) lea
 * o escriba la memoria del celular. Acá: el permiso, copiar a R4A los
 * proyectos que estaban afuera, y sacar el permiso.
 */
object PhoneAccess {

    /** Lo que da acceso a la memoria compartida (con targetSdk 28, WRITE trae los otros). */
    private val STORAGE = buildList {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_IMAGES)
    }

    fun hasStoragePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** Proyectos abiertos desde afuera de R4A que hoy se pueden leer (los que se pueden copiar). */
    fun readableExternal(container: AppContainer): List<File> =
        container.projectRepository.registeredExternal().filter { it.isDirectory && it.canRead() }

    class CopyResult(val copied: Int, val failed: List<String>)

    /**
     * Copia a R4A cada proyecto de afuera que se pueda leer. Las charlas, el
     * ícono de exportación y sus datos pasan al proyecto nuevo. Los originales
     * no se tocan.
     */
    suspend fun copyExternalsIntoR4A(container: AppContainer): CopyResult = withContext(Dispatchers.IO) {
        val repo = container.projectRepository
        var copied = 0
        val failed = ArrayList<String>()
        for (dir in readableExternal(container)) {
            runCatching {
                val oldCanonical = ProjectLeftovers.canonical(dir)
                val copy = repo.copyIntoR4A(dir)
                val newCanonical = ProjectLeftovers.canonical(copy)
                withContext(Dispatchers.Main) { container.chatStore.forget(oldCanonical) }
                ProjectLeftovers.move(container.context.filesDir, oldCanonical, newCanonical)
                movePrefs(container.context, oldCanonical, newCanonical)
                copied++
            }.onFailure { failed += dir.name }
        }
        CopyResult(copied, failed)
    }

    /**
     * Saca el permiso de almacenamiento. Android 13+: R4A lo devuelve sola y
     * se aplica cuando se cierra (true). Antes de Android 13 no hay forma desde
     * la app (false): hay que sacarlo en los ajustes de Android.
     */
    fun revokeStorage(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            context.revokeSelfPermissionsOnKill(STORAGE.filter {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            })
            true
        }.getOrDefault(false)
    }

    /** Cómo terminó pasar a modo seguro. */
    sealed interface GoSafeResult {
        /** No se pudo copiar alguno: no se cambió nada (sigue el acceso al celular). */
        data class CopyFailed(val names: List<String>) : GoSafeResult
        /** Modo seguro; [automatic] como en [revokeStorage]; null = no había permiso que sacar. */
        data class Done(val copied: CopyResult, val automatic: Boolean?) : GoSafeResult
    }

    /**
     * Pasa a modo seguro: copia a R4A los proyectos de afuera ([copy]), guarda
     * la opción y saca el permiso. Si una copia falla no cambia nada, para que
     * ningún proyecto quede sin poder abrirse.
     */
    suspend fun goSafe(container: AppContainer, copy: Boolean): GoSafeResult {
        val ctx = container.context
        val copied = if (copy) copyExternalsIntoR4A(container) else CopyResult(0, emptyList())
        if (copied.failed.isNotEmpty()) return GoSafeResult.CopyFailed(copied.failed)
        dev.rustdroid.ide.util.AppSettings.setPhoneAccess(ctx, false)
        val automatic = if (hasStoragePermission(ctx)) revokeStorage(ctx) else null
        return GoSafeResult.Done(copied, automatic)
    }

    /** Cierra R4A del todo (todos sus procesos, también el del juego), para que el cambio se aplique. */
    fun closeApp(activity: Activity) {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val me = android.os.Process.myPid()
        activity.finishAffinity()
        am.runningAppProcesses?.forEach { if (it.pid != me) android.os.Process.killProcess(it.pid) }
        android.os.Process.killProcess(me)
    }

    /** La pantalla de Android con los permisos de R4A. */
    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Datos guardados con la ruta del proyecto como clave. [newCanonical] null =
     * se borran; si no, pasan a la ruta nueva. Exportar: "<ruta>|campo".
     * Borradores del chat: la clave es la ruta del archivo de la charla.
     */
    fun movePrefs(ctx: Context, oldCanonical: String, newCanonical: String?) {
        val export = ctx.getSharedPreferences("r4a_export", Context.MODE_PRIVATE)
        val e = export.edit()
        for ((k, v) in export.all) {
            if (!k.startsWith("$oldCanonical|")) continue
            e.remove(k)
            if (newCanonical != null) {
                val nk = newCanonical + k.removePrefix(oldCanonical)
                when (v) {
                    is String -> e.putString(nk, v)
                    is Int -> e.putInt(nk, v)
                    is Long -> e.putLong(nk, v)
                    is Boolean -> e.putBoolean(nk, v)
                    is Float -> e.putFloat(nk, v)
                }
            }
        }
        e.apply()
        val oldChats = ProjectLeftovers.chatsDir(ctx.filesDir, oldCanonical).absolutePath + File.separator
        val newChats = newCanonical?.let { ProjectLeftovers.chatsDir(ctx.filesDir, it).absolutePath + File.separator }
        val drafts = ctx.getSharedPreferences("r4a_chat_drafts", Context.MODE_PRIVATE)
        val d = drafts.edit()
        for ((k, v) in drafts.all) {
            if (!k.startsWith(oldChats)) continue
            d.remove(k)
            if (newChats != null && v is String) d.putString(newChats + k.removePrefix(oldChats), v)
        }
        d.apply()
    }
}

/**
 * La pregunta de una vez (instalaciones que ya tenían el permiso), y también
 * lo que se muestra en Opciones al volver a "Solo el proyecto" con proyectos
 * afuera. [externalNames] vacío = no hay proyectos afuera.
 */
@Composable
fun SafeModeQuestionDialog(
    externalNames: List<String>,
    onSafe: () -> Unit,
    onKeepAccess: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text(stringResource(R.string.phone_access_question_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.phone_access_question_text))
                if (externalNames.isNotEmpty()) {
                    Text(
                        pluralStringResource(
                            R.plurals.phone_access_question_projects, externalNames.size,
                            externalNames.size, externalNames.joinToString(", "),
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSafe) {
                Text(
                    stringResource(
                        if (externalNames.isEmpty()) R.string.phone_access_use_safe
                        else R.string.phone_access_copy_and_safe,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepAccess) { Text(stringResource(R.string.phone_access_keep)) }
        },
    )
}

/** Lo que se muestra después de [PhoneAccess.goSafe]. */
@Composable
fun SafeModeResultDialog(result: PhoneAccess.GoSafeResult, onDismiss: () -> Unit) {
    when (result) {
        is PhoneAccess.GoSafeResult.CopyFailed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.phone_access_apply_title)) },
            text = { Text(stringResource(R.string.phone_access_copy_failed_nothing, result.names.joinToString(", "))) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_dismiss)) } },
        )
        is PhoneAccess.GoSafeResult.Done -> SafeModeApplyDialog(result.automatic, result.copied, onDismiss)
    }
}

/** Después de pasar a modo seguro: cómo se aplica (cerrar R4A, o sacarlo a mano en Android 8–12). */
@Composable
fun SafeModeApplyDialog(
    /** true = R4A lo saca sola al cerrarse; false = hay que ir a los ajustes de Android; null = ya está. */
    automatic: Boolean?,
    copied: PhoneAccess.CopyResult?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.phone_access_apply_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (copied != null && copied.copied > 0) {
                    Text(pluralStringResource(R.plurals.phone_access_copied, copied.copied, copied.copied))
                }
                if (copied != null && copied.failed.isNotEmpty()) {
                    Text(stringResource(R.string.phone_access_copy_failed, copied.failed.joinToString(", ")))
                }
                Text(
                    stringResource(
                        when (automatic) {
                            true -> R.string.phone_access_apply_text_auto
                            false -> R.string.phone_access_apply_text_manual
                            null -> R.string.phone_access_done
                        },
                    ),
                )
            }
        },
        confirmButton = {
            if (automatic == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_dismiss)) }
            } else TextButton(onClick = {
                onDismiss()
                if (automatic) context.findActivity()?.let(PhoneAccess::closeApp)
                else PhoneAccess.openAppSettings(context)
            }) {
                Text(
                    stringResource(
                        if (automatic) R.string.phone_access_close_now else R.string.phone_access_open_settings,
                    ),
                )
            }
        },
        dismissButton = {
            if (automatic != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.phone_access_later)) }
            }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
