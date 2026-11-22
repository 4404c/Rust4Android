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

    /**
     * R4A: the version written in a Cargo.toml spec — `"0.8.5"`, `"^1.2"`,
     * or `{ version = "0.4", … }`. Null when there is nothing to update:
     * pinned (`=0.9.3`), wildcard, git/path dependencies.
     */
    fun specVersion(spec: String): String? {
        val raw = Regex("""version\s*=\s*"([^"]*)"""").find(spec)?.groupValues?.get(1)
            ?: spec.trim().takeIf { it.startsWith("\"") }?.trim('"')
            ?: return null
        val v = raw.trim().removePrefix("^").trim()
        if (v.isEmpty() || v.startsWith("=") || v.startsWith("<") || v.startsWith(">") ||
            v.startsWith("~") || '*' in v || ',' in v
        ) return null
        return v.takeIf { it.matches(Regex("""\d+(\.\d+){0,2}""")) }
    }

    /**
     * R4A: is [candidate] newer than what [current] already allows? Only as
     * many parts as [current] has are compared: "0.4" already covers 0.4.16
     * (cargo picks it), while "0.8.5" -> "0.8.6" or "0.9.2" is an update.
     */
    fun isNewer(current: String, candidate: String): Boolean {
        val parts = current.split('.').size
        val cut = candidate.split('.').take(parts).joinToString(".")
        return compare(cut, current) > 0
    }

    /**
     * R4A: would going from [current] to [candidate] be a breaking update
     * under Cargo's rules? 1.x -> 2.x, or 0.8 -> 0.9 (for 0.x the minor
     * number is the "major" one), or 0.0.3 -> 0.0.4.
     */
    fun isBreaking(current: String, candidate: String): Boolean =
        compatBucket(current) != compatBucket(candidate)

    private fun compatBucket(v: String): List<Int> {
        val p = v.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        return when {
            p[0] > 0 -> listOf(p[0])
            p[1] > 0 -> listOf(0, p[1])
            else -> listOf(0, 0, p[2])
        }
    }

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
