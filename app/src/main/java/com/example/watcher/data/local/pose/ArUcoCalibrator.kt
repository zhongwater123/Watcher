package com.example.watcher.data.local.pose

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.Dictionary
import org.opencv.objdetect.Objdetect
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * ArUco marker-based dual-camera calibration.
 *
 * Uses a 6x6 ArUco marker (ID 0, known physical size) to determine
 * the spatial relationship between two cameras in one frame.
 *
 * Each camera that detects the marker yields a 3D pose (rvec, tvec).
 * Combining both poses gives the inter-camera angle, distance, and height difference.
 *
 * Uses OpenCV 4.9.0 API (ArucoDetector + solvePnP).
 */
class ArUcoCalibrator(
    private val markerSizeCm: Float = 5f
) {
    companion object {
        private const val TAG = "ArUcoCalibrator"

        // Horizontal FOV estimates (used to compute focal length from image width)
        private const val FRONT_HFOV_DEG = 70.0   // typical phone front camera
        private const val SIDE_HFOV_DEG = 118.0   // ESP32-CAM H0F3M-118 ultra-wide

        /**
         * Build camera matrix dynamically from image dimensions and estimated FOV.
         * fx = (width/2) / tan(hfov/2)
         * cx = width/2, cy = height/2
         */
        fun buildCameraMatrix(imageWidth: Int, imageHeight: Int, hfovDeg: Double): Mat {
            val fx = (imageWidth / 2.0) / kotlin.math.tan(Math.toRadians(hfovDeg / 2.0))
            val fy = fx  // square pixels assumption
            val cx = imageWidth / 2.0
            val cy = imageHeight / 2.0
            return Mat(3, 3, CvType.CV_64FC1).apply {
                put(0, 0, fx); put(0, 1, 0.0); put(0, 2, cx)
                put(1, 0, 0.0); put(1, 1, fy); put(1, 2, cy)
                put(2, 0, 0.0); put(2, 1, 0.0); put(2, 2, 1.0)
            }
        }
    }

    data class CameraPose(
        val rvec: FloatArray,       // Rodrigues rotation vector (3)
        val tvec: FloatArray,       // Translation in cm (3)
        val distanceCm: Float,      // Euclidean distance from camera to marker center
        val wasFlipped: Boolean = false  // Whether detection used flipped image
    )

    data class CalibrationResult(
        val cameraAngleDeg: Float,      // Angle between two cameras (as seen from marker)
        val cameraDistanceCm: Float,    // Distance between two cameras
        val heightDiffCm: Float,        // Height difference (positive = side is higher)
        val frontDistanceCm: Float,     // Front camera distance to marker
        val sideDistanceCm: Float,      // Side camera distance to marker
        val success: Boolean
    )

    private val dictionary: Dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_6X6_50)

    // Lenient detector parameters for low-quality MJPEG streams (ESP32)
    private val detectorParams = org.opencv.objdetect.DetectorParameters().apply {
        // Wider adaptive threshold window range for compression artifacts
        set_adaptiveThreshWinSizeMin(3)
        set_adaptiveThreshWinSizeMax(35)
        set_adaptiveThreshWinSizeStep(4)
        // More lenient corner/perimeter detection
        set_minMarkerPerimeterRate(0.02)
        set_maxMarkerPerimeterRate(4.0)
        set_polygonalApproxAccuracyRate(0.05)
        // Relaxed bit extraction
        set_perspectiveRemoveIgnoredMarginPerCell(0.2)
        set_maxErroneousBitsInBorderRate(0.5)
        // Lower otsu threshold for low-contrast images
        set_minOtsuStdDev(3.0)
    }
    private val detector: ArucoDetector = ArucoDetector(dictionary, detectorParams)

    private val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)

    // 3D object points for a single marker (centered at origin, on Z=0 plane)
    private val markerObjectPoints: MatOfPoint3f = run {
        val half = markerSizeCm / 2.0
        MatOfPoint3f(
            Point3(-half, half, 0.0),   // top-left
            Point3(half, half, 0.0),    // top-right
            Point3(half, -half, 0.0),   // bottom-right
            Point3(-half, -half, 0.0)   // bottom-left
        )
    }

    /**
     * Attempt calibration from two simultaneous frames.
     * Returns null if marker not detected in both frames.
     */
    fun calibrate(frontBitmap: Bitmap, sideBitmap: Bitmap): CalibrationResult? {
        val frontPose = detectPose(frontBitmap, FRONT_HFOV_DEG) ?: return null
        val sidePose = detectPose(sideBitmap, SIDE_HFOV_DEG) ?: return null
        return computeCalibration(frontPose, sidePose)
    }

    /**
     * Detect ArUco marker in a single frame. Returns CameraPose or null.
     * Use for real-time UI feedback (showing checkmark per camera).
     */
    fun detectInFrame(bitmap: Bitmap, isFrontCamera: Boolean = true): CameraPose? {
        val hfov = if (isFrontCamera) FRONT_HFOV_DEG else SIDE_HFOV_DEG
        return detectPose(bitmap, hfov)
    }

    /**
     * Detect from raw JPEG bytes (bypasses Android BitmapFactory → Utils.bitmapToMat).
     * Use for MJPEG streams where Bitmap conversion may lose quality.
     */
    fun detectFromJpegBytes(jpegBytes: ByteArray, isFrontCamera: Boolean = false): CameraPose? {
        val hfov = if (isFrontCamera) FRONT_HFOV_DEG else SIDE_HFOV_DEG
        return detectPoseFromBytes(jpegBytes, hfov)
    }

    /**
     * Full calibration using front Bitmap + side raw JPEG bytes.
     */
    fun calibrateWithBytes(frontBitmap: Bitmap, sideJpegBytes: ByteArray): CalibrationResult? {
        val frontPose = detectPose(frontBitmap, FRONT_HFOV_DEG) ?: return null
        val sidePose = detectPoseFromBytes(sideJpegBytes, SIDE_HFOV_DEG) ?: return null
        return computeCalibration(frontPose, sidePose, sideIsFlipped = sidePose.wasFlipped)
    }

    fun release() {
        distCoeffs.release()
        markerObjectPoints.release()
    }

    // ── Internal ──

    private fun computeCalibration(frontPose: CameraPose, sidePose: CameraPose, sideIsFlipped: Boolean = false): CalibrationResult {
        val rvecA = Mat(3, 1, CvType.CV_64FC1).apply {
            put(0, 0, frontPose.rvec[0].toDouble())
            put(1, 0, frontPose.rvec[1].toDouble())
            put(2, 0, frontPose.rvec[2].toDouble())
        }
        val rvecB = Mat(3, 1, CvType.CV_64FC1).apply {
            put(0, 0, sidePose.rvec[0].toDouble())
            put(1, 0, sidePose.rvec[1].toDouble())
            put(2, 0, sidePose.rvec[2].toDouble())
        }
        val rMatA = Mat()
        val rMatB = Mat()
        Calib3d.Rodrigues(rvecA, rMatA)
        Calib3d.Rodrigues(rvecB, rMatB)

        val camPosA = computeCameraPosition(rMatA, frontPose.tvec)
        var camPosB = computeCameraPosition(rMatB, sidePose.tvec)

        // If side image was flipped for detection, mirror the X component of camera position
        if (sideIsFlipped) {
            camPosB = floatArrayOf(-camPosB[0], camPosB[1], camPosB[2])
        }

        Log.d(TAG, "camPosA=[${camPosA.joinToString { "%.1f".format(it) }}] " +
            "camPosB=[${camPosB.joinToString { "%.1f".format(it) }}] flipped=$sideIsFlipped")

        // Angle and horizontal distance from full 3D camera positions (accurate)
        val angleDeg = angleBetweenVectors(camPosA, camPosB)

        val dx = camPosA[0] - camPosB[0]
        val dy = camPosA[1] - camPosB[1]
        val dz = camPosA[2] - camPosB[2]
        val cameraDistance = sqrt(dx * dx + dy * dy + dz * dz)

        // Height difference: use tvec.y directly (robust, avoids rotation amplification)
        // tvec.y = marker's Y position in camera frame → negative tvec.y = camera is below marker
        // heightDiff = (camera_B height) - (camera_A height)
        //            = (-side_tvec.y) - (-front_tvec.y) = front_tvec.y - side_tvec.y
        val heightDiff = frontPose.tvec[1] - sidePose.tvec[1]

        Log.d(TAG, "tvec.y: front=${frontPose.tvec[1]} side=${sidePose.tvec[1]} → heightDiff=${"%.1f".format(heightDiff)}cm")

        rvecA.release()
        rvecB.release()
        rMatA.release()
        rMatB.release()

        Log.i(TAG, "Calibration SUCCESS: angle=${"%.1f".format(angleDeg)} " +
            "dist=${"%.1f".format(cameraDistance)}cm height=${"%.1f".format(heightDiff)}cm")

        return CalibrationResult(
            cameraAngleDeg = angleDeg,
            cameraDistanceCm = cameraDistance,
            heightDiffCm = heightDiff,
            frontDistanceCm = frontPose.distanceCm,
            sideDistanceCm = sidePose.distanceCm,
            success = true
        )
    }

    private fun detectPoseFromBytes(jpegBytes: ByteArray, hfovDeg: Double): CameraPose? {
        detectCallCount++
        val rawData = MatOfByte(*jpegBytes)
        val mat = Imgcodecs.imdecode(rawData, Imgcodecs.IMREAD_COLOR)
        rawData.release()

        if (mat.empty()) {
            Log.w(TAG, "imdecode failed for ${jpegBytes.size} bytes")
            mat.release()
            return null
        }

        if (detectCallCount <= 3) {
            Log.i(TAG, "FromBytes: ${mat.cols()}x${mat.rows()} ch=${mat.channels()}")
        }

        // Build camera matrix from actual image dimensions
        val cameraMatrix = buildCameraMatrix(mat.cols(), mat.rows(), hfovDeg)

        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)

        // Try normal orientation first
        val corners = mutableListOf<Mat>()
        val ids = Mat()
        val rejected = mutableListOf<Mat>()
        detector.detectMarkers(gray, corners, ids, rejected)

        // If not found, try horizontally flipped (many ESP32-CAM modules output mirrored image)
        if (ids.rows() == 0) {
            corners.forEach { it.release() }
            corners.clear()
            ids.release()
            rejected.forEach { it.release() }
            rejected.clear()

            val flipped = Mat()
            org.opencv.core.Core.flip(gray, flipped, 1)  // 1 = horizontal flip
            gray.release()

            val idsFlip = Mat()
            val cornersFlip = mutableListOf<Mat>()
            val rejectedFlip = mutableListOf<Mat>()
            detector.detectMarkers(flipped, cornersFlip, idsFlip, rejectedFlip)

            if (detectCallCount <= 5 || idsFlip.rows() > 0) {
                Log.i(TAG, "detectBytes(flipped): markers=${idsFlip.rows()} rejected=${rejectedFlip.size}")
            }

            // Use flipped result — mark as flipped (correction applied in computeCalibration)
            val result = if (idsFlip.rows() > 0) {
                extractPose(cornersFlip, idsFlip, cameraMatrix)?.copy(wasFlipped = true)
            } else null

            flipped.release()
            mat.release()
            cameraMatrix.release()
            idsFlip.release()
            cornersFlip.forEach { it.release() }
            rejectedFlip.forEach { it.release() }
            return result
        }

        if (detectCallCount <= 5 || ids.rows() > 0) {
            Log.i(TAG, "detectBytes: markers=${ids.rows()} rejected=${rejected.size}")
        }

        val result = extractPose(corners, ids, cameraMatrix)

        cameraMatrix.release()
        mat.release()
        gray.release()
        ids.release()
        corners.forEach { it.release() }
        rejected.forEach { it.release() }
        return result
    }

    private var detectCallCount = 0

    private fun detectPose(bitmap: Bitmap, hfovDeg: Double): CameraPose? {
        detectCallCount++

        // Normalize bitmap to guaranteed ARGB_8888 via pixel copy
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val normalizedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        normalizedBitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        val mat = Mat()
        Utils.bitmapToMat(normalizedBitmap, mat)
        normalizedBitmap.recycle()

        // Build camera matrix matching actual image dimensions
        val cameraMatrix = buildCameraMatrix(mat.cols(), mat.rows(), hfovDeg)

        if (detectCallCount <= 3) {
            Log.i(TAG, "Input: bitmap=${w}x${h} mat=${mat.cols()}x${mat.rows()} ch=${mat.channels()}")
        }

        if (mat.empty()) {
            Log.w(TAG, "Mat is empty after bitmapToMat")
            mat.release()
            return null
        }

        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        val corners = mutableListOf<Mat>()
        val ids = Mat()
        val rejected = mutableListOf<Mat>()
        detector.detectMarkers(gray, corners, ids, rejected)

        if (detectCallCount <= 5 || ids.rows() > 0) {
            Log.i(TAG, "detect: markers=${ids.rows()} rejected=${rejected.size} (ids=${
                if (ids.rows() > 0) (0 until ids.rows()).map { ids.get(it, 0)[0].toInt() } else "none"
            })")
        }

        val result = extractPose(corners, ids, cameraMatrix)

        cameraMatrix.release()
        mat.release()
        gray.release()
        ids.release()
        corners.forEach { it.release() }
        rejected.forEach { it.release() }

        return result
    }

    /**
     * Extract pose from detected corners/ids. Returns null if target marker not found or solvePnP fails.
     */
    private fun extractPose(corners: List<Mat>, ids: Mat, cameraMatrix: Mat): CameraPose? {
        if (ids.rows() == 0) return null
        val targetIdx = findTargetMarkerIndex(ids)
        if (targetIdx < 0) return null

        val cornerMat = corners[targetIdx]
        val imagePoints = MatOfPoint2f(cornerMat.reshape(2, 4))

        val rvec = Mat()
        val tvec = Mat()
        val success = try {
            Calib3d.solvePnP(
                markerObjectPoints, imagePoints, cameraMatrix, distCoeffs,
                rvec, tvec, false, Calib3d.SOLVEPNP_IPPE_SQUARE
            )
        } catch (e: Exception) {
            Log.e(TAG, "solvePnP exception: ${e.message}")
            false
        }

        var result: CameraPose? = null
        if (success) {
            val rv = floatArrayOf(
                rvec.get(0, 0)[0].toFloat(),
                rvec.get(1, 0)[0].toFloat(),
                rvec.get(2, 0)[0].toFloat()
            )
            val tv = floatArrayOf(
                tvec.get(0, 0)[0].toFloat(),
                tvec.get(1, 0)[0].toFloat(),
                tvec.get(2, 0)[0].toFloat()
            )
            val distance = sqrt(tv[0] * tv[0] + tv[1] * tv[1] + tv[2] * tv[2])
            result = CameraPose(rvec = rv, tvec = tv, distanceCm = distance)
            Log.i(TAG, "Pose: dist=${"%.1f".format(distance)}cm tvec=[${tv.joinToString { "%.1f".format(it) }}]")
        }

        rvec.release()
        tvec.release()
        imagePoints.release()
        return result
    }

    private fun findTargetMarkerIndex(ids: Mat): Int {
        for (i in 0 until ids.rows()) {
            if (ids.get(i, 0)[0].toInt() == 0) return i
        }
        return -1
    }

    /**
     * Compute camera position in marker frame: -R^T * t
     */
    private fun computeCameraPosition(rMat: Mat, tvec: FloatArray): FloatArray {
        val result = FloatArray(3)
        for (i in 0..2) {
            result[i] = -(
                rMat.get(0, i)[0].toFloat() * tvec[0] +
                rMat.get(1, i)[0].toFloat() * tvec[1] +
                rMat.get(2, i)[0].toFloat() * tvec[2]
            )
        }
        return result
    }

    /**
     * Angle between two 3D vectors in degrees.
     */
    private fun angleBetweenVectors(a: FloatArray, b: FloatArray): Float {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val magA = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val magB = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (magA < 0.001f || magB < 0.001f) return 0f
        val cosAngle = (dot / (magA * magB)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosAngle).toDouble()).toFloat()
    }
}
