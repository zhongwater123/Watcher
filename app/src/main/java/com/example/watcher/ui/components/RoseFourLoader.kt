package com.example.watcher.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.pow

/**
 * Rose Four mathematical curve loading animation.
 * Formula: r(t) = (9.2 + 0.6·s)(0.72 + 0.28·s) cos(4t)
 */
@Composable
fun RoseFourLoader(
    modifier: Modifier = Modifier,
    active: Boolean = true
) {
    val color = MaterialTheme.colorScheme.primary
    if (!active) {
        Canvas(modifier = modifier) {
            val radius = size.minDimension * 0.32f
            drawCircle(color = color.copy(alpha = 0.18f), radius = radius, center = center)
            drawCircle(
                color = color.copy(alpha = 0.34f),
                radius = radius,
                center = center,
                style = Stroke(width = size.minDimension * 0.04f)
            )
        }
        return
    }

    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "rose")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(5400, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "progress"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(4500, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "pulse"
    )
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(28000, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "rotation"
    )
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = size.width / 100f
        val pi2 = (Math.PI * 2).toFloat()

        val detailScale = 0.52f + ((kotlin.math.sin((pulse * pi2 + 0.55f).toDouble()).toFloat() + 1f) / 2f) * 0.48f
        val particleCount = 48
        val trailSpan = 0.32f

        rotate(rotation, Offset(cx, cy)) {
            // Background path
            val path = Path().apply {
                for (i in 0..240) {
                    val t = (i / 240f) * pi2
                    val a = 9.2f + detailScale * 0.6f
                    val r = a * (0.72f + detailScale * 0.28f) * kotlin.math.cos((4f * t).toDouble()).toFloat()
                    val px = cx + kotlin.math.cos(t.toDouble()).toFloat() * r * scale * 3.25f
                    val py = cy + kotlin.math.sin(t.toDouble()).toFloat() * r * scale * 3.25f
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            drawPath(path, color = color.copy(alpha = 0.08f), style = Stroke(width = 1.5f * scale))

            // Particles
            for (i in 0 until particleCount) {
                val tailOffset = i.toFloat() / (particleCount - 1)
                val particleProgress = ((progress - tailOffset * trailSpan) % 1f + 1f) % 1f
                val t = particleProgress * pi2
                val a = 9.2f + detailScale * 0.6f
                val r = a * (0.72f + detailScale * 0.28f) * kotlin.math.cos((4f * t).toDouble()).toFloat()
                val px = cx + kotlin.math.cos(t.toDouble()).toFloat() * r * scale * 3.25f
                val py = cy + kotlin.math.sin(t.toDouble()).toFloat() * r * scale * 3.25f
                val fade = (1.0 - tailOffset).toDouble().pow(0.56).toFloat()
                val radius = (0.5f + fade * 1.8f) * scale
                val alpha = 0.04f + fade * 0.96f
                drawCircle(color = color.copy(alpha = alpha), radius = radius, center = Offset(px, py))
            }
        }
    }
}
