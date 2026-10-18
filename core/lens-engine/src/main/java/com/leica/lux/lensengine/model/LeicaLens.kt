package com.leica.lux.lensengine.model

/**
 * Leica M Lenses modeled with physical Point Spread Function (PSF) optical kernels
 * and Lens Shading Profiles (LSP) reverse-engineered from Leica LUX.
 */
enum class LeicaLens(
    val id: String,
    val title: String,
    val focalLengthMm: Int,
    val maxAperture: Float,
    val selectableApertures: List<Float>,
    val hasSoftLook: Boolean = false,
    val isPaid: Boolean = false,
    val fixedLook: LeicaLook? = null
) {
    SUMMILUX_28(
        id = "summilux28",
        title = "Summilux-M 28mm f/1.4 ASPH.",
        focalLengthMm = 28,
        maxAperture = 1.4f,
        selectableApertures = listOf(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = false
    ),
    SUMMILUX_35(
        id = "summilux35",
        title = "Summilux-M 35mm f/1.4 ASPH.",
        focalLengthMm = 35,
        maxAperture = 1.4f,
        selectableApertures = listOf(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = false
    ),
    NOCTILUX_50(
        id = "noctilux50",
        title = "Noctilux-M 50mm f/1.2 ASPH.",
        focalLengthMm = 50,
        maxAperture = 1.2f,
        selectableApertures = listOf(1.2f, 1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = false
    ),
    NOCTILUX_75(
        id = "noctilux75",
        title = "Noctilux-M 75mm f/1.25 ASPH.",
        focalLengthMm = 75,
        maxAperture = 1.25f,
        selectableApertures = listOf(1.25f, 1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = true
    ),
    SUMMILUX_90(
        id = "summilux90",
        title = "Summilux-M 90mm f/1.5 ASPH.",
        focalLengthMm = 90,
        maxAperture = 1.5f,
        selectableApertures = listOf(1.5f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = true
    ),
    SUMMICRON_90(
        id = "summicron90",
        title = "Summicron-M 90mm f/2.0 ASPH.",
        focalLengthMm = 90,
        maxAperture = 2.0f,
        selectableApertures = listOf(2.0f, 2.5f, 2.8f, 4.0f, 5.6f, 8.0f),
        isPaid = true
    ),
    THAMBAR_90(
        id = "thambar90",
        title = "Thambar-M 90mm f/2.3",
        focalLengthMm = 90,
        maxAperture = 2.3f,
        selectableApertures = listOf(2.3f, 2.5f, 2.8f, 3.2f, 4.5f, 6.3f),
        hasSoftLook = true,
        isPaid = true
    ),
    APO_TELYT_135(
        id = "apoTelyt135",
        title = "APO-Telyt-M 135mm f/3.4",
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
