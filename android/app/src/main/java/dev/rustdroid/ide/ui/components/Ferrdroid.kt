package dev.rustdroid.ide.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rustdroid.ide.R
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * R4A: Ferrdroid, la mascota. Habla en un globo (letra por letra, con un
 * saltito por letra), parpadea, mira a los lados, respira, y si pasa un rato
 * sin que toques nada se duerme ("z Z z") hasta el próximo toque.
 *
 * Los sprites son pixel art de 19×9 (res/drawable-nodpi/ferrdroid_*.png) y
 * se dibujan sin suavizado: cada píxel del dibujo es [PX] de pantalla, y
 * todos los movimientos son de a un píxel del dibujo para que se vea prolijo.
 */
@Stable
class FerrdroidState {
    /** Lo que está diciendo (null = sin globo). [messageId] cambia con cada frase nueva. */
    var message by mutableStateOf<String?>(null)
        private set
    var messageId by mutableIntStateOf(0)
        private set
    var sleeping by mutableStateOf(false)
        internal set
    internal var happyUntil by mutableLongStateOf(0L)
    internal var lastActivity = SystemClock.uptimeMillis()
    internal var wokeAt = 0L
    internal var wakeText: String = ""

    fun say(text: String) {
        message = text
        messageId++
    }

    fun hush() { message = null }

    /** Ferrdroid pone la cara de contento un rato. */
    fun cheer(ms: Long = 1500) { happyUntil = SystemClock.uptimeMillis() + ms }

    /** Cualquier toque en la pantalla: cuenta como actividad y lo despierta. */
    fun onActivity() {
        lastActivity = SystemClock.uptimeMillis()
        if (sleeping) {
            sleeping = false
            wokeAt = lastActivity
            cheer()
            say(wakeText)
        }
    }
}

@Composable
fun rememberFerrdroidState(): FerrdroidState = remember { FerrdroidState() }

/** Avisa a Ferrdroid de cada toque en esta zona, sin quitarle el toque a nadie. */
fun Modifier.ferrdroidActivity(state: FerrdroidState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            state.onActivity()
        }
    }
}

private val PX = 4.dp            // tamaño de un píxel del dibujo
private const val SPRITE_W = 19
private const val SPRITE_H = 9
private const val SLEEP_AFTER_MS = 30_000L
private const val BUBBLE_HIDE_MS = 9_000L
private val HEADER_HEIGHT = 104.dp
private val SPRITE_TOP_ROOM = 18.dp   // lugar arriba del sprite para las "z"
private val SPRITE_BOX_HEIGHT = SPRITE_TOP_ROOM + PX * SPRITE_H

private enum class Look { CENTER, LEFT, RIGHT }

/** true si el celu tiene las animaciones desactivadas (accesibilidad / opciones de desarrollador). */
private fun animationsOff(context: Context): Boolean =
    android.provider.Settings.Global.getFloat(
        context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f

/** "Blip" por letra (opcional, Opciones → Apariencia). */
private class Blipper(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        ).build()
    private val sound = pool.load(context, R.raw.ferrdroid_blip, 1)
    fun play() { pool.play(sound, 0.35f, 0.35f, 0, 0, Random.nextDouble(0.9, 1.15).toFloat()) }
    fun release() = pool.release()
}

/**
 * Ferrdroid con su globo a la derecha. Tocarlo lo pone contento y le hace
 * decir uno de [tips]. Dormido, cualquier toque lo despierta con [wakeLines].
 */
