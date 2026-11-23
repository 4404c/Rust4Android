package dev.rustdroid.ide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.drawable.ColorDrawable

private val DarkScheme = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    primaryContainer = md_dark_primaryContainer,
    onPrimaryContainer = md_dark_onPrimaryContainer,
    secondary = md_dark_secondary,
    onSecondary = md_dark_onSecondary,
    secondaryContainer = md_dark_secondaryContainer,
    onSecondaryContainer = md_dark_onSecondaryContainer,
    tertiary = md_dark_tertiary,
    onTertiary = md_dark_onTertiary,
    tertiaryContainer = md_dark_tertiaryContainer,
    onTertiaryContainer = md_dark_onTertiaryContainer,
    error = md_dark_error,
    onError = md_dark_onError,
    errorContainer = md_dark_errorContainer,
    onErrorContainer = md_dark_onErrorContainer,
    background = md_dark_background,
    onBackground = md_dark_onBackground,
    surface = md_dark_surface,
    onSurface = md_dark_onSurface,
    surfaceVariant = md_dark_surfaceVariant,
    onSurfaceVariant = md_dark_onSurfaceVariant,
    outline = md_dark_outline,
    outlineVariant = md_dark_outlineVariant,
    surfaceContainerLowest = md_dark_surfaceContainerLowest,
    surfaceContainerLow = md_dark_surfaceContainerLow,
    surfaceContainer = md_dark_surfaceContainer,
    surfaceContainerHigh = md_dark_surfaceContainerHigh,
    surfaceContainerHighest = md_dark_surfaceContainerHighest,
    surfaceBright = md_dark_surfaceContainerHighest,
    surfaceDim = md_dark_surfaceContainerLowest,
)

private val LightScheme = lightColorScheme(
    primary = md_light_primary,
    onPrimary = md_light_onPrimary,
    primaryContainer = md_light_primaryContainer,
    onPrimaryContainer = md_light_onPrimaryContainer,
    secondary = md_light_secondary,
    onSecondary = md_light_onSecondary,
    secondaryContainer = md_light_secondaryContainer,
    onSecondaryContainer = md_light_onSecondaryContainer,
    tertiary = md_light_tertiary,
    onTertiary = md_light_onTertiary,
    tertiaryContainer = md_light_tertiaryContainer,
    onTertiaryContainer = md_light_onTertiaryContainer,
    error = md_light_error,
    onError = md_light_onError,
    errorContainer = md_light_errorContainer,
    onErrorContainer = md_light_onErrorContainer,
    background = md_light_background,
    onBackground = md_light_onBackground,
    surface = md_light_surface,
    onSurface = md_light_onSurface,
    surfaceVariant = md_light_surfaceVariant,
    onSurfaceVariant = md_light_onSurfaceVariant,
    outline = md_light_outline,
    outlineVariant = md_light_outlineVariant,
    surfaceContainerLowest = md_light_surfaceContainerLowest,
    surfaceContainerLow = md_light_surfaceContainerLow,
    surfaceContainer = md_light_surfaceContainer,
    surfaceContainerHigh = md_light_surfaceContainerHigh,
    surfaceContainerHighest = md_light_surfaceContainerHighest,
    surfaceBright = md_light_surfaceContainerHighest,
    surfaceDim = md_light_surfaceContainerLowest,
)

/** Editor-specific palette (sora themes are separate assets). */
data class EditorPalette(
    val consoleBg: androidx.compose.ui.graphics.Color,
    val stdout: androidx.compose.ui.graphics.Color,
    val stderr: androidx.compose.ui.graphics.Color,
    val system: androidx.compose.ui.graphics.Color,
    // R4A: console colors by meaning (see runtime/ConsoleClassifier.kt)
    val warning: androidx.compose.ui.graphics.Color,
    val status: androidx.compose.ui.graphics.Color,
    val noise: androidx.compose.ui.graphics.Color,
)

