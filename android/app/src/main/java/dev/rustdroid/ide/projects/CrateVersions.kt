package dev.rustdroid.ide.projects

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * R4A: pick the newest version of a crate that builds with the on-device
 * Rust toolchain.
 *
 * crates.io's `max_version` often needs a newer Rust than the bundle's
 * (e.g. uuid 1.29.3 requires 1.89, the bundle is 1.85). Writing that into
 * Cargo.toml makes the build fail, and the MSRV-aware resolver cannot go
 * below a version the manifest asks for — so the app has to choose.
 */
object CrateVersions {

    @Serializable
    data class Version(
        val num: String,
        val yanked: Boolean = false,
        @SerialName("rust_version") val rustVersion: String? = null,
    )

    @Serializable
    private data class VersionsResponse(val versions: List<Version> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<Version> =
        json.decodeFromString(VersionsResponse.serializer(), body).versions

    /**
     * Newest non-yanked stable version whose `rust_version` is unset or
     * <= [rust]. Null when none qualifies.
     */
    fun pickCompatible(versions: List<Version>, rust: String): String? =
        versions
            .filter { !it.yanked && '-' !in it.num } // skip pre-releases
            .filter { it.rustVersion == null || compare(it.rustVersion, rust) <= 0 }
            .maxWithOrNull { a, b -> compare(a.num, b.num) }
            ?.num

    /** Numeric dotted-version comparison ("1.62" == "1.62.0"). */
    fun compare(a: String, b: String): Int {
        val pa = a.substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
