package dev.rustdroid.ide.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rustdroid.ide.R
import kotlinx.coroutines.launch

/**
 * R4A: la presentación de Ferrdroid, a pantalla completa. Cada paso muestra
 * capturas de la app (res/drawable-nodpi/tour_*.webp, sacadas del celu sin
 * barras del sistema) con la zona de la que habla resaltada, y abajo Ferrdroid
 * lo cuenta en su globo, con Saltar · n/N · Siguiente. Se pasa tocando a
 * Ferrdroid, Siguiente o deslizando.
 */
@Composable
fun FerrdroidTourScreen(wakeLines: List<String>, onClose: () -> Unit) {
    val context = LocalContext.current
    val still = remember { animationsOff(context) }
    val texts = remember { context.resources.getStringArray(R.array.ferrdroid_tour).toList() }
    // las capturas en inglés (drawable-en-nodpi) tienen algunos botones en otro lugar
    val pages = remember { tourPages(context.resources.getBoolean(R.bool.tour_shots_en)) }
    val total = minOf(texts.size, pages.size)
    val pager = rememberPagerState { total }
    val scope = rememberCoroutineScope()
    val ferrdroid = rememberFerrdroidState()
    LaunchedEffect(pager.currentPage) {
        ferrdroid.cheer(600)
        ferrdroid.say(texts[pager.currentPage])
    }
    val goTo: (Int) -> Unit = { page ->
        scope.launch { if (still) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
    }
    val tour = FerrdroidTour(
        step = pager.currentPage,
        total = total,
        onNext = { if (pager.currentPage + 1 < total) goTo(pager.currentPage + 1) else onClose() },
        onSkip = onClose,
    )
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    pageSpacing = 16.dp,
                ) { page -> TourPage(pages[page], still) }
                Spacer(Modifier.height(8.dp))
                Ferrdroid(state = ferrdroid, tips = emptyList(), wakeLines = wakeLines, tour = tour)
            }
        }
    }
}

/** Una captura y, si hace falta, la zona que se resalta (en fracciones de la imagen). */
private class Shot(@DrawableRes val res: Int, vararg val highlights: Rect)

/** Un paso por entrada, en el mismo orden que `ferrdroid_tour`. Vacío = la portada. */
private fun tourPages(en: Boolean): List<List<Shot>> = listOf(
    emptyList(),
    listOf(
        if (en) Shot(R.drawable.tour_new, Rect(0.635f, 0.927f, 0.975f, 0.988f), Rect(0.24f, 0.538f, 0.62f, 0.582f))
        else Shot(R.drawable.tour_new, Rect(0.58f, 0.927f, 0.975f, 0.988f), Rect(0.24f, 0.555f, 0.65f, 0.597f)),
    ), // Nuevo proyecto + Juego
    listOf(Shot(R.drawable.tour_editor, Rect(0.80f, 0.0f, 0.90f, 0.05f))),             // ▶
    listOf(
        Shot(R.drawable.tour_problems, Rect(0.0f, 0.735f, 1.0f, 0.86f)),               // Problems
        Shot(R.drawable.tour_console, Rect(0.0f, 0.735f, 1.0f, 0.998f)),               // Console
    ),
    listOf(
        Shot(R.drawable.tour_game),
        Shot(R.drawable.tour_export, if (en) Rect(0.632f, 0.785f, 0.75f, 0.823f) else Rect(0.585f, 0.783f, 0.76f, 0.825f)), // Exportar
    ),
    listOf(Shot(R.drawable.tour_chat_home, Rect(0.11f, 0.953f, 0.99f, 0.998f))),      // cuadro del chat
    listOf(Shot(R.drawable.tour_chat_project, Rect(0.11f, 0.953f, 0.99f, 0.998f))),
)

@Composable
private fun TourPage(shots: List<Shot>, still: Boolean) {
    if (shots.isEmpty()) {
        // portada: Ferrdroid grande y el nombre de la app
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                bitmap = ImageBitmap.imageResource(R.drawable.ferrdroid_contento),
                contentDescription = null,
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(19.dp * 12, 9.dp * 12),
            )
            Spacer(Modifier.height(24.dp))
            Text("𝚁𝟺𝙰", color = MaterialTheme.colorScheme.primary, fontSize = 56.sp)
        }
        return
    }
    // cada captura lo más grande posible sin cortarse: limita el alto o el ancho, lo que llegue antes
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 12.dp
        val n = shots.size
        val w = minOf(maxHeight * SHOT_RATIO, (maxWidth - gap * (n - 1)) / n)
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shots.forEach { TourShot(it, still, Modifier.size(w, w / SHOT_RATIO)) }
        }
    }
}

/** Ancho / alto de las capturas (540×1083). */
private const val SHOT_RATIO = 540f / 1083f

/** Captura con borde redondeado y, encima, la zona resaltada que late. */
@Composable
private fun TourShot(shot: Shot, still: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val color = MaterialTheme.colorScheme.primary
    val pulse = if (still || shot.highlights.isEmpty()) null else rememberInfiniteTransition(label = "shot").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "shotPulse",
    )
    Box(
        modifier
            .clip(shape)
            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Image(
            painter = painterResource(shot.res),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (shot.highlights.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val a = pulse?.value ?: 1f
                for (hl in shot.highlights) {
                val pad = 3.dp.toPx()
                val topLeft = Offset(hl.left * size.width - pad, hl.top * size.height - pad)
                val box = Size(hl.width * size.width + 2 * pad, hl.height * size.height + 2 * pad)
                val corner = CornerRadius(8.dp.toPx())
                drawRoundRect(color.copy(alpha = 0.18f * a), topLeft, box, corner)
                drawRoundRect(color.copy(alpha = a), topLeft, box, corner, style = Stroke(2.5.dp.toPx()))
                }
            }
        }
    }
}
