package dev.rustdroid.ide.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rustdroid.ide.ui.theme.LocalEditorPalette

/**
 * R4A: Markdown liviano para el chat (sin librerías): títulos (#), listas
 * (-, *, 1.), citas (>), bloques de código (```), y en línea **negrita**,
 * *itálica*, `código`, ~~tachado~~ y [links](url) (se muestra el texto).
 * Tolera markdown a medio escribir (mientras la IA va respondiendo).
 */
@Composable
fun ChatMarkdown(text: String, color: Color, modifier: Modifier = Modifier) {
    val palette = LocalEditorPalette.current
    val codeBg = palette.consoleBg
    val accent = MaterialTheme.colorScheme.primary
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in blocks) when (b) {
            is Block.Code -> Text(
                b.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.stdout,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(codeBg, RoundedCornerShape(6.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            )
            is Block.Heading -> Text(
                inline(b.text, accent),
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = when (b.level) { 1 -> 17.sp; 2 -> 15.sp; else -> 14.sp },
            )
            is Block.Item -> Row(Modifier.padding(start = (b.indent * 12).dp)) {
                Text(b.marker, color = accent, style = MaterialTheme.typography.bodySmall)
                Text(" ", style = MaterialTheme.typography.bodySmall)
                Text(inline(b.text, accent), color = color, style = MaterialTheme.typography.bodySmall)
            }
            is Block.Quote -> Row {
                Text("▌", color = accent.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                Text(
                    inline(b.text, accent),
                    color = color.copy(alpha = 0.8f),
                    fontStyle = FontStyle.Italic,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            is Block.Para -> Text(inline(b.text, accent), color = color, style = MaterialTheme.typography.bodySmall)
            Block.Rule -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("────────", color = color.copy(alpha = 0.4f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private sealed interface Block {
    data class Code(val code: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Item(val marker: String, val text: String, val indent: Int) : Block
    data class Quote(val text: String) : Block
    data class Para(val text: String) : Block
    data object Rule : Block
}

private val bullet = Regex("""^(\s*)([-*+])\s+(.*)$""")
private val numbered = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val heading = Regex("""^(#{1,6})\s+(.*)$""")

private fun parseBlocks(text: String): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Para(para.toString().trim('\n'))
        para.clear()
    }
    val lines = text.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            flush()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[i])
                i++
            }
            out += Block.Code(code.toString())
            i++ // salta el cierre (si todavía no llegó, igual se muestra lo que hay)
            continue
        }
        val h = heading.matchEntire(trimmed)
        val b = bullet.matchEntire(line)
        val n = numbered.matchEntire(line)
        when {
            h != null -> { flush(); out += Block.Heading(h.groupValues[1].length, h.groupValues[2]) }
            trimmed == "---" || trimmed == "***" -> { flush(); out += Block.Rule }
            b != null -> { flush(); out += Block.Item("•", b.groupValues[3], b.groupValues[1].length / 2) }
            n != null -> { flush(); out += Block.Item("${n.groupValues[2]}.", n.groupValues[3], n.groupValues[1].length / 2) }
            trimmed.startsWith(">") -> { flush(); out += Block.Quote(trimmed.removePrefix(">").trim()) }
            trimmed.isEmpty() -> flush()
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flush()
    return out
}

/** Estilos en línea. Un marcador sin cerrar se muestra tal cual. */
private fun inline(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    fun closeOf(token: String, from: Int) = text.indexOf(token, from).takeIf { it > from }
    while (i < text.length) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = closeOf("`", i + 1)
                if (end != null) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accent, background = accent.copy(alpha = 0.12f))) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1; continue
                }
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val tok = text.substring(i, i + 2)
                val end = closeOf(tok, i + 2)
                if (end != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end), accent)) }
                    i = end + 2; continue
                }
            }
            text.startsWith("~~", i) -> {
                val end = closeOf("~~", i + 2)
                if (end != null) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2; continue
                }
            }
            (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                val end = closeOf(c.toString(), i + 1)
                // "_" dentro de palabras (snake_case) no es itálica
                val wordInside = c == '_' && i > 0 && text[i - 1].isLetterOrDigit()
                if (end != null && !wordInside) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1; continue
                }
            }
            c == '[' -> {
                val close = text.indexOf("](", i)
                val end = if (close > i) text.indexOf(')', close) else -1
                if (close > i && end > close) {
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(i + 1, close))
                    }
                    i = end + 1; continue
                }
            }
        }
        append(c)
        i++
    }
}
