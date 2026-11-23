package dev.rustdroid.ide.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import dev.rustdroid.ide.util.AppSettings

/**
 * R4A: tema Personalizado. Con 4 colores arma todo el esquema de Material:
 * principal → botones y acentos, secundario → tarjetas y barras (mezclado con
 * el fondo), fondo → fondo, texto → letras (o automático: blanco o negro).
 *
 * Contraste: si un color se parece demasiado al fondo (por ejemplo, todos
 * iguales), se aclara u oscurece lo justo para que se vea. Así nunca quedan
 * botones o letras invisibles.
 */
object CustomScheme {

    fun isDark(c: AppSettings.CustomColors) = Color(c.background).luminance() < 0.4f

    /** Blanco o negro, el que mejor se lea sobre [bg]. */
    fun onColor(bg: Color) = if (bg.luminance() > 0.45f) Color.Black else Color.White

    /** Relación de contraste WCAG (1 = iguales, 21 = blanco sobre negro). */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /** [color] tal cual si ya se distingue de [against]; si no, lo acerca a blanco/negro hasta que sí. */
    fun ensureContrast(color: Color, against: Color, min: Float): Color {
        if (contrast(color, against) >= min) return color
        val target = onColor(against)
        var t = 0.1f
        while (t < 1f) {
            val c = lerp(color, target, t)
            if (contrast(c, against) >= min) return c
            t += 0.1f
        }
        return target
    }

    /** Color del texto que se va a usar (el elegido, o automático). */
    fun textColor(c: AppSettings.CustomColors): Color {
        val bg = Color(c.background)
        val wanted = c.text?.let { Color(it) } ?: onColor(bg)
        return ensureContrast(wanted, bg, 4.5f)
    }

    fun build(c: AppSettings.CustomColors): ColorScheme {
        val bg = Color(c.background)
        val dark = isDark(c)
        // botones y acentos: tienen que verse sobre el fondo
        val primary = ensureContrast(Color(c.primary), bg, 3f)
        // tarjetas: el secundario mezclado con el fondo, sin que el texto deje de leerse
        val secondary = Color(c.secondary)
        val autoText = onColor(bg)
        var k = if (dark) 0.6f else 0.18f
        while (k > 0.05f && onColor(lerp(bg, secondary, k)) != autoText) k -= 0.05f
        var cards = lerp(bg, secondary, k)
        // si el secundario es igual al fondo, las tarjetas se separan igual un poquito
        if (contrast(cards, bg) < 1.12f) cards = lerp(bg, autoText, if (dark) 0.1f else 0.06f)
        fun layer(amount: Float) = lerp(bg, cards, amount)
        // texto: se tiene que leer sobre el fondo y sobre las tarjetas
        val text = ensureContrast(textColor(c), cards, 4.5f)
        // elegido en los botones segmentados, chips…: si el secundario no se distingue, uno del principal
        val selected = if (contrast(secondary, bg) < 1.3f) lerp(bg, primary, 0.35f) else secondary
        val primaryContainer = lerp(bg, primary, 0.35f)
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onColor(primaryContainer),
            inversePrimary = primary,
            secondary = ensureContrast(lerp(secondary, text, 0.35f), bg, 3f),
            onSecondary = onColor(ensureContrast(lerp(secondary, text, 0.35f), bg, 3f)),
            secondaryContainer = selected,
            onSecondaryContainer = onColor(selected),
            tertiary = primary,
            onTertiary = onColor(primary),
            tertiaryContainer = primaryContainer,
            onTertiaryContainer = onColor(primaryContainer),
            background = bg,
            onBackground = text,
            surface = bg,
            onSurface = text,
            surfaceTint = primary,
            surfaceVariant = cards,
            onSurfaceVariant = ensureContrast(lerp(text, bg, 0.3f), cards, 3f),
            outline = ensureContrast(lerp(text, bg, 0.5f), bg, 1.8f),
            outlineVariant = lerp(text, bg, 0.8f),
            surfaceContainerLowest = bg,
            surfaceContainerLow = layer(0.3f),
            surfaceContainer = layer(0.5f),
            surfaceContainerHigh = layer(0.75f),
            surfaceContainerHighest = cards,
            surfaceBright = cards,
            surfaceDim = bg,
        )
    }
}
