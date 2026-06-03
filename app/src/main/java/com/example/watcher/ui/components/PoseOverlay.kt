package com.example.watcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.example.watcher.data.local.pose.NormalizedLandmark
import com.example.watcher.data.local.pose.PoseDetectionResult
import com.example.watcher.data.local.pose.PoseSkeleton

private val POSE_COLORS = listOf(
    Color(0xFF00E676),  // Green
    Color(0xFF2979FF),  // Blue
    Color(0xFFFF6D00),  // Orange
    Color(0xFFAA00FF),  // Purple
    Color(0xFFFFD600)   // Yellow
)

/** Rainbow colors for move segmentation visualization */
val MOVE_RAINBOW_COLORS = listOf(
    Color(0xFFEF5350),  // Red
    Color(0xFFFF9800),  // Orange
    Color(0xFFFFEB3B),  // Yellow
    Color(0xFF4CAF50),  // Green
    Color(0xFF00BCD4),  // Cyan
    Color(0xFF2196F3),  // Blue
    Color(0xFF9C27B0)   // Purple
)

private const val LANDMARK_RADIUS = 6f
private const val CONNECTION_STROKE_WIDTH = 4f
private const val VISIBILITY_THRESHOLD = 0.5f

/**
 * Draws pose skeleton overlay on a Canvas that matches the image display area.
 *
 * @param result The pose detection result with normalized landmarks (0-1).
 * @param mirrorHorizontally If true, flip x-coordinates (for front camera selfie view).
 * @param imageAspectRatio The aspect ratio of the source image (width/height).
 *        Used to compute the actual image rect within the canvas when ContentScale.Fit is used.
 */
@Composable
fun PoseOverlay(
    result: PoseDetectionResult?,
    modifier: Modifier = Modifier,
    mirrorHorizontally: Boolean = false,
    imageAspectRatio: Float = 4f / 3f,
    moveColorIndex: Int = -1  // -1 = default colors, >=0 = rainbow index for segmentation
) {
    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        if (canvasWidth <= 0f || canvasHeight <= 0f) return@Canvas

        // Compute the image rect within the canvas (ContentScale.Fit behavior)
        val canvasAspect = canvasWidth / canvasHeight
        val imageWidth: Float
        val imageHeight: Float
        val offsetX: Float
        val offsetY: Float

        if (imageAspectRatio > canvasAspect) {
            // Image is wider than canvas: full width, letterboxed vertically
            imageWidth = canvasWidth
            imageHeight = canvasWidth / imageAspectRatio
            offsetX = 0f
            offsetY = (canvasHeight - imageHeight) / 2f
        } else {
            // Image is taller than canvas: full height, pillarboxed horizontally
            imageHeight = canvasHeight
            imageWidth = canvasHeight * imageAspectRatio
            offsetX = (canvasWidth - imageWidth) / 2f
            offsetY = 0f
        }

        result?.landmarks?.forEachIndexed { poseIndex, poseSet ->
            val color = if (moveColorIndex >= 0) {
                MOVE_RAINBOW_COLORS[moveColorIndex % MOVE_RAINBOW_COLORS.size]
            } else {
                POSE_COLORS[poseIndex % POSE_COLORS.size]
            }
            val connectionColor = color.copy(alpha = 0.7f)

            fun mapPoint(landmark: NormalizedLandmark): Offset {
                val nx = if (mirrorHorizontally) 1f - landmark.x else landmark.x
                return Offset(
                    x = offsetX + nx * imageWidth,
                    y = offsetY + landmark.y * imageHeight
                )
            }

            // Draw connections
            PoseSkeleton.connections.forEach { (startIdx, endIdx) ->
                val start = poseSet.normalizedLandmarks.getOrNull(startIdx) ?: return@forEach
                val end = poseSet.normalizedLandmarks.getOrNull(endIdx) ?: return@forEach

                if (start.visibility < VISIBILITY_THRESHOLD || end.visibility < VISIBILITY_THRESHOLD) {
                    return@forEach
                }

                drawLine(
                    color = connectionColor,
                    start = mapPoint(start),
                    end = mapPoint(end),
                    strokeWidth = CONNECTION_STROKE_WIDTH,
                    cap = StrokeCap.Round
                )
            }

            // Draw landmarks
            poseSet.normalizedLandmarks.forEach { landmark ->
                if (landmark.visibility >= VISIBILITY_THRESHOLD) {
                    val pointColor = color.copy(alpha = landmark.visibility.coerceIn(0.4f, 1f))
                    drawCircle(
                        color = pointColor,
                        radius = LANDMARK_RADIUS,
                        center = mapPoint(landmark)
                    )
                }
            }
        }
    }
}
