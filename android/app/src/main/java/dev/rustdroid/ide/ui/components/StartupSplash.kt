package dev.rustdroid.ide.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rustdroid.ide.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * R4A: lo que falta para que Inicio esté listo al abrir la app. Cada paso lo
 * marca quien lo hace de verdad (MainActivity, AppRoot, HomeViewModel); la
 * pantalla de carga sólo muestra la suma.
 */
object StartupProgress {
    enum class Step(val weight: Float) {
        APP(0.2f),         // Application + Activity creadas (ajustes, contenedor)
        TOOLCHAIN(0.2f),   // estado de la toolchain leído del disco
        PROJECTS(0.35f),   // lista de proyectos leída (o no hace falta: pantalla de instalación)
        FIRST_FRAME(0.25f) // la pantalla de abajo ya se dibujó
    }

    private val _done = MutableStateFlow<Set<Step>>(emptySet())
    val done: StateFlow<Set<Step>> = _done.asStateFlow()

    /** Ya se mostró en este proceso (girar la pantalla o cambiar idioma no la repite). */
    @Volatile var finished = false

    fun mark(step: Step) {
        val before = _done.value
        _done.update { it + step }
        if (before.size < Step.entries.size && _done.value.size == Step.entries.size) {
            // R4A: cuánto tardó en estar listo Inicio desde que arrancó el proceso (para medir)
            val ms = android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()
            android.util.Log.i("R4A", "startup ready in ${ms}ms")
        }
    }
    fun markAll() = _done.update { Step.entries.toSet() }
}

private const val SPRITE_W = 19
private const val SPRITE_H = 9
private val PX = 9.dp
/** Tope por si algo se cuelga (una carpeta externa lenta): no es una demora, es un límite. */
private const val MAX_WAIT_MS = 5_000L

/**
 * Ferrdroid se va llenando de color, píxel por píxel de abajo hacia arriba,
 * a medida que avanzan los pasos de [StartupProgress]. Al llegar al 100 %
 * pone cara de contento y [onFinished] la saca.
 */
@Composable
fun StartupSplash(onFinished: () -> Unit) {
    val context = LocalContext.current
    val still = remember { animationsOff(context) }
    val done by StartupProgress.done.collectAsStateWithLifecycle()
    val target = done.sumOf { it.weight.toDouble() }.toFloat().coerceAtMost(1f)
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = if (still) tween(0) else tween(160, easing = LinearEasing),
        label = "splashFill",
    )

    val normal = ImageBitmap.imageResource(R.drawable.ferrdroid_normal)
    val happy = ImageBitmap.imageResource(R.drawable.ferrdroid_contento)
    // píxeles opacos ordenados de abajo hacia arriba, de izquierda a derecha
    val order = remember(normal) { opaquePixels(normal) }
    val happyPx = remember(happy) { IntArray(SPRITE_W * SPRITE_H).also { happy.readPixels(it) } }
    val normalPx = remember(normal) { IntArray(SPRITE_W * SPRITE_H).also { normal.readPixels(it) } }

    LaunchedEffect(Unit) {
        delay(MAX_WAIT_MS)
        StartupProgress.markAll()
    }
    val complete = shown >= 1f
    LaunchedEffect(complete) {
        if (!complete) return@LaunchedEffect
        if (!still) delay(140) // un instante con la cara de contento
        StartupProgress.finished = true
        onFinished()
    }

    // gris sólido (con transparencia, los bordes que se pisan se veían como una grilla)
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
        .compositeOver(MaterialTheme.colorScheme.background)
    val desc = stringResource(R.string.startup_loading)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .semantics { contentDescription = desc },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(PX * SPRITE_W, PX * SPRITE_H)) {
            val px = size.width / SPRITE_W
            val revealed = (shown * order.size).toInt()
            val cell = Size(px + 0.5f, px + 0.5f) // sin rayitas entre píxeles
            order.forEachIndexed { i, idx ->
                val color = when {
                    complete -> Color(happyPx[idx])
                    i < revealed -> Color(normalPx[idx])
                    else -> empty
                }
                if (color.alpha == 0f) return@forEachIndexed
                drawRect(color, Offset((idx % SPRITE_W) * px, (idx / SPRITE_W) * px), cell)
            }
            // los píxeles que sólo existen en la cara de contento
            if (complete) for (idx in happyPx.indices) {
                if (normalPx[idx] ushr 24 == 0 && happyPx[idx] ushr 24 != 0) {
                    drawRect(Color(happyPx[idx]), Offset((idx % SPRITE_W) * px, (idx / SPRITE_W) * px), cell)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            desc,
            fontFamily = dev.rustdroid.ide.ui.theme.PixelFamily,
            fontSize = 8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(0.8f),
        )
    }
}

private fun opaquePixels(bmp: ImageBitmap): IntArray {
    val px = IntArray(SPRITE_W * SPRITE_H)
    bmp.readPixels(px)
    val out = ArrayList<Int>(px.size)
    for (y in SPRITE_H - 1 downTo 0) for (x in 0 until SPRITE_W) {
        val idx = y * SPRITE_W + x
        if (px[idx] ushr 24 != 0) out += idx
    }
    return out.toIntArray()
}
