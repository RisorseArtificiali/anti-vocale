package com.antivocale.app.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.antivocale.app.R

@Composable
fun SkeletonLine(
    progress: Float,
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 12.dp,
    shape: Shape = RoundedCornerShape(3.dp)
) {
    val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(shape)
            .drawBehind {
                drawRect(color = baseColor)
                val shimmerWidth = size.width * 0.6f
                val offset = (progress * (size.width + shimmerWidth)) - shimmerWidth
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, highlightColor, Color.Transparent),
                        startX = offset,
                        endX = offset + shimmerWidth
                    )
                )
            }
    )
}

@Composable
fun SkeletonTranscriptionPreview(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_sweep"
    )

    Column(modifier = modifier) {
        SkeletonLine(progress = progress, widthFraction = 0.85f, height = 14.dp)
        Spacer(modifier = Modifier.height(6.dp))
        SkeletonLine(progress = progress, widthFraction = 0.6f, height = 14.dp)
    }
}

@Composable
fun SkeletonTranscriptionCard(
    modifier: Modifier = Modifier
) {
    val contentDescription = stringResource(R.string.skeleton_loading_content_description)
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_sweep"
    )

    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            this.contentDescription = contentDescription
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonLine(
                    progress = progress,
                    modifier = Modifier.size(16.dp),
                    shape = CircleShape,
                    height = 16.dp
                )
                Spacer(modifier = Modifier.width(4.dp))
                SkeletonLine(progress = progress, widthFraction = 0.15f, height = 10.dp)
                Spacer(modifier = Modifier.width(4.dp))
                SkeletonLine(progress = progress, widthFraction = 0.2f, height = 8.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonLine(
                    progress = progress,
                    modifier = Modifier.size(14.dp),
                    shape = CircleShape,
                    height = 14.dp
                )
                Spacer(modifier = Modifier.width(4.dp))
                SkeletonLine(progress = progress, widthFraction = 0.12f, height = 8.dp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        SkeletonLine(progress = progress, widthFraction = 0.85f, height = 14.dp)
        Spacer(modifier = Modifier.height(6.dp))
        SkeletonLine(progress = progress, widthFraction = 0.6f, height = 14.dp)
    }
}