val LocalEditorPalette = staticCompositionLocalOf {
    EditorPalette(
        consoleBg = androidx.compose.ui.graphics.Color(0xFF0E0E0E),
        stdout = androidx.compose.ui.graphics.Color(0xFFFAF9F5),
        stderr = androidx.compose.ui.graphics.Color(0xFFFF8A80),
        system = androidx.compose.ui.graphics.Color(0xFF9CD634),
        warning = androidx.compose.ui.graphics.Color(0xFFFFCC66),
        status = androidx.compose.ui.graphics.Color(0xFF9E9E9E),
        noise = androidx.compose.ui.graphics.Color(0xFF6E6A66),
    )
}

/** R4A: true si la app está en modo oscuro (elegido en Opciones o por el sistema). */
val LocalDarkTheme = staticCompositionLocalOf { true }

/** R4A: oscuro según Opciones → Apariencia (Sistema / Claro / Oscuro). */
@Composable
fun rememberDarkTheme(): Boolean {
    val mode by dev.rustdroid.ide.util.AppSettings.themeMode.collectAsState()
    val custom by dev.rustdroid.ide.util.AppSettings.customColors.collectAsState()
    return when (mode) {
        dev.rustdroid.ide.util.AppSettings.ThemeMode.LIGHT -> false
        dev.rustdroid.ide.util.AppSettings.ThemeMode.DARK -> true
        dev.rustdroid.ide.util.AppSettings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        dev.rustdroid.ide.util.AppSettings.ThemeMode.CUSTOM -> CustomScheme.isDark(custom)
    }
}

@Composable
fun RustDroidTheme(
    darkTheme: Boolean = rememberDarkTheme(),
    content: @Composable () -> Unit,
) {
    val mode by dev.rustdroid.ide.util.AppSettings.themeMode.collectAsState()
    val custom by dev.rustdroid.ide.util.AppSettings.customColors.collectAsState()
    val isCustom = mode == dev.rustdroid.ide.util.AppSettings.ThemeMode.CUSTOM
    val scheme = when {
        isCustom -> CustomScheme.build(custom)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    // Barras del sistema y fondo de la ventana siguen al tema elegido (no sólo al del sistema).
    val view = LocalView.current
    if (!view.isInEditMode) {
        val bg = scheme.background
        SideEffect {
            val activity = view.context as? ComponentActivity ?: return@SideEffect
            activity.window.setBackgroundDrawable(ColorDrawable(bg.toArgb()))
            val transparent = android.graphics.Color.TRANSPARENT
            val style = if (darkTheme) SystemBarStyle.dark(transparent)
            else SystemBarStyle.light(transparent, transparent)
            activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
    }
    val basePalette = if (darkTheme) {
        EditorPalette(
            consoleBg = androidx.compose.ui.graphics.Color(0xFF0E0E0E),
            stdout = androidx.compose.ui.graphics.Color(0xFFFAF9F5),
            stderr = androidx.compose.ui.graphics.Color(0xFFFF8A80),
            system = androidx.compose.ui.graphics.Color(0xFF9CD634),
            warning = androidx.compose.ui.graphics.Color(0xFFFFCC66),
            status = androidx.compose.ui.graphics.Color(0xFF9E9E9E),
            noise = androidx.compose.ui.graphics.Color(0xFF6E6A66),
        )
    } else {
        EditorPalette(
            consoleBg = androidx.compose.ui.graphics.Color(0xFFF5F4ED),
            stdout = androidx.compose.ui.graphics.Color(0xFF141413),
            stderr = androidx.compose.ui.graphics.Color(0xFFB3261E),
            system = androidx.compose.ui.graphics.Color(0xFF5C941E),
            warning = androidx.compose.ui.graphics.Color(0xFF8A5A00),
            status = androidx.compose.ui.graphics.Color(0xFF6B6B6B),
            noise = androidx.compose.ui.graphics.Color(0xFFA19D99),
        )
    }
    // Personalizado: la consola toma el tono de las tarjetas
    val palette = if (isCustom) basePalette.copy(consoleBg = scheme.surfaceContainerLow) else basePalette
    CompositionLocalProvider(LocalEditorPalette provides palette, LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
