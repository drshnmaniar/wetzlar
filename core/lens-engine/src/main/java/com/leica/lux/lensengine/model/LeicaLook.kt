package com.leica.lux.lensengine.model

/**
 * Leica Look color science profiles reverse-engineered from Leica LUX.
 */
enum class LeicaLook(
    val id: String,
    val title: String,
    val abbreviatedTitle: String,
    val lutFileName: String,
    val isMonochrome: Boolean = false,
    val defaultIntensity: Float = 1.0f,
    val defaultGrain: Float = 0.0f,
    val isPaid: Boolean = false
) {
    CLASSIC(
        id = "classic",
        title = "Leica Classic",
        abbreviatedTitle = "CLASSIC",
        lutFileName = "Classic_sRGB_sRGB_Release_opacity_65.cube",
        defaultIntensity = 0.65f,
        isPaid = false
    ),
    CONTEMPORARY(
        id = "contemporary",
        title = "Leica Contemporary",
        abbreviatedTitle = "CONTEMP",
        lutFileName = "Contemporary_sRGB_sRGB_Release_opacity_65.cube",
        defaultIntensity = 0.65f,
        isPaid = false
    ),
    NATURAL(
        id = "natural",
        title = "Leica Natural",
        abbreviatedTitle = "NAT",
        lutFileName = "Leica-Filter_Natural_DP3_DP3_Release.cube",
        isPaid = false
    ),
    VIVID(
        id = "vivid",
        title = "Leica Vivid",
        abbreviatedTitle = "VIVID",
        lutFileName = "Leica_Vivid_sRGB_sRGB_Release.cube",
        isPaid = false
    ),
    BW_NATURAL(
        id = "bwNatural",
        title = "Leica B&W Natural",
        abbreviatedTitle = "BW NAT",
        lutFileName = "Leica-Filter_Monochrome_DP3_DP3_Release.cube",
        isMonochrome = true,
        defaultGrain = 0.15f,
        isPaid = false
    ),
    BW_HIGH_CONTRAST(
        id = "bwHighContrast",
        title = "Leica B&W High Contrast",
        abbreviatedTitle = "BW HC",
        lutFileName = "Leica_Monochrome_High_Contrast_sRGB_sRGB_Release.cube",
        isMonochrome = true,
        defaultGrain = 0.25f,
        isPaid = false
    ),
    CHROME(
        id = "chrome",
        title = "Leica Chrome",
        abbreviatedTitle = "CHROME",
        lutFileName = "Tyson_Leica_Chrome_sRGB_sRGB_Release.cube",
        isPaid = true
    ),
    ETERNAL(
        id = "eternal",
        title = "Leica Eternal",
        abbreviatedTitle = "ETERNAL",
        lutFileName = "Leica-Looks_Eternal_sRGB_sRGB_Release.cube",
        isPaid = true
    ),
    SEPIA(
        id = "sepia",
        title = "Leica Sepia",
        abbreviatedTitle = "SEPIA",
        lutFileName = "Leica-Looks_Sepia_sRGB_sRGB_Release.cube",
        isMonochrome = true,
        isPaid = true
    ),
    SELENIUM(
        id = "selenium",
        title = "Leica Selenium",
        abbreviatedTitle = "SELENIUM",
        lutFileName = "Leica-Looks_Selenium_sRGB_sRGB_Release.cube",
        isMonochrome = true,
        isPaid = true
    ),
    SILVER(
        id = "silver",
        title = "Leica Silver",
        abbreviatedTitle = "SILVER",
        lutFileName = "Leica-Looks_Silver_sRGB_sRGB_Release.cube",
        isMonochrome = true,
        isPaid = true
    ),
    BLUE(
        id = "blue",
        title = "Leica Blue",
        abbreviatedTitle = "BLUE",
        lutFileName = "Leica-Looks_Blue_sRGB_sRGB_Release.cube",
        isPaid = true
    ),
    TEAL(
        id = "teal",
        title = "Leica Teal",
        abbreviatedTitle = "TEAL",
        lutFileName = "Leica-Looks_Teal_sRGB_sRGB_Release.cube",
        isPaid = true
    ),
    BRASS(
        id = "brass",
        title = "Leica Brass",
        abbreviatedTitle = "BRASS",
        lutFileName = "Leica_Brass_sRGB_sRGB_Release.cube",
        isPaid = true
    ),
    BLEACH(
        id = "bleach",
        title = "Leica Bleach Bypass",
        abbreviatedTitle = "BLEACH",
        lutFileName = "Leica_Bleach_sRGB_sRGB_Release.cube",
        isPaid = true
    );
}

/**
 * Monochrome color filter overlays simulating glass filters on black & white film.
 */
enum class ColorFilter(val id: String, val title: String, val rgbWeight: FloatArray) {
    NONE("none", "Standard", floatArrayOf(1.0f, 1.0f, 1.0f)),
    YELLOW("yellow", "Yellow", floatArrayOf(1.2f, 1.1f, 0.4f)),
    ORANGE("orange", "Orange", floatArrayOf(1.4f, 0.8f, 0.2f)),
    RED("red", "Red", floatArrayOf(1.8f, 0.4f, 0.1f)),
    GREEN("green", "Green", floatArrayOf(0.4f, 1.5f, 0.4f)),
    BLUE("blue", "Blue", floatArrayOf(0.3f, 0.5f, 1.6f));

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ColorFilter
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
