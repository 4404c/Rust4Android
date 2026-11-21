package dev.rustdroid.ide.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** R4A: what kind of file a name is, for its icon in Files and the tabs. */
enum class FileKind { RUST, CARGO_MANIFEST, CARGO_LOCK, TOML, JSON, MARKDOWN, TEXT, SHELL, YAML, OTHER }

fun fileKind(name: String): FileKind = when {
    name == "Cargo.toml" -> FileKind.CARGO_MANIFEST
    name == "Cargo.lock" -> FileKind.CARGO_LOCK
    else -> when (name.substringAfterLast('.', "").lowercase()) {
        "rs" -> FileKind.RUST
        "toml" -> FileKind.TOML
        "json" -> FileKind.JSON
        "md" -> FileKind.MARKDOWN
        "txt" -> FileKind.TEXT
        "sh" -> FileKind.SHELL
        "yml", "yaml" -> FileKind.YAML
        else -> FileKind.OTHER
    }
}

private val RustOrange = Color(0xFFDE6B35)

/**
 * R4A: small file-type icon. Rust files get a drawn gear-with-R (the Rust
 * logo's shape, drawn here rather than shipping the logo file); Cargo files
 * reuse app icons; other text formats get a tiny colored glyph.
 */
@Composable
fun FileTypeIcon(name: String, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    when (fileKind(name)) {
        FileKind.RUST -> RustGear(size, modifier)
        FileKind.CARGO_MANIFEST -> Icon(RdIcons.Package, null, modifier.size(size), tint = Color(0xFFB08968))
        FileKind.CARGO_LOCK -> Icon(Icons.Filled.Lock, null, modifier.size(size), tint = Color(0xFF9E9E9E))
        FileKind.TOML -> Glyph("⚙", Color(0xFF9C8CB8), size, modifier)
        FileKind.JSON -> Glyph("{}", Color(0xFFE5C07B), size, modifier)
        FileKind.MARKDOWN -> Glyph("M↓", Color(0xFF61AFEF), size, modifier)
        FileKind.TEXT -> Glyph("≡", Color(0xFFB0B0B0), size, modifier)
        FileKind.SHELL -> Glyph("$", Color(0xFF98C379), size, modifier)
        FileKind.YAML -> Glyph("Y", Color(0xFFE06C9F), size, modifier)
        FileKind.OTHER -> Icon(RdIcons.FileGeneric, null, modifier.size(size), tint = Color(0xFF8A8F98))
    }
}

/** Gear ring with teeth and a bold "R" in the middle. */
@Composable
private fun RustGear(size: Dp, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = center
        val toothW = r * 0.22f
        val toothH = r * 0.22f
        val teeth = 16
        repeat(teeth) { i ->
            rotate(360f / teeth * i, c) {
                drawRect(
                    RustOrange,
                    topLeft = Offset(c.x - toothW / 2f, c.y - r),
                    size = Size(toothW, toothH + 1f),
                )
            }
        }
        drawCircle(RustOrange, radius = r * 0.80f, center = c, style = Stroke(width = r * 0.20f))
        centeredText(measurer, "R", RustOrange, r * 1.05f, FontFamily.SansSerif, FontWeight.Black)
    }
}

/** A short colored glyph centred in the icon box. */
@Composable
private fun Glyph(text: String, color: Color, size: Dp, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.size(size)) {
        val px = this.size.minDimension * if (text.length > 1) 0.62f else 0.9f
        centeredText(measurer, text, color, px, FontFamily.Monospace, FontWeight.Bold)
    }
}

/**
 * Draws [text] centred, with its size in PIXELS of this icon — so the
 * user's font-size setting cannot overflow the small icon box (sp-based
 * Text did, and the "R" ended up clipped at the bottom).
 */
private fun DrawScope.centeredText(
    measurer: TextMeasurer,
    text: String,
    color: Color,
    sizePx: Float,
    family: FontFamily,
    weight: FontWeight,
) {
    val layout = measurer.measure(
        text,
        style = TextStyle(
            color = color,
            // px -> sp at this density and font scale: renders at exactly sizePx
            fontSize = (sizePx / (density * fontScale)).sp,
            fontFamily = family,
            fontWeight = weight,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        softWrap = false,
        maxLines = 1,
    )
    drawText(
        layout,
        topLeft = Offset(
            center.x - layout.size.width / 2f,
            center.y - layout.size.height / 2f,
        ),
    )
}
