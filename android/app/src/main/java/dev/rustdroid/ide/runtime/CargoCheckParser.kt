package dev.rustdroid.ide.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** R4A: one text replacement suggested by rustc. Lines/columns are 1-based. */
data class TextEdit(
    val file: String,
    val lineStart: Int,
    val colStart: Int,
    val lineEnd: Int,
    val colEnd: Int,
    val replacement: String,
)

/** R4A: a quick fix = a button title plus the edits it applies. */
data class LiveFix(val title: String, val edits: List<TextEdit>)

/**
 * R4A: a compiler diagnostic located in a project file, ready to be drawn
 * as a squiggle. Lines/columns are 1-based, as rustc reports them.
 */
data class LiveDiagnostic(
    val file: String,
    val lineStart: Int,
    val colStart: Int,
    val lineEnd: Int,
    val colEnd: Int,
    val isError: Boolean,
    val message: String,
    val code: String?,
    /** The primary span's label, e.g. "cannot borrow as mutable". */
    val label: String?,
    /** help/note children without a machine fix. */
    val notes: List<String>,
    val fixes: List<LiveFix>,
)

/**
 * R4A: parses `cargo check --message-format=json` output (one JSON object
 * per line). Only diagnostics with a primary span inside the project
 * (relative path) are kept — summaries like "aborting due to…" and spans
 * inside std/macros are dropped. Pure — unit-tested on the JVM.
 */
object CargoCheckParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Suggestions rustc considers safe enough to offer as a button. */
    private val applicable = setOf("MachineApplicable", "MaybeIncorrect", "Unspecified")

    fun parse(lines: List<String>): List<LiveDiagnostic> =
        lines.mapNotNull { parseLine(it) }

    fun parseLine(line: String): LiveDiagnostic? {
        val t = line.trim()
        if (!t.startsWith("{")) return null
        val obj = runCatching { json.parseToJsonElement(t) as? JsonObject }.getOrNull() ?: return null
        if (obj.str("reason") != "compiler-message") return null
        val msg = obj["message"] as? JsonObject ?: return null
        val level = msg.str("level") ?: return null
        if (level != "error" && level != "warning") return null

        val spans = msg.arr("spans")
        val primary = spans.firstOrNull { it.bool("is_primary") == true } ?: return null
        val file = primary.str("file_name") ?: return null
        if (file.startsWith("/") || file.startsWith("<")) return null // std, macros

        val notes = mutableListOf<String>()
        val fixes = mutableListOf<LiveFix>()
        for (child in msg.arr("children")) {
            val childMsg = child.str("message").orEmpty()
            val edits = child.arr("spans").mapNotNull { s ->
                val replacement = s.str("suggested_replacement") ?: return@mapNotNull null
                val applicability = s.str("suggestion_applicability")
                if (applicability != null && applicability !in applicable) return@mapNotNull null
                TextEdit(
                    file = s.str("file_name") ?: return@mapNotNull null,
                    lineStart = s.int("line_start") ?: return@mapNotNull null,
                    colStart = s.int("column_start") ?: return@mapNotNull null,
                    lineEnd = s.int("line_end") ?: return@mapNotNull null,
                    colEnd = s.int("column_end") ?: return@mapNotNull null,
                    replacement = replacement,
                )
            }
            if (edits.isNotEmpty()) {
                fixes += LiveFix(childMsg.replaceFirstChar { it.uppercase() }, edits)
            } else if (childMsg.isNotBlank()) {
                notes += "${child.str("level") ?: "note"}: $childMsg"
            }
        }

        return LiveDiagnostic(
            file = file,
            lineStart = primary.int("line_start") ?: return null,
            colStart = primary.int("column_start") ?: return null,
            lineEnd = primary.int("line_end") ?: return null,
            colEnd = primary.int("column_end") ?: return null,
            isError = level == "error",
            message = msg.str("message").orEmpty(),
            code = (msg["code"] as? JsonObject)?.str("code"),
            label = primary.str("label"),
            notes = notes,
            fixes = fixes,
        )
    }

    private fun JsonElement.str(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonElement)
            ?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonElement.int(key: String): Int? =
        ((this as? JsonObject)?.get(key))?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull

    private fun JsonElement.bool(key: String): Boolean? =
        ((this as? JsonObject)?.get(key))?.takeIf { it !is JsonNull }?.jsonPrimitive?.booleanOrNull

    private fun JsonElement.arr(key: String): List<JsonElement> =
        ((this as? JsonObject)?.get(key) as? JsonArray)?.toList() ?: emptyList()
}
