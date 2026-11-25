package dev.rustdroid.ide

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.file.Files

/**
 * R4A (auditoría M5): arma la copia de seguridad a mano.
 *
 * Con targetSdk 28, Android 12+ ignora las reglas XML (`backup_rules.xml`) en
 * `adb backup` y copiaba TODO, incluida la clave privada de los juegos y
 * cientos de MB de compilaciones. Este agente copia siempre la misma lista
 * cerrada, y la clave de juegos (files/signing) SOLO si la copia va cifrada
 * (nube de Google con bloqueo de pantalla) o es el paso a un celular nuevo.
 * `adb backup` no trae ninguna de esas marcas: ahí la clave nunca sale.
 *
 * Mantener en sincronía con `res/xml/backup_rules.xml` (que sigue mandando al
 * restaurar).
 */
class R4ABackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        val files = filesDir
        for (name in FILES) backupTree(File(files, name), data)
        if (keyAllowed(data)) backupTree(File(files, SIGNING), data)
        val prefs = File(dataDir, "shared_prefs")
        for (name in PREFS) backupTree(File(prefs, name), data)
    }

    private fun keyAllowed(data: FullBackupDataOutput): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val flags = data.transportFlags
        return flags and FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED != 0 ||
            flags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0
    }

    /** Copia [f] (archivo o carpeta entera). Los symlinks no se siguen ni se copian. */
    private fun backupTree(f: File, data: FullBackupDataOutput) {
        if (Files.isSymbolicLink(f.toPath()) || !f.exists()) return
        if (f.isDirectory) {
            f.listFiles()?.forEach { backupTree(it, data) }
        } else {
            fullBackupFile(f, data)
        }
    }

    // Solo copia completa (android:fullBackupOnly): no hay datos clave-valor.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) {}
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) {}

    companion object {
        /** En files/: lo del usuario (sin toolchain ni cachés de compilación/crates). */
        private val FILES = listOf("projects", "chats", "export-icons", "external-projects.txt")
        private const val SIGNING = "signing"
        /** Ajustes. Sin r4a_ai: sus API keys están cifradas con el Keystore y en otro celu no sirven. */
        private val PREFS = listOf("r4a_prefs.xml", "r4a_export.xml", "r4a_chat_drafts.xml")
    }
}
