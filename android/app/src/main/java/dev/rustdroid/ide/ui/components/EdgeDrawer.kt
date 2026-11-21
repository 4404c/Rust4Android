package dev.rustdroid.ide.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * R4A: state of [EdgeDrawer]. [progress] goes from 0 (closed) to 1 (open)
 * and follows the finger while dragging; [open]/[close] animate.
 */
@Stable
class EdgeDrawerState {
    internal val progress = Animatable(0f)

    /** True while the drawer is visible at all (also mid-drag). */
    val isOpen: Boolean get() = progress.value > 0f || progress.targetValue > 0f

    suspend fun open() = progress.animateTo(1f, spring(stiffness = 600f))

    suspend fun close() = progress.animateTo(0f, spring(stiffness = 600f))

    internal suspend fun dragBy(fraction: Float) =
        progress.snapTo((progress.value + fraction).coerceIn(0f, 1f))

    /** Finish a drag: fling wins, otherwise snap to the nearest side. */
    internal suspend fun settle(velocityFractionPerSec: Float) {
        val open = when {
            velocityFractionPerSec > FLING -> true
            velocityFractionPerSec < -FLING -> false
            else -> progress.value > 0.5f
        }
        progress.animateTo(
            if (open) 1f else 0f,
            spring(stiffness = 600f),
            initialVelocity = velocityFractionPerSec,
        )
    }

    private companion object {
        /** Drawer widths per second that count as a fling. */
        const val FLING = 1.2f
    }
}

@Composable
fun rememberEdgeDrawerState(): EdgeDrawerState = remember { EdgeDrawerState() }

/**
 * A left drawer that tracks the finger, opened by swiping from a thin strip
 * on the left edge. Unlike Material's ModalNavigationDrawer it never listens
 * to gestures over [content] while closed — the code editor is an Android
 * View (sora) whose scroll and long-press selection fought with the drawer's
 * full-screen drag detector (see the note in EditorScreen).
 *
 * [edgeTopPadding] keeps the strip below the top bar so its buttons work.
 */
@Composable
fun EdgeDrawer(
    state: EdgeDrawerState,
    drawerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    edgeTopPadding: Dp = 64.dp,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sheetWidth = min(maxWidth - 56.dp, 360.dp)
        val widthPx = with(LocalDensity.current) { sheetWidth.toPx() }

        content()

        // Edge strip: the only gesture surface while closed. It also asks
        // Android to keep the system back gesture off this edge (honoured
        // for part of the screen height only).
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = edgeTopPadding)
                .width(20.dp)
                .systemGestureExclusion()
                .drawerDrag(state, widthPx, scope),
        )

        // Only composed while visible; derivedStateOf keeps the editor from
        // recomposing on every drag frame.
        val visible by remember { derivedStateOf { state.progress.value > 0f } }
        if (visible) {
            // Untranslated full-size layer: drag deltas are measured here, in
            // screen space, so the moving sheet does not distort them.
            Box(Modifier.fillMaxSize().drawerDrag(state, widthPx, scope)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = state.progress.value }
                        .background(Color.Black.copy(alpha = 0.4f))
                        .pointerInput(Unit) {
                            detectTapGestures { scope.launch { state.close() } }
                        },
                )
                Box(
                    Modifier
                        .width(sheetWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            translationX = -widthPx * (1f - state.progress.value)
                        },
                ) {
                    drawerContent()
                }
            }
        }
    }
}

private fun Modifier.drawerDrag(
    state: EdgeDrawerState,
    widthPx: Float,
    scope: CoroutineScope,
): Modifier = pointerInput(widthPx) {
    val tracker = VelocityTracker()
    detectHorizontalDragGestures(
        onDragStart = { tracker.resetTracking() },
        onDragEnd = {
            val v = tracker.calculateVelocity().x / widthPx
            scope.launch { state.settle(v) }
        },
        onDragCancel = { scope.launch { state.settle(0f) } },
        onHorizontalDrag = { change, amount ->
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            scope.launch { state.dragBy(amount / widthPx) }
        },
    )
}
