package dev.rustdroid.ide.ui.theme

import androidx.compose.ui.graphics.Color

// Paleta R4A (2026-11-23): sólo verdes (los del logo del cangrejo y sus tonos), blanco y negro.
val R4aGreen = Color(0xFF9CD634)       // verde lima del cangrejo
val R4aGreenMid = Color(0xFF5C941E)    // verde medio
val R4aGreenDark = Color(0xFF1E360C)   // verde muy oscuro
val R4aGreenPale = Color(0xFFC4E88A)   // lima pálido (segundo acento en oscuro)
val R4aGreenDeep = Color(0xFF2F5214)   // entre el medio y el muy oscuro
val R4aGreenForest = Color(0xFF3F6E14) // verde bosque (segundo acento en claro)
val R4aWhite = Color(0xFFFFFFFF)
val R4aBlack = Color(0xFF000000)

// Nombres viejos, todavía usados en algunas pantallas.
val RustOrange = R4aGreen
val RustOrangeDim = R4aGreenMid
val Charcoal = R4aBlack

// ---- Oscuro: paleta de la app de Claude para Android (medida de una captura:
// fondo #151515, botones #252523, píldoras #0B0B0B, texto blanco, etiquetas
// #979797), con el verde lima de R4A como acento en lugar del terracota.
val ClaudeBg = Color(0xFF151515)        // fondo principal
val ClaudeSidebar = Color(0xFF0E0E0E)   // capas hundidas (Files, consola, terminal)
val ClaudeCard = Color(0xFF252523)      // tarjetas, campos, botones redondos
val ClaudeBorder = Color(0xFF333331)    // bordes suaves / elegido
val ClaudeText = Color(0xFFFFFFFF)      // texto principal
val ClaudeTextDim = Color(0xFFDDDED8)   // texto secundario
val ClaudeTextFaint = Color(0xFF979797) // etiquetas, texto apagado
val ClaudeAccent = R4aGreen             // acento: verde lima (en Claude es terracota)

val md_dark_primary = ClaudeAccent
val md_dark_onPrimary = R4aGreenDark
val md_dark_primaryContainer = R4aGreenDeep
val md_dark_onPrimaryContainer = R4aGreenPale
val md_dark_secondary = ClaudeTextDim
val md_dark_onSecondary = ClaudeBg
val md_dark_secondaryContainer = ClaudeBorder
val md_dark_onSecondaryContainer = ClaudeText
val md_dark_tertiary = R4aGreen
val md_dark_onTertiary = R4aGreenDark
val md_dark_tertiaryContainer = R4aGreen
val md_dark_onTertiaryContainer = R4aGreenDark
val md_dark_error = Color(0xFFFFB4AB)
val md_dark_onError = Color(0xFF690005)
val md_dark_errorContainer = Color(0xFF93000A)
val md_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_dark_background = ClaudeBg
val md_dark_onBackground = ClaudeText
val md_dark_surface = ClaudeBg
val md_dark_onSurface = ClaudeText
val md_dark_surfaceVariant = ClaudeCard
val md_dark_onSurfaceVariant = ClaudeTextDim
val md_dark_outline = Color(0xFF5C5C58)
val md_dark_outlineVariant = ClaudeBorder
val md_dark_surfaceContainerLowest = Color(0xFF0B0B0B)
val md_dark_surfaceContainerLow = ClaudeSidebar
val md_dark_surfaceContainer = Color(0xFF1C1C1B)
val md_dark_surfaceContainerHigh = ClaudeCard
val md_dark_surfaceContainerHighest = ClaudeCard

// ---- Claro: paleta clara estilo Claude (marfil y cremas, texto casi negro)
// con verde en lugar del terracota. Acento #5C941E (el lima no se lee sobre
// fondo claro); el lima queda en "Nuevo proyecto".
val ClaudeLightBg = Color(0xFFFAF9F5)      // fondo principal (marfil)
val ClaudeLightSidebar = Color(0xFFF5F4ED) // capas suaves
val ClaudeLightPanel = Color(0xFFF0EEE6)   // barra de arriba, Files, pestañas
val ClaudeLightCard = Color(0xFFECEAE1)    // tarjetas
val ClaudeLightBorder = Color(0xFFDEDCD1)  // bordes suaves
val ClaudeLightText = Color(0xFF141413)    // texto principal
val ClaudeLightTextDim = Color(0xFF5E5D59) // texto secundario

val md_light_primary = R4aGreenMid
val md_light_onPrimary = R4aWhite
val md_light_primaryContainer = Color(0xFFDCEFC0)
val md_light_onPrimaryContainer = R4aGreenDark
val md_light_secondary = Color(0xFF3D3D3A)
val md_light_onSecondary = R4aWhite
val md_light_secondaryContainer = ClaudeLightBorder
val md_light_onSecondaryContainer = ClaudeLightText
val md_light_tertiary = R4aGreenDeep
val md_light_onTertiary = R4aWhite
val md_light_tertiaryContainer = R4aGreen
val md_light_onTertiaryContainer = R4aGreenDark
val md_light_error = Color(0xFFBA1A1A)
val md_light_onError = Color(0xFFFFFFFF)
val md_light_errorContainer = Color(0xFFFFDAD6)
val md_light_onErrorContainer = Color(0xFF410002)
val md_light_background = ClaudeLightBg
val md_light_onBackground = ClaudeLightText
val md_light_surface = ClaudeLightBg
val md_light_onSurface = ClaudeLightText
val md_light_surfaceVariant = ClaudeLightPanel
val md_light_onSurfaceVariant = ClaudeLightTextDim
val md_light_outline = Color(0xFFA8A69C)
val md_light_outlineVariant = ClaudeLightBorder
val md_light_surfaceContainerLowest = R4aWhite
val md_light_surfaceContainerLow = ClaudeLightSidebar
val md_light_surfaceContainer = Color(0xFFF2F0E9)
val md_light_surfaceContainerHigh = ClaudeLightPanel
val md_light_surfaceContainerHighest = ClaudeLightCard
