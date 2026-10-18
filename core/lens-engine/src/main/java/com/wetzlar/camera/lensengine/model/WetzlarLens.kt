package com.wetzlar.camera.lensengine.model

/**
 * Lens profiles for selectable focal lengths and apertures, with optional optical effects.
 */
enum class WetzlarLens(
    val id: String,
    val title: String,
    val focalLengthMm: Int,
    val maxAperture: Float,
    val selectableApertures: List<Float>,
    val hasSoftLook: Boolean = false,
    val isPaid: Boolean = false,
    val fixedLook: WetzlarLook? = null
) {
    PRIME_28(
        id = "prime28",
        title = "28 mm f/1.4",
        focalLengthMm = 28,
        maxAperture = 1.4f,
        selectableApertures = listOf(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = false
    ),
    PRIME_35(
        id = "prime35",
        title = "35 mm f/1.4",
        focalLengthMm = 35,
        maxAperture = 1.4f,
        selectableApertures = listOf(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = false
    ),
    PRIME_50(
        id = "prime50",
        title = "50 mm f/1.2",
        focalLengthMm = 50,
        maxAperture = 1.2f,
        selectableApertures = listOf(1.2f, 1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = false
    ),
    PRIME_75(
        id = "prime75",
        title = "75 mm f/1.25",
        focalLengthMm = 75,
        maxAperture = 1.25f,
        selectableApertures = listOf(1.25f, 1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = true
    ),
    PORTRAIT_90_F1_5(
        id = "portrait90f15",
        title = "90 mm f/1.5",
        focalLengthMm = 90,
        maxAperture = 1.5f,
        selectableApertures = listOf(1.5f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = true
    ),
    PORTRAIT_90_F2(
        id = "portrait90f2",
        title = "90 mm f/2.0",
        focalLengthMm = 90,
        maxAperture = 2.0f,
        selectableApertures = listOf(2.0f, 2.5f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = true
    ),
    SOFT_FOCUS_90(
        id = "softFocus90",
        title = "90 mm soft focus f/2.3",
        focalLengthMm = 90,
        maxAperture = 2.3f,
        selectableApertures = listOf(2.3f, 2.5f, 2.8f, 3.2f, 4.5f, 6.3f),
        hasSoftLook = true,
        isPaid = true
    ),
    TELEPHOTO_135(
        id = "telephoto135",
        title = "135 mm f/3.4",
        focalLengthMm = 135,
        maxAperture = 3.4f,
        selectableApertures = listOf(3.4f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f),
        isPaid = true
    );

    fun getPsfAssetPath(aperture: Float): String {
        val fFormatted = String.format(java.util.Locale.US, "%.1f", aperture)
        return "psf/${focalLengthMm}_f${maxAperture.toString().replace(".", "")}/${focalLengthMm}-f$fFormatted.data"
    }

    fun getLspAssetPath(aperture: Float): String {
        val fFormatted = String.format(java.util.Locale.US, "%.1f", aperture)
        return "lsp/${focalLengthMm}_f${maxAperture.toString().replace(".", "")}/LSP_${focalLengthMm}mmf${fFormatted}_S_40.jpg"
    }
}
