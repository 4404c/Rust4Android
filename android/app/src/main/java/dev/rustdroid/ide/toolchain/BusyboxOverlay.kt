package dev.rustdroid.ide.toolchain

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * R4A: installs the BusyBox shipped in the APK (assets/busybox/) over the
 * one from the toolchain bundle.
 *
 * Why: the bundle's BusyBox 1.36.1 printed a fresh prompt every time the
 * terminal was resized (keyboard open/close) — ash aborted line editing on
 * SIGWINCH. Ours carries busybox/patches/0018 (backport from busybox
 * master) and is built by .github/workflows/busybox.yml. Shipping it in the
 * APK avoids rebuilding and re-publishing the whole ~117 MB bundle.
 *
 * Safe to call on every start: it only writes when the installed binary's
 * SHA-256 differs from the shipped one, and it swaps atomically (temp file,
 * verify, rename) so a failure leaves the previous BusyBox in place. The
 * sh/ash symlinks point at "busybox" by name and keep working.
 */
object BusyboxOverlay {

    private const val ASSET = "busybox/busybox"
    private const val SHA_ASSET = "busybox/busybox.sha256"

    /** @return true when the binary was replaced. */
    fun apply(context: Context, paths: ToolchainPaths): Boolean {
        val target = paths.busybox
        if (!target.isFile) return false // toolchain not installed yet
        val wanted = context.assets.open(SHA_ASSET).use { it.readBytes().decodeToString().trim() }
        if (sha256(target.inputStream()) == wanted) return false

        val tmp = File(target.parentFile, ".busybox.r4a.tmp")
        try {
            context.assets.open(ASSET).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val got = sha256(tmp.inputStream())
            check(got == wanted) { "shipped busybox corrupted: $got != $wanted" }
            check(tmp.setReadable(true, false) && tmp.setExecutable(true, false)) {
                "cannot chmod ${tmp.path}"
            }
            check(tmp.renameTo(target)) { "cannot replace ${target.path}" }
            return true
        } finally {
            tmp.delete() // no-op after a successful rename
        }
    }

    private fun sha256(input: InputStream): String = input.use { stream ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}
