package dev.rustdroid.ide

import dev.rustdroid.ide.model.FileNode
import dev.rustdroid.ide.ui.editor.FileTreeGuides
import dev.rustdroid.ide.ui.editor.TreeRow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FileTreeGuidesTest {

    private fun dir(path: String, depth: Int) = FileNode(File(path), path, true, depth)
    private fun file(path: String, depth: Int) = FileNode(File(path), path, false, depth)

    /** Renders rows like `tree` so expectations read like the real output. */
    private fun render(rows: List<TreeRow>): List<String> = rows.map { r ->
        val prefix = r.continues.joinToString("") { if (it) "│   " else "    " }
        val connector = if (r.isLast) "└── " else "├── "
        val name = r.node.file.name + if (r.node.isDirectory) "/" else ""
        prefix + connector + name
    }

    // the example from the screenshot: lib/ and vendor/ with files inside
    private val sample = listOf(
        dir("lib", 0),
        file("lib/hotp.php", 1),
        file("lib/otphp.php", 1),
        file("lib/otp.php", 1),
        file("lib/totp.php", 1),
        dir("vendor", 0),
        file("vendor/base32.php", 1),
        file("vendor/libs.php", 1),
        file("LICENCE", 0),
    )

    @Test
    fun `draws like tree`() {
        assertEquals(
            listOf(
                "├── lib/",
                "│   ├── hotp.php",
                "│   ├── otphp.php",
                "│   ├── otp.php",
                "│   └── totp.php",
                "├── vendor/",
                "│   ├── base32.php",
                "│   └── libs.php",
                "└── LICENCE",
            ),
            render(FileTreeGuides.rows(sample, emptySet())),
        )
    }

    @Test
    fun `last folder does not continue its parent line`() {
        val nodes = listOf(
            file("Cargo.toml", 0),
            dir("src", 0),
            dir("src/bin", 1),
            file("src/bin/tool.rs", 2),
            file("src/main.rs", 1),
        )
        assertEquals(
            listOf(
                "├── Cargo.toml",
                "└── src/",
                "    ├── bin/",
                "    │   └── tool.rs",
                "    └── main.rs",
            ),
            render(FileTreeGuides.rows(nodes, emptySet())),
        )
    }

    @Test
    fun `collapsed folder hides its contents and keeps the others`() {
        val rows = FileTreeGuides.rows(sample, setOf("lib"))
        assertEquals(
            listOf(
                "├── lib/",
                "├── vendor/",
                "│   ├── base32.php",
                "│   └── libs.php",
                "└── LICENCE",
            ),
            render(rows),
        )
        assertEquals(false, rows.first { it.node.relativePath == "lib" }.expanded)
        assertEquals(true, rows.first { it.node.relativePath == "vendor" }.expanded)
    }

    @Test
    fun `collapsing does not hide a sibling that only shares a name prefix`() {
        val nodes = listOf(dir("src", 0), file("src/main.rs", 1), dir("src2", 0), file("src2/x.rs", 1))
        assertEquals(
            listOf("├── src/", "└── src2/", "    └── x.rs"),
            render(FileTreeGuides.rows(nodes, setOf("src"))),
        )
    }
}
