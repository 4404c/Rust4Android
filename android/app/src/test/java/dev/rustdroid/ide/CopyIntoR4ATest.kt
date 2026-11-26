package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectRepository
import dev.rustdroid.ide.runtime.CargoRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** R4A (alcance de archivos): copiar un proyecto de afuera a R4A. */
class CopyIntoR4ATest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val projects by lazy { tmp.newFolder("projects") }
    private val registry by lazy { File(tmp.root, "external-projects.txt") }

    private fun repo() = ProjectRepository(
        projectsRoot = projects,
        externalRegistry = registry,
        runner = CargoRunner(),
        envProvider = { emptyMap() },
        cargoPath = { "/unused/cargo" },
    )

    private fun external(name: String): File {
        val dir = tmp.newFolder("storage", name)
        File(dir, "Cargo.toml").writeText("[package]\nname = \"x\"\n")
        File(dir, "src").mkdirs()
        File(dir, "src/main.rs").writeText("fn main() {}\n")
        File(dir, "assets").mkdirs()
        File(dir, "assets/a.png").writeBytes(byteArrayOf(0, 1, 2))
        File(dir, "target/debug").mkdirs()
        File(dir, "target/debug/big").writeText("build output")
        return dir
    }

    @Test
    fun `names become valid project names`() {
        assertEquals("R4A_RUST_PRUEBAS", ProjectRepository.internalNameFor("R4A RUST PRUEBAS"))
        assertEquals("Rust4Android", ProjectRepository.internalNameFor("Rust4Android"))
        assertEquals("p_2048", ProjectRepository.internalNameFor("2048"))
        assertEquals("juego", ProjectRepository.internalNameFor("juego ñ"))
        assertEquals("proyecto", ProjectRepository.internalNameFor("  ¿?  "))
    }

    @Test
    fun `copies the project without target and forgets the external one`() {
        val src = external("R4A RUST PRUEBAS")
        val repo = repo()
        repo.registerExternal(src)

        val copy = repo.copyIntoR4A(src)

        assertEquals(File(projects, "R4A_RUST_PRUEBAS"), copy)
        assertEquals("fn main() {}\n", File(copy, "src/main.rs").readText())
        assertTrue(File(copy, "assets/a.png").readBytes().contentEquals(byteArrayOf(0, 1, 2)))
        assertFalse(File(copy, "target").exists())
        // el original queda intacto
        assertTrue(File(src, "target/debug/big").isFile)
        assertTrue(File(src, "src/main.rs").isFile)
        // ya no está registrado afuera; aparece como proyecto de R4A
        assertTrue(repo.registeredExternal().isEmpty())
        assertTrue(repo.list().any { it.dir == copy && !it.external })
        // sin carpetas temporales sobrantes
        assertEquals(listOf("R4A_RUST_PRUEBAS"), projects.list()!!.toList())
    }

    /** Una carpeta "del selector de Android" armada en memoria: id = ruta. */
    private class FakeTree(val files: Map<String, ByteArray>) : ProjectRepository.TreeSource {
        override fun children(id: String): List<ProjectRepository.TreeSource.Entry> {
            val prefix = if (id.isEmpty()) "" else "$id/"
            return files.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .distinct()
                .map { name ->
                    val full = prefix + name
                    val isDir = full !in files
                    ProjectRepository.TreeSource.Entry(full, name, isDir, files[full]?.size?.toLong() ?: 0)
                }
        }
        override fun open(id: String) = files.getValue(id).inputStream()
    }

    @Test
    fun `importTree copies a picked folder without target and adds what cargo needs`() {
        val tree = FakeTree(
            mapOf(
                "src/main.rs" to "fn main() {}\n".toByteArray(),
                "assets/a.png" to byteArrayOf(9, 8),
                "target/debug/big" to "x".toByteArray(),
            ),
        )
        val dir = repo().importTree(tree, "", "Mi Juego")
        assertEquals("Mi_Juego", dir.name)
        assertEquals("fn main() {}\n", File(dir, "src/main.rs").readText())
        assertTrue(File(dir, "assets/a.png").readBytes().contentEquals(byteArrayOf(9, 8)))
        assertFalse(File(dir, "target").exists())
        // no tenía Cargo.toml: se agrega
        assertTrue(File(dir, "Cargo.toml").readText().contains("name = \"Mi_Juego\""))
        assertTrue(repo().list().any { it.dir == dir && !it.external })
    }

    @Test
    fun `importTree refuses folders that are too big and leaves nothing behind`() {
        val tree = FakeTree(mapOf("big.bin" to ByteArray(2000)))
        val ok = runCatching { repo().importTree(tree, "", "grande", maxBytes = 1000) }
        assertTrue(ok.isFailure)
        assertTrue(projects.list()!!.isEmpty())
    }

    @Test
    fun `a taken name gets a suffix`() {
        File(projects, "pelota").mkdirs()
        val src = external("pelota")
        val copy = repo().copyIntoR4A(src)
        assertEquals("pelota_2", copy.name)
    }

    @Test
    fun `symlinks are not followed`() {
        val src = external("conlink")
        val secret = tmp.newFolder("afuera")
        File(secret, "privado.txt").writeText("no copiar")
        val made = runCatching { Files.createSymbolicLink(File(src, "link").toPath(), secret.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("symlinks not available here (Windows without privilege)", made)

        val copy = repo().copyIntoR4A(src)

        assertFalse(File(copy, "link").exists())
        assertTrue(File(secret, "privado.txt").isFile)
    }
}
