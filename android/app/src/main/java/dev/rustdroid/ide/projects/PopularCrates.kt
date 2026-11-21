package dev.rustdroid.ide.projects

/**
 * R4A: well-known crates shown in Deps before the user searches.
 *
 * Hand-picked, not "most downloaded" (that ranking is dominated by build
 * plumbing like syn/quote). Every entry is pure Rust: R4A has no real C
 * compiler (the cc shim is link-only), so crates with C code inside would
 * fail to build and make a bad first impression.
 */
object PopularCrates {
    val names = listOf(
        "rand",        // random numbers
        "serde",       // (de)serialization framework
        "serde_json",  // JSON
        "regex",       // regular expressions
        "chrono",      // dates and times
        "anyhow",      // easy error handling
        "thiserror",   // custom error types
        "itertools",   // extra iterator tools
        "clap",        // command-line arguments
        "log",         // logging facade
        "once_cell",   // lazy statics
        "rayon",       // data parallelism
        "colored",     // colored terminal output
        "uuid",        // UUIDs
        "base64",      // base64
        "sha2",        // SHA-256 and friends
        "csv",         // CSV files
        "num",         // numeric types
    )
}
