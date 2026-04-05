package com.antivocale.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
    val background: Color,
    val onClick: () -> Unit
)

@Stable
class SwipeToRevealState {
    var offsetX by mutableFloatStateOf(0f)
        internal set

    val isRevealed: Boolean get() = offsetX < -1f

    internal var scope: kotlinx.coroutines.CoroutineScope? = null

    fun reset() {
        val s = scope ?: return
        s.launch {
            animate(
                initialValue = offsetX,
                targetValue = 0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy)
            ) { value, _ ->
                offsetX = value
            }
        }
    }
}

@Composable
fun rememberSwipeToRevealState(): SwipeToRevealState {
    return remember { SwipeToRevealState() }
}

private val ACTION_BUTTON_WIDTH = 72.dp

private val SNAP_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)

@Composable
fun SwipeToRevealBox(
    state: SwipeToRevealState,
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealDistancePx = with(density) { ACTION_BUTTON_WIDTH.toPx() * actions.size }

    // Bind coroutine scope so reset() can be called from non-suspend contexts
    LaunchedEffect(state) { state.scope = scope }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RectangleShape)
    ) {
        // Background: action buttons — only visible when swiped
        Row(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (state.isRevealed) 1f else 0f },
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { action ->
                Box(
                    modifier = Modifier
                        .size(ACTION_BUTTON_WIDTH)
                        .background(action.background)
                        .clickable {
                            state.reset()
                            action.onClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = action.tint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Foreground: swipe gesture + visual offset
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = state.offsetX
                }
                .pointerInput(revealDistancePx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val current = state.offsetX
                            val target = if (current < -revealDistancePx * 0.4f) {
                                -revealDistancePx
                            } else {
                                0f
                            }
                            scope.launch {
                                animate(
                                    initialValue = current,
                                    targetValue = target,
                                    animationSpec = SNAP_SPRING
                                ) { value, _ ->
                                    state.offsetX = value
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch { state.reset() }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        state.offsetX = (state.offsetX + dragAmount)
                            .coerceAtMost(0f)
                            .coerceAtLeast(-revealDistancePx)
                    }
                }
        ) {
            content()
        }
    }
}
