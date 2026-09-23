package com.jrs8205.appletvremote.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jrs8205.appletvremote.data.NavigationMode
import com.jrs8205.appletvremote.protocol.companion.HidButton
import com.jrs8205.appletvremote.protocol.companion.TouchPhase
import com.jrs8205.appletvremote.ui.theme.RemoteColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actions the click pad can raise; the screen maps them to remote commands. */
interface ClickPadActions {
    fun click(button: HidButton)
    fun touch(phase: TouchPhase, x: Int, y: Int)
}

/**
 * The round pad of the Siri Remote. Touchpad mode streams relative movement like a trackpad,
 * swipe mode turns each 56 dp of movement into one arrow press, d-pad mode has five tap zones.
 * A tap without movement selects in every mode.
 */
@Composable
fun ClickPad(
    mode: NavigationMode,
    actions: ClickPadActions,
    haptics: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val current by rememberUpdatedState(actions)
    val padColor = if (isSystemInDarkTheme()) RemoteColors.PadDark else RemoteColors.PadLight
    val tick: () -> Unit = { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.ContextClick) }

    Box(
        modifier = modifier
            .size(size)
            .background(padColor, CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(mode, haptics) {
                val tapSlopPx = with(density) { TAP_SLOP_DP.dp.toPx() }
                val swipeStepPx = with(density) { SWIPE_STEP_DP.dp.toPx() }
                val trackpadScalePx = with(density) { TRACKPAD_SCALE_DP.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Main)
                    down.consume()
                    val bounds = Rect(Offset.Zero, Size(this.size.width.toFloat(), this.size.height.toFloat()))
                    var last = down.position
                    var travelled = 0f
                    var accX = 0f
                    var accY = 0f
                    var padX = 0.5f
                    var padY = 0.5f
                    if (mode == NavigationMode.TOUCHPAD) current.touch(TouchPhase.PRESS, 500, 500)
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.position - last
                        last = change.position
                        travelled += abs(delta.x) + abs(delta.y)
                        when (mode) {
                            NavigationMode.TOUCHPAD -> {
                                padX = (padX + delta.x / trackpadScalePx).coerceIn(0f, 1f)
                                padY = (padY + delta.y / trackpadScalePx).coerceIn(0f, 1f)
                                if (change.pressed) {
                                    current.touch(TouchPhase.HOLD, (padX * 1000).roundToInt(), (padY * 1000).roundToInt())
                                }
                            }
                            NavigationMode.SWIPE -> {
                                accX += delta.x
                                accY += delta.y
                                while (abs(accX) >= swipeStepPx || abs(accY) >= swipeStepPx) {
                                    if (abs(accX) >= abs(accY)) {
                                        tick()
                                        current.click(if (accX > 0) HidButton.RIGHT else HidButton.LEFT)
                                        accX -= swipeStepPx * if (accX > 0) 1 else -1
                                        accY = 0f
                                    } else {
                                        tick()
                                        current.click(if (accY > 0) HidButton.DOWN else HidButton.UP)
                                        accY -= swipeStepPx * if (accY > 0) 1 else -1
                                        accX = 0f
                                    }
                                }
                            }
                            NavigationMode.DPAD -> Unit
                        }
                        change.consume()
                        if (!change.pressed) released = true
                    }
                    if (mode == NavigationMode.TOUCHPAD) {
                        current.touch(TouchPhase.RELEASE, (padX * 1000).roundToInt(), (padY * 1000).roundToInt())
                    }
                    if (travelled <= tapSlopPx) {
                        tick()
                        val button = when (mode) {
                            NavigationMode.DPAD -> when (padRegion(down.position, bounds)) {
                                PadRegion.CENTER -> HidButton.SELECT
                                PadRegion.UP -> HidButton.UP
                                PadRegion.DOWN -> HidButton.DOWN
                                PadRegion.LEFT -> HidButton.LEFT
                                PadRegion.RIGHT -> HidButton.RIGHT
                            }
                            else -> HidButton.SELECT
                        }
                        current.click(button)
                    }
                }
            },
    ) {
        PadDecoration(mode)
    }
}

@Composable
private fun PadDecoration(mode: NavigationMode) {
    val ring = Color.White.copy(alpha = 0.16f)
    val mark = Color.White.copy(alpha = 0.55f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(ring, radius = radius * 0.94f, center = center, style = Stroke(width = 2.dp.toPx()))
        if (mode == NavigationMode.DPAD) {
            drawCircle(ring, radius = radius * 0.38f, center = center, style = Stroke(width = 2.dp.toPx()))
            val arm = radius * 0.72f
            val half = radius * 0.07f
            listOf(0f, 90f, 180f, 270f).forEach { angle ->
                rotate(angle, center) {
                    val tip = Offset(center.x, center.y - arm - half)
                    val path = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(tip.x - half * 1.4f, tip.y + half * 2f)
                        lineTo(tip.x + half * 1.4f, tip.y + half * 2f)
                        close()
                    }
                    drawPath(path, mark)
                }
            }
        } else {
            drawCircle(mark, radius = 3.dp.toPx(), center = center)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.rotate(degrees: Float, pivot: Offset, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    drawContext.transform.rotate(degrees, pivot)
    block()
    drawContext.transform.rotate(-degrees, pivot)
}

private const val TAP_SLOP_DP = 20
private const val SWIPE_STEP_DP = 56
private const val TRACKPAD_SCALE_DP = 600
