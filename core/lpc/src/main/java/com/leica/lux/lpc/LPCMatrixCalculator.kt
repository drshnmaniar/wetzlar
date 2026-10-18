package com.leica.lux.lpc

import kotlin.math.cos
import kotlin.math.sin

/** A row-major 3x3 projective transform in pixel coordinates. */
data class PerspectiveTransform(val values: FloatArray) {
    init { require(values.size == 9) { "A perspective transform needs 9 values" } }

    /** Maps a source pixel to its corrected output position, or null at the projective horizon. */
    fun map(x: Float, y: Float): PointF? {
        val w = values[6] * x + values[7] * y + values[8]
        if (!w.isFinite() || kotlin.math.abs(w) < 1e-7f) return null
        return PointF(
            (values[0] * x + values[1] * y + values[2]) / w,
            (values[3] * x + values[4] * y + values[5]) / w,
        )
    }
}

data class PointF(val x: Float, val y: Float)

/**
 * Computes an ideal pinhole-camera homography that levels measured pitch and roll.
 * Angles are radians. The output maps source-image coordinates into the leveled camera plane;
 * callers still need to rasterize/resample the image and choose a crop. Lens distortion, sensor
 * calibration, focal length metadata and optical stabilization are outside this geometric model.
 */
object LPCMatrixCalculator {
    fun calculate(
        pitchRadians: Float,
        rollRadians: Float,
        focalLengthPixels: Float,
        principalX: Float,
        principalY: Float,
    ): PerspectiveTransform {
        require(pitchRadians.isFinite() && rollRadians.isFinite()) { "Angles must be finite" }
        require(focalLengthPixels.isFinite() && focalLengthPixels > 0f) { "Focal length must be positive" }
        require(principalX.isFinite() && principalY.isFinite()) { "Principal point must be finite" }

        // Intrinsics and their inverse for a square-pixel, zero-skew pinhole camera.
        val k = floatArrayOf(focalLengthPixels, 0f, principalX, 0f, focalLengthPixels, principalY, 0f, 0f, 1f)
        val invK = floatArrayOf(
            1f / focalLengthPixels, 0f, -principalX / focalLengthPixels,
            0f, 1f / focalLengthPixels, -principalY / focalLengthPixels,
            0f, 0f, 1f,
        )
        val cp = cos(pitchRadians); val sp = sin(pitchRadians)
        val cr = cos(rollRadians); val sr = sin(rollRadians)
        val rotateX = floatArrayOf(1f, 0f, 0f, 0f, cp, -sp, 0f, sp, cp)
        val rotateZ = floatArrayOf(cr, -sr, 0f, sr, cr, 0f, 0f, 0f, 1f)
        val rotation = multiply(rotateX, rotateZ)
        val homography = multiply(multiply(k, rotation), invK)
        val scale = homography[8].takeIf { kotlin.math.abs(it) > 1e-7f } ?: 1f
        return PerspectiveTransform(FloatArray(9) { homography[it] / scale })
    }

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { index ->
        val row = index / 3
        val column = index % 3
        a[row * 3] * b[column] + a[row * 3 + 1] * b[column + 3] + a[row * 3 + 2] * b[column + 6]
    }
}
