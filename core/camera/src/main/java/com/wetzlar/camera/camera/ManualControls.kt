package com.wetzlar.camera.camera

/**
 * Manual camera controls for ISO, shutter speed, exposure compensation, focus, and white balance.
 */
data class ManualSettings(
    val iso: Int? = null,                  // null = Auto, e.g. 100, 200, 400, 800, 1600, 3200
    val exposureTimeNs: Long? = null,      // null = Auto, e.g. 1_000_000_000L / 125 (1/125s)
    val exposureCompensationEv: Float = 0f,// -3.0 to +3.0 in 1/3 EV steps
    val focusDistanceMeters: Float? = null,// null = AF, e.g. 0.3m to 10.0m (Infinity)
    val whiteBalanceKelvin: Int? = null,   // null = AWB, e.g. 2800K, 4000K, 5500K, 6500K, 7500K
    val isFocusPeakingEnabled: Boolean = false
) {
    val shutterSpeedDisplay: String
        get() = when (val ns = exposureTimeNs) {
            null -> "AUTO"
            else -> {
                val sec = ns / 1_000_000_000.0
                if (sec >= 1.0) String.format(java.util.Locale.US, "%.1fs", sec)
                else "1/" + Math.round(1.0 / sec).toInt()
            }
        }

    val isoDisplay: String
        get() = iso?.let { "ISO $it" } ?: "AUTO"

    val evDisplay: String
        get() = String.format(java.util.Locale.US, "%+.1f EV", exposureCompensationEv)
}