@Composable
fun Ferrdroid(
    state: FerrdroidState,
    tips: List<String>,
    wakeLines: List<String>,
    soundOn: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val still = remember { animationsOff(context) }
    var shown by remember { mutableStateOf("") }
    var talking by remember { mutableStateOf(false) }
    var bubbleVisible by remember { mutableStateOf(false) }
    var hop by remember { mutableStateOf(false) }
    var blink by remember { mutableStateOf(false) }
    var breath by remember { mutableStateOf(false) }
    var look by remember { mutableStateOf(Look.CENTER) }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    state.wakeText = remember(wakeLines) { wakeLines.randomOrNull().orEmpty() }

    val blipper = remember(soundOn) { if (soundOn) Blipper(context) else null }
    DisposableEffect(blipper) { onDispose { blipper?.release() } }

    // reloj: se duerme tras un rato sin actividad; el globo se esconde solo
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = SystemClock.uptimeMillis()
            if (!state.sleeping && !talking && now - state.lastActivity > SLEEP_AFTER_MS) {
                state.sleeping = true
                state.wakeText = wakeLines.randomOrNull().orEmpty()
                state.hush()
            }
        }
    }
    // parpadeo cada 3–6 s
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(3000, 6000))
            if (!state.sleeping) { blink = true; delay(130); blink = false }
        }
    }
    // mira a los lados cada tanto, si no está hablando
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(5000, 10000))
            if (!state.sleeping && !talking) {
                look = if (Random.nextBoolean()) Look.LEFT else Look.RIGHT
                delay(1200)
                look = Look.CENTER
            }
        }
    }
    // respira: sube y baja un píxel
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        while (true) {
            delay(900)
            breath = !breath && !state.sleeping && !talking
        }
    }
    // habla: letra por letra, un saltito por letra
    LaunchedEffect(state.messageId) {
        val text = state.message
        if (text.isNullOrEmpty()) { bubbleVisible = false; return@LaunchedEffect }
        bubbleVisible = true
        talking = true
        if (still) {
            shown = text
        } else {
            shown = ""
            for (ch in text) {
                shown += ch
                if (ch.isLetterOrDigit()) {
                    hop = true
                    blipper?.play()
                    delay(45)
                    hop = false
                    delay(15)
                } else {
                    delay(if (ch in ".,!?¡¿:;…") 180 else 30)
                }
            }
        }
        talking = false
        delay(BUBBLE_HIDE_MS)
        bubbleVisible = false
    }

    val pose = when {
        state.sleeping -> R.drawable.ferrdroid_durmiendo
        now < state.happyUntil -> R.drawable.ferrdroid_contento
        blink -> R.drawable.ferrdroid_parpadeo
        talking -> R.drawable.ferrdroid_derecha
        look == Look.LEFT -> R.drawable.ferrdroid_izquierda
        look == Look.RIGHT -> R.drawable.ferrdroid_derecha
        else -> R.drawable.ferrdroid_normal
    }
    val lift = when {
        hop -> -PX
        breath -> -PX
        else -> 0.dp
    }

    // el piso: una línea a la altura de las patas, de lado a lado
    val groundColor = MaterialTheme.colorScheme.outlineVariant
    // altura fija: lugar para un globo de hasta 3 líneas, así la lista no salta al hablar
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = HEADER_HEIGHT)
            .drawBehind {
                // el sprite (con su margen de arriba) está centrado: sus patas quedan en (alto + caja) / 2
                val feet = (size.height + SPRITE_BOX_HEIGHT.toPx()) / 2f
                val stroke = 2.dp.toPx()
                drawLine(groundColor, Offset(0f, feet + stroke / 2), Offset(size.width, feet + stroke / 2), stroke)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(top = SPRITE_TOP_ROOM, end = 4.dp)) {
            Image(
                bitmap = ImageBitmap.imageResource(pose),
                contentDescription = stringResource(R.string.ferrdroid_cd),
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .offset(y = lift)
                    .size(PX * SPRITE_W, PX * SPRITE_H)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        // el toque que lo despertó ya le hizo decir algo
                        if (SystemClock.uptimeMillis() - state.wokeAt < 400) return@clickable
                        state.cheer()
                        tips.randomOrNull()?.let(state::say)
                    },
            )
            if (state.sleeping) Zzz(still, Modifier.align(Alignment.TopEnd).offset(x = 22.dp, y = (-26).dp))
        }
        AnimatedVisibility(
            visible = bubbleVisible && !state.sleeping,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.9f),
            exit = fadeOut(tween(300)),
            modifier = Modifier.weight(1f),
        ) {
            SpeechBubble(state.message.orEmpty(), shown.length)
        }
    }
}

/** Globo de texto con la punta hacia Ferrdroid (a la izquierda). */
@Composable
private fun SpeechBubble(text: String, revealed: Int) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    val line = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(14.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 9.dp, height = 14.dp)) {
            val p = Path().apply {
                moveTo(size.width + 1f, 0f)
                lineTo(0f, size.height / 2)
                lineTo(size.width + 1f, size.height)
                close()
            }
            drawPath(p, bg)
            drawLine(line, Offset(size.width, 0f), Offset(0f, size.height / 2), strokeWidth = 1.dp.toPx())
            drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        // la frase entera ocupa su lugar desde el principio; lo que falta
        // escribir va transparente (así el globo no crece letra por letra)
        val textColor = MaterialTheme.colorScheme.onSurface
        val shownText = androidx.compose.ui.text.buildAnnotatedString {
            val n = revealed.coerceIn(0, text.length)
            append(text.substring(0, n))
            pushStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color.Transparent))
            append(text.substring(n))
            pop()
        }
        Text(
            shownText,
            // Press Start 2P: letra pixel, en múltiplos de su grilla de 8 para que se vea nítida
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = dev.rustdroid.ide.ui.theme.PixelFamily,
                fontSize = 10.sp,
                lineHeight = 17.sp,
            ),
            color = textColor,
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 40.dp)
                .background(bg, shape)
                .border(1.dp, line, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** "z Z z" que suben y se desvanecen mientras duerme. */
@Composable
private fun Zzz(still: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "zzz")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "zzzPhase",
    )
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.width(36.dp).heightIn(min = 30.dp)) {
        listOf(0f, 0.33f, 0.66f).forEachIndexed { i, delayFrac ->
            val p = if (still) 0.5f else (phase + 1f - delayFrac) % 1f
            Text(
                if (i == 1) "Z" else "z",
                color = color,
                fontFamily = dev.rustdroid.ide.ui.theme.PixelFamily,
                fontSize = (8 + i * 3).sp,
                modifier = Modifier
                    .offset(x = (i * 10).dp, y = (18 - p * 22).dp)
                    .alpha(if (still) 1f else (1f - p) * minOf(1f, p * 4f)),
            )
        }
    }
}

@Composable
private fun stringResource(id: Int) = androidx.compose.ui.res.stringResource(id)
