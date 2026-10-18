package com.wetzlar.camera.lensengine.model

/**
 * Color looks backed by packaged .cube lookup tables.
 */
enum class WetzlarLook(
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
        title = "Wetzlar Classic",
        abbreviatedTitle = "CLASSIC",
        lutFileName = "Classic_sRGB_sRGB_Release_opacity_65.cube",
        defaultIntensity = 0.65f,
        isPaid = false
    ),
    CONTEMPORARY(
        id = "contemporary",
        title = "Wetzlar Contemporary",
        abbreviatedTitle = "CONTEMP",
        lutFileName = "Contemporary_sRGB_sRGB_Release_opacity_65.cube",
        defaultIntensity = 0.65f,
        isPaid = false
    ),
    NATURAL(
        id = "natural",
        title = "Wetzlar Natural",
        abbreviatedTitle = "NAT",
        lutFileName = "Wetzlar-Filter_Natural_DP3_DP3_Release.cube",
        isPaid = false
    ),
    VIVID(
        id = "vivid",
        title = "Wetzlar Vivid",
        abbreviatedTitle = "VIVID",
        lutFileName = "Wetzlar_Vivid_sRGB_sRGB_Release.cube",
        isPaid = false
    ),
    BW_NATURAL(
        id = "bwNatural",
        title = "Wetzlar B&W Natural",
        abbreviatedTitle = "BW NAT",
        lutFileName = "Wetzlar-Filter_Monochrome_DP3_DP3_Release.cube",
        isMonochrome = true,
        defaultGrain = 0.15f,
        isPaid = false
    ),
    BW_HIGH_CONTRAST(
        id = "bwHighContrast",
        title = "Wetzlar B&W High Contrast",
        abbreviatedTitle = "BW HC",
        lutFileName = "Wetzlar_Monochrome_High_Contrast_sRGB_sRGB_Release.cube",
        isMonochrome = true,
        defaultGrain = 0.25f,
        isPaid = false
    ),
    CHROME(
        id = "chrome",
        title = "Wetzlar Chrome",
        abbreviatedTitle = "CHROME",
        lutFileName = "Chrome_sRGB_sRGB_Release.cube",
        isPaid = true
    ),
    ETERNAL(
        id = "eternal",
        title = "Wetzlar Eternal",
        abbreviatedTitle = "ETERNAL",
        lutFileName = "Wetzlar-Looks_Eternal_sRGB_sRGB_Release.cube",
        isPaid = true
    ),
    SEPIA(
        id = "sepia",
        title = "Wetzlar Sepia",
        abbreviatedTitle = "SEPIA",
        lutFileName = "Wetzlar-Looks_Sepia_sRGB_sRGB_Release.cube",
        isMonochrome = true,
        isPaid = true
    ),
    SELENIUM(
        id = "selenium",
        title = "Wetzlar Selenium",
        abbreviatedTitle = "SELENIUM",
        lutFileName = "Wetzlar-Looks_Selenium_sRGB_sRGB_Release.cube",
        isMonochrome = true,
        isPaid = true
    ),
    SILVER(
        id = "silver",
        title = "Wetzlar Silver",
        abbreviatedTitle = "SILVER",
        lutFileName = "Wetzlar-Looks_Silver_sRGB_sRGB_Release.cube",
        isMonochrome = true,
        isPaid = true
    ),
    BLUE(
        id = "blue",
        title = "Wetzlar Blue",
        abbreviatedTitle = "BLUE",
        lutFileName = "Wetzlar-Looks_Blue_sRGB_sRGB_Release.cube",
        isPaid = true
    ),
    TEAL(
        id = "teal",
        title = "Wetzlar Teal",
        abbreviatedTitle = "TEAL",
        lutFileName = "Wetzlar-Looks_Teal_sRGB_sRGB_Release.cube",
        isPaid = true
    ),
    BRASS(
        id = "brass",
        title = "Wetzlar Brass",
        abbreviatedTitle = "BRASS",
        lutFileName = "Wetzlar_Brass_sRGB_sRGB_Release.cube",
        isPaid = true
    ),
    BLEACH(
        id = "bleach",
        title = "Wetzlar Bleach Bypass",
        abbreviatedTitle = "BLEACH",
        lutFileName = "Wetzlar_Bleach_sRGB_sRGB_Release.cube",
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
