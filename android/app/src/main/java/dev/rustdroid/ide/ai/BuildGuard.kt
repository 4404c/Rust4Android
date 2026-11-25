package dev.rustdroid.ide.ai

import java.io.File
import java.security.MessageDigest

/**
 * R4A (auditoría 2, S1): lo que la IA cambió en "cómo se compila" y el
 * usuario todavía no aprobó.
 *
 * Antes solo se preguntaba si aparecía un NOMBRE nuevo en [dependencies], y
 * se esquivaba con `git =`/`path =`, `[target.…]`, `[patch]`, `.cargo/`, un
 * `build.rs` (que cargo EJECUTA hasta en un check sin internet) o volviendo
 * a abrir el proyecto. Ahora, antes de que la IA toque un archivo
 * [sensible][isSensitive], se guarda la huella (SHA-256) de cómo estaba; si
 * al compilar alguno quedó distinto, hay cambios [pendientes][pending]:
 * la IA tiene que pedir permiso y los errores en vivo no compilan solos.
 * Deshacer el cambio vuelve la huella a la original y ya no queda pendiente.
 *
 * Se guarda en [store] (en la carpeta de charlas del proyecto: sigue al
 * renombrar y se borra con el proyecto), así cerrar y abrir no lo "aprueba".
 */
class BuildGuard(private val projectDir: File, private val store: File?) {

    /** Ruta relativa → huella de antes del primer cambio de la IA ("-" = no existía). */
    private val baseline: LinkedHashMap<String, String> by lazy { load() }

    /** Llamar ANTES de que la IA escriba o borre [rel]. */
    @Synchronized
    fun beforeAiWrite(rel: String) {
        if (!isSensitive(rel) || rel in baseline) return
        baseline[rel] = hash(rel)
        save()
    }

    /** Los archivos sensibles que hoy son distintos de lo aprobado. */
    @Synchronized
    fun pending(): List<String> = baseline.filter { (rel, h) -> hash(rel) != h }.keys.toList()

    /** El usuario aprobó (o compiló él mismo con ▶): lo de hoy pasa a ser lo aprobado. */
    @Synchronized
    fun approve() {
        if (baseline.isEmpty()) return
        baseline.clear()
        save()
    }

    private fun hash(rel: String): String {
        val f = File(projectDir, rel)
        if (!f.isFile) return "-"
        val d = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun load(): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        runCatching {
            store?.takeIf { it.isFile }?.readLines()?.forEach { line ->
                val tab = line.lastIndexOf('\t')
                if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
        return map
    }

    private fun save() {
        val s = store ?: return
        runCatching {
            if (baseline.isEmpty()) {
                s.delete()
            } else {
                s.parentFile?.mkdirs()
                dev.rustdroid.ide.util.Fs.writeAtomic(s, baseline.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            }
        }
    }

    companion object {
        /**
         * Archivos que deciden qué código corre al compilar: manifiestos y
         * lockfile (de dónde salen las crates), `.cargo/` (fuentes, linker),
         * `build.rs`, y el código de otras crates del proyecto (miembros del
         * workspace, macros), que no está en `src/` de la raíz.
         */
        fun isSensitive(rel: String): Boolean {
            val name = rel.substringAfterLast('/')
            if (name == "Cargo.toml" || name == "Cargo.lock" || name == "build.rs") return true
            if (name == "rust-toolchain" || name == "rust-toolchain.toml") return true
            val top = rel.substringBefore('/')
            if (top == ".cargo") return true
            if (name.endsWith(".rs") && '/' in rel) {
                return top !in setOf("src", "tests", "examples", "benches")
            }
            return false
        }
    }
}
