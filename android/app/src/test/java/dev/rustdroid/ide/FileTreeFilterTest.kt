package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectRepository
import dev.rustdroid.ide.runtime.CargoRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** R4A: which files the Files drawer shows in "Sources" mode. */
class FileTreeFilterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo() = ProjectRepository(
        projectsRoot = tmp.root,
        externalRegistry = File(tmp.root, "external-projects.txt"),
        runner = CargoRunner(),
        envProvider = { emptyMap() },
        cargoPath = { "/unused/cargo" },
    )

    @Test
    fun `files without an extension are shown`() {
        val project = tmp.newFolder("proj")
        File(project, "main").writeText("fn main() {}\n") // saved without .rs
        File(project, "Cargo.toml").writeText("[package]\nname = \"p\"\n")
        File(project, "data.bin").writeBytes(byteArrayOf(0, 1, 2))
        val names = repo().fileTree(project, allFiles = false).map { it.relativePath }
        assertTrue("main should be listed: $names", "main" in names)
        assertTrue("Cargo.toml" in names)
        assertFalse("unknown extensions stay hidden: $names", "data.bin" in names)
    }
}
