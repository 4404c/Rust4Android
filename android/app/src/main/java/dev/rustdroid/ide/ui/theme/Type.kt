package dev.rustdroid.ide.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import dev.rustdroid.ide.R

/** R4A: Newsreader (OFL, sin Reserved Font Name) para el título R4A: grosor 500, óptica "display", recortada a ASCII. */
val NewsreaderFamily = FontFamily(Font(R.font.newsreader_title, FontWeight.Normal))

/** R4A: Press Start 2P (OFL), la letra pixel del globo de Ferrdroid. */
val PixelFamily = FontFamily(Font(R.font.press_start_2p, FontWeight.Normal))

/** R4A: Courier Prime Bold (OFL), monoespaciada tipo máquina de escribir para el título. */
val TitleFamily = FontFamily(Font(R.font.courier_prime_bold, FontWeight.Bold))


val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

val MonoFamily = FontFamily.Monospace

val MonoSmall = TextStyle(
    fontFamily = MonoFamily,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)
