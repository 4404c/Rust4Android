package dev.rustdroid.ide.export

import com.android.apksig.ApkSigner
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * R4A: builds an installable APK of the user's game from the template APK
 * (gameplayer module, shipped in assets): the game library goes into
 * lib/arm64-v8a/libgame.so, the manifest gets the chosen package, name and
 * version, and the result is signed with apksig (v1+v2+v3). apksig also
 * aligns uncompressed entries, so no zipalign step is needed.
 */
object ApkExporter {

    data class Options(
        val appName: String,
        val packageName: String,
        val versionCode: Int,
        val versionName: String,
    )

    /** Strings of the template manifest that [Options] replace. */
    const val TEMPLATE_PACKAGE = "r4a.player.template"
    const val TEMPLATE_LABEL = "R4A Game Template"
    const val TEMPLATE_VERSION_NAME = "1.0"

    /** Template entries that hold the launcher icon (PNG). */
    val ICON_ENTRY = Regex("""res/[^/]*\.png|res/mipmap[^/]*/ic_launcher\.png""")

    private val SEGMENT = Regex("[a-zA-Z][a-zA-Z0-9_]*")
    private val JAVA_WORDS = setOf(
        "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "super", "switch", "synchronized",
        "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
    )

    /** Android package rule: two or more parts like `com.nombre.juego`. */
    fun isValidPackage(p: String): Boolean {
        val parts = p.split('.')
        return parts.size >= 2 && parts.all { it.matches(SEGMENT) && it !in JAVA_WORDS }
    }

    /** A package suggested from a project name: `com.r4a.<name>`. */
    fun suggestPackage(projectName: String): String {
        val clean = projectName.lowercase().replace(Regex("[^a-z0-9_]"), "_")
            .let { if (it.firstOrNull()?.isLetter() == true) it else "juego_$it" }
            .let { if (it in JAVA_WORDS) "${it}_" else it }
        return "com.r4a.$clean"
    }

    /**
     * Write the signed APK to [out]. [workDir] holds the unsigned
     * intermediate file. Throws with a readable message on bad input.
     */
    fun export(
        template: ByteArray,
        gameLibrary: File,
        options: Options,
        key: GameSigningKey.Key,
        workDir: File,
        out: File,
        icon: ByteArray? = null,
        assets: Map<String, File> = emptyMap(),
    ) {
        require(isValidPackage(options.packageName)) { "package: ${options.packageName}" }
        require(options.appName.isNotBlank()) { "name" }
        require(options.versionCode > 0) { "versionCode" }
        require(gameLibrary.isFile) { gameLibrary.path }

        workDir.mkdirs()
        val unsigned = File(workDir, "unsigned.apk")
        ZipInputStream(template.inputStream()).use { zin ->
            ZipOutputStream(unsigned.outputStream().buffered()).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.name.startsWith("META-INF/")) continue // old signature
                    var data = zin.readBytes()
                    if (e.name == "AndroidManifest.xml") {
                        data = BinaryManifest.rewrite(
                            data,
                            mapOf(
                                TEMPLATE_PACKAGE to options.packageName,
                                TEMPLATE_LABEL to options.appName,
                                TEMPLATE_VERSION_NAME to options.versionName,
                            ),
                            options.versionCode,
                        )
                    }
                    // the template's only image is its launcher icon (the
                    // release build gives it a short name like res/o-.png)
                    if (icon != null && ICON_ENTRY.matches(e.name)) data = icon
                    zout.putEntry(e.name, data, stored = e.method == ZipEntry.STORED)
                }
                zout.putEntry("lib/arm64-v8a/libgame.so", gameLibrary.readBytes(), stored = false)
                // the game's images/sounds: macroquad reads them from here
                for ((name, file) in assets) {
                    zout.putEntry("assets/$name", file.readBytes(), stored = false)
                }
            }
        }

        out.parentFile?.mkdirs()
        val signer = ApkSigner.SignerConfig.Builder("r4a", key.privateKey, listOf(key.certificate)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned)
            .setOutputApk(out)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        unsigned.delete()
    }

    private fun ZipOutputStream.putEntry(name: String, data: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        if (stored) {
            // stored entries need size and CRC up front (resources.arsc must
            // stay uncompressed for Android to map it)
            e.method = ZipEntry.STORED
            e.size = data.size.toLong()
            e.compressedSize = data.size.toLong()
            e.crc = CRC32().apply { update(data) }.value
        } else {
            e.method = ZipEntry.DEFLATED
        }
        putNextEntry(e)
        write(data)
        closeEntry()
    }
}
