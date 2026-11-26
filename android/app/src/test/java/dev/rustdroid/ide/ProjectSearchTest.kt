package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectSearchTest {
    @Test
    fun findsInTextFilesOnly() {
        val dir = Files.createTempDirectory("ps").toFile()
        File(dir, "src").mkdirs()
        File(dir, "target/debug").mkdirs()
        File(dir, "assets").mkdirs()
        File(dir, "src/main.rs").writeText("fn main() {\n    let pelota = 1; // Pelota\n}\n")
        File(dir, "Cargo.toml").writeText("name = \"pelota\"\n")
        File(dir, "target/debug/x.rs").writeText("pelota")
        File(dir, "assets/p.png").writeBytes(byteArrayOf(0, 1, 2) + "pelota".toByteArray())

        val r = ProjectSearch.search(dir, "pelota", matchCase = false)
        assertEquals(
            listOf("Cargo.toml" to 1, "src/main.rs" to 2),
            r.hits.map { it.path to it.line },
        )
        assertEquals(listOf(9), r.hits.filter { it.path == "src/main.rs" }.map { it.col })
        assertEquals(2, ProjectSearch.search(dir, "pelota", matchCase = true).hits.size)

        // unsaved text of an open tab wins over the disk
        val u = ProjectSearch.search(dir, "pelota", true, mapOf("src/main.rs" to "nada"))
        assertEquals(listOf("Cargo.toml"), u.hits.map { it.path })

        val t = ProjectSearch.search(dir, "e", false, limit = 1)
        assertEquals(1, t.hits.size)
        assertTrue(t.truncated)
        dir.deleteRecursively()
    }
}
