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

    @org.junit.Test
    fun `spec versions are read, pinned ones are skipped`() {
        org.junit.Assert.assertEquals("0.8.5", dev.rustdroid.ide.projects.CrateVersions.specVersion("\"0.8.5\""))
        org.junit.Assert.assertEquals("1.2", dev.rustdroid.ide.projects.CrateVersions.specVersion("\"^1.2\""))
        org.junit.Assert.assertEquals("0.4", dev.rustdroid.ide.projects.CrateVersions.specVersion("{ version = \"0.4\", default-features = false }"))
        org.junit.Assert.assertNull(dev.rustdroid.ide.projects.CrateVersions.specVersion("\"=0.9.3\""))
        org.junit.Assert.assertNull(dev.rustdroid.ide.projects.CrateVersions.specVersion("\"*\""))
        org.junit.Assert.assertNull(dev.rustdroid.ide.projects.CrateVersions.specVersion("{ path = \"../x\" }"))
    }

    @org.junit.Test
    fun `newer means newer than what the spec already allows`() {
        val v = dev.rustdroid.ide.projects.CrateVersions
        org.junit.Assert.assertFalse(v.isNewer("0.4", "0.4.16"))
        org.junit.Assert.assertTrue(v.isNewer("0.8.5", "0.8.6"))
        org.junit.Assert.assertTrue(v.isNewer("0.8.5", "0.9.2"))
        org.junit.Assert.assertTrue(v.isNewer("1", "2.0.1"))
        org.junit.Assert.assertFalse(v.isNewer("1.0.200", "1.0.200"))
    }

    @org.junit.Test
    fun `breaking follows cargo compatibility`() {
        val v = dev.rustdroid.ide.projects.CrateVersions
        org.junit.Assert.assertFalse(v.isBreaking("0.8.5", "0.8.6"))
        org.junit.Assert.assertTrue(v.isBreaking("0.8.5", "0.9.2"))
        org.junit.Assert.assertFalse(v.isBreaking("1.2", "1.9.0"))
        org.junit.Assert.assertTrue(v.isBreaking("1.9", "2.0.0"))
        org.junit.Assert.assertTrue(v.isBreaking("0.0.3", "0.0.4"))
    }
}
