package dev.rustdroid.ide

import dev.rustdroid.ide.projects.CrateVersions
import dev.rustdroid.ide.projects.CrateVersions.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrateVersionsTest {

    @Test
    fun `picks the newest version that builds with the toolchain`() {
        // the real case measured on the phone: uuid 1.29.3 needs Rust 1.89
        val versions = listOf(
            Version("1.29.3", rustVersion = "1.89.0"),
            Version("1.27.0", rustVersion = "1.86"),
            Version("1.26.1", rustVersion = "1.85.0"),
            Version("1.26.0", rustVersion = "1.63"),
        )
        assertEquals("1.26.1", CrateVersions.pickCompatible(versions, "1.85.0"))
    }

    @Test
    fun `no declared rust version counts as compatible`() {
        val versions = listOf(Version("0.8.5"), Version("0.8.4"))
        assertEquals("0.8.5", CrateVersions.pickCompatible(versions, "1.85.0"))
    }

    @Test
    fun `skips yanked and pre-release versions`() {
        val versions = listOf(
            Version("2.0.0-beta.1", rustVersion = "1.60"),
            Version("1.5.0", yanked = true),
            Version("1.4.2", rustVersion = "1.70"),
        )
        assertEquals("1.4.2", CrateVersions.pickCompatible(versions, "1.85.0"))
    }

    @Test
    fun `order in the response does not matter`() {
        val versions = listOf(Version("1.9.0"), Version("1.10.0"), Version("1.2.0"))
        assertEquals("1.10.0", CrateVersions.pickCompatible(versions, "1.85.0"))
    }

    @Test
    fun `null when nothing is compatible`() {
        assertNull(CrateVersions.pickCompatible(listOf(Version("3.0.0", rustVersion = "1.90")), "1.85.0"))
    }

    @Test
    fun `version comparison`() {
        assertEquals(0, CrateVersions.compare("1.62", "1.62.0"))
        assertTrue(CrateVersions.compare("1.10.0", "1.9.9") > 0)
        assertTrue(CrateVersions.compare("1.85.0", "1.89") < 0)
    }

    @Test
    fun `parses the crates io versions endpoint`() {
        val body = """{"versions":[
            {"num":"1.29.3","yanked":false,"rust_version":"1.89.0","downloads":10},
            {"num":"1.26.1","yanked":false,"rust_version":"1.85.0"}
        ],"meta":{"total":2}}"""
        val v = CrateVersions.parse(body)
        assertEquals(2, v.size)
        assertEquals("1.89.0", v[0].rustVersion)
    }
}
