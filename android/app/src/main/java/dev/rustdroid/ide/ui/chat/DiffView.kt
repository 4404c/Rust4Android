package dev.rustdroid.ide.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rustdroid.ide.R
import dev.rustdroid.ide.ui.theme.LocalEditorPalette

/**
 * R4A: diferencias entre el "antes" y el "después" de un archivo que tocó la
 * IA, por líneas, como en GitHub: verde (+) lo agregado, rojo (−) lo borrado,
 * con números de línea y solo un poco de contexto alrededor de cada cambio.
 */
object LineDiff {
    enum class Kind { SAME, ADD, DEL, GAP }

    /** [oldNo]/[newNo]: número de línea (1-based) en cada versión; GAP = líneas iguales ocultas. */
    data class Line(val kind: Kind, val text: String, val oldNo: Int? = null, val newNo: Int? = null)

    data class Result(val lines: List<Line>, val added: Int, val removed: Int, val firstChangedNewLine: Int?)

    /**
     * Recorta el principio y el final que coinciden y compara el medio con LCS.
     * Si el medio es enorme (muchas miles de líneas distintas) lo marca como
     * reemplazo completo para no gastar memoria.
     */
    fun compute(before: String?, after: String?, context: Int = 3): Result {
        val a = before?.lines() ?: emptyList()
        val b = after?.lines() ?: emptyList()
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        val full = ArrayList<Line>()
        for (i in 0 until start) full += Line(Kind.SAME, a[i], i + 1, i + 1)
        val midA = a.subList(start, endA)
        val midB = b.subList(start, endB)
        if (midA.size.toLong() * midB.size <= 4_000_000L) {
            // LCS clásico sobre el medio
            val dp = Array(midA.size + 1) { IntArray(midB.size + 1) }
            for (i in midA.indices.reversed()) for (j in midB.indices.reversed()) {
                dp[i][j] = if (midA[i] == midB[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
            var i = 0
            var j = 0
            while (i < midA.size || j < midB.size) {
                when {
                    i < midA.size && j < midB.size && midA[i] == midB[j] -> {
                        full += Line(Kind.SAME, midA[i], start + i + 1, start + j + 1); i++; j++
                    }
                    // como GitHub: primero lo borrado (rojo) y después lo agregado (verde)
                    i < midA.size && (j == midB.size || dp[i + 1][j] >= dp[i][j + 1]) -> {
                        full += Line(Kind.DEL, midA[i], start + i + 1, null); i++
                    }
                    else -> { full += Line(Kind.ADD, midB[j], null, start + j + 1); j++ }
                }
            }
        } else {
            midA.forEachIndexed { k, t -> full += Line(Kind.DEL, t, start + k + 1, null) }
            midB.forEachIndexed { k, t -> full += Line(Kind.ADD, t, null, start + k + 1) }
        }
        for (k in 0 until a.size - endA) full += Line(Kind.SAME, a[endA + k], endA + k + 1, endB + k + 1)

        val added = full.count { it.kind == Kind.ADD }
        val removed = full.count { it.kind == Kind.DEL }
        val firstNew = full.firstOrNull { it.kind == Kind.ADD }?.newNo
            ?: full.indexOfFirst { it.kind == Kind.DEL }.takeIf { it >= 0 }?.let { idx ->
                full.subList(idx, full.size).firstOrNull { it.newNo != null }?.newNo ?: b.size.coerceAtLeast(1)
            }
        return Result(collapse(full, context), added, removed, firstNew)
    }

    /** Deja [context] líneas iguales alrededor de cada cambio; el resto, un "⋯". */
    private fun collapse(full: List<Line>, context: Int): List<Line> {
        val keep = BooleanArray(full.size)
        full.forEachIndexed { i, l ->
            if (l.kind != Kind.SAME) for (k in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(full.size - 1)) keep[k] = true
        }
        val out = ArrayList<Line>()
        var hidden = 0
        full.forEachIndexed { i, l ->
            if (keep[i]) {
                if (hidden > 0) { out += Line(Kind.GAP, hidden.toString()); hidden = 0 }
                out += l
            } else hidden++
        }
        if (hidden > 0) out += Line(Kind.GAP, hidden.toString())
        return out
    }
}

private val AddBg = Color(0xFF2EA043)
private val DelBg = Color(0xFFF85149)

/** Una línea del diff: números viejo/nuevo, signo y texto. */
@Composable
private fun DiffRow(l: LineDiff.Line, textColor: Color) {
    if (l.kind == LineDiff.Kind.GAP) {
        Text(
            stringResource(R.string.ai_diff_hidden, l.text.toIntOrNull() ?: 0),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = textColor.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp),
        )
        return
    }
    val bg = when (l.kind) {
        LineDiff.Kind.ADD -> AddBg.copy(alpha = 0.22f)
        LineDiff.Kind.DEL -> DelBg.copy(alpha = 0.22f)
        else -> Color.Transparent
    }
    val sign = when (l.kind) { LineDiff.Kind.ADD -> "+"; LineDiff.Kind.DEL -> "−"; else -> " " }
    val signColor = when (l.kind) { LineDiff.Kind.ADD -> AddBg; LineDiff.Kind.DEL -> DelBg; else -> textColor }
    // R4A: un solo Text por línea (antes eran tres): menos trabajo al entrar en pantalla
    val line = androidx.compose.runtime.remember(l, textColor) {
        androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = textColor.copy(alpha = 0.45f))) {
                append((l.oldNo?.toString() ?: "").padStart(4) + " " + (l.newNo?.toString() ?: "").padStart(4))
            }
            withStyle(androidx.compose.ui.text.SpanStyle(color = signColor)) { append(" $sign ") }
            append(l.text.ifEmpty { " " })
        }
    }
    Text(
        line,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = textColor,
        softWrap = false,
        maxLines = 1,
        modifier = Modifier.background(bg),
    )
}

/** El diff dentro de la tarjeta (hasta [maxLines] líneas, con scroll horizontal). */
@Composable
fun DiffPreview(result: LineDiff.Result, maxLines: Int = 40) {
    val palette = LocalEditorPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.consoleBg)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        result.lines.take(maxLines).forEach { DiffRow(it, palette.stdout) }
        if (result.lines.size > maxLines) {
            Text(
                stringResource(R.string.ai_diff_more, result.lines.size - maxLines),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

/** El diff entero, a pantalla completa. */
@Composable
fun DiffDialog(title: String, result: LineDiff.Result, onDismiss: () -> Unit) {
    val palette = LocalEditorPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(palette.consoleBg)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = palette.stdout, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row {
                        Text("+${result.added}", color = AddBg, fontSize = 12.sp)
                        Text("  −${result.removed}", color = DelBg, fontSize = 12.sp)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ai_chat_close), tint = palette.stdout)
                }
            }
            val scroll = rememberScrollState()
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(scroll).width(2000.dp)) {
                items(result.lines) { DiffRow(it, palette.stdout) }
            }
        }
    }
}
