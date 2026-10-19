package com.wetzlar.camera.lensengine

import android.graphics.Bitmap
import android.opengl.GLES30
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.StringTokenizer

/** Parsed 3D .cube LUT. The sample order follows the .cube convention: red varies fastest. */
class LutColorFilter private constructor(
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    private val rgba: FloatArray,
) {
    init {
        require(size >= MIN_LUT_SIZE && size <= MAX_LUT_SIZE)
        require(domainMin.size == 3 && domainMax.size == 3)
        require(rgba.size == size * size * size * 4)
    }

    /** Uploads this LUT as a GL_TEXTURE_3D. Call with a current GLES 3 context. */
    fun uploadTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        check(ids[0] != 0) { "Could not allocate 3D LUT texture" }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, size, size, size, 0,
            GLES30.GL_RGBA, GLES30.GL_FLOAT, directFloatBuffer(rgba),
        )
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, ids, 0)
            error("3D LUT upload failed (GL error 0x${error.toString(16)})")
        }
        return ids[0]
    }

    /** Applies this LUT to a bitmap for still-photo export. The input bitmap is left unchanged. */
    fun applyToBitmap(input: Bitmap, intensity: Float = 1f): Bitmap {
        val amount = intensity.coerceIn(0f, 1f)
        if (amount == 0f) return input.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not copy bitmap")

        val output = input.copy(Bitmap.Config.ARGB_8888, true) ?: error("Could not copy bitmap")
        val tableSize = CPU_TABLE_SIZE
        val table = buildCpuTable(tableSize)
        val row = IntArray(output.width)
        for (y in 0 until output.height) {
            output.getPixels(row, 0, output.width, 0, y, output.width, 1)
            for (x in row.indices) {
                val color = row[x]
                val red = (color ushr 16) and 0xff
                val green = (color ushr 8) and 0xff
                val blue = color and 0xff
                val r = domainIndex(red / 255f, 0, tableSize)
                val g = domainIndex(green / 255f, 1, tableSize)
                val b = domainIndex(blue / 255f, 2, tableSize)
                val index = ((b * tableSize + g) * tableSize + r) * 3
                val outR = blendChannel(red, table[index].toInt() and 0xff, amount)
                val outG = blendChannel(green, table[index + 1].toInt() and 0xff, amount)
                val outB = blendChannel(blue, table[index + 2].toInt() and 0xff, amount)
                row[x] = (color and -0x1000000) or (outR shl 16) or (outG shl 8) or outB
            }
            output.setPixels(row, 0, output.width, 0, y, output.width, 1)
        }
        return output
    }

    /** Converts a tiled 2D LUT image (width=size², height=size) into a 3D LUT. */
    companion object {
        private const val MIN_LUT_SIZE = 2
        private const val MAX_LUT_SIZE = 128
        private const val CPU_TABLE_SIZE = 64

        fun parseCube(input: InputStream): LutColorFilter = parseCube(
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)),
        )

        fun parseCube(reader: BufferedReader): LutColorFilter {
            var size: Int? = null
            val domainMin = floatArrayOf(0f, 0f, 0f)
            val domainMax = floatArrayOf(1f, 1f, 1f)
            var values = FloatArray(0)
            var valueCount = 0
            reader.useLines { lines ->
                lines.forEachIndexed { lineIndex, original ->
                    val line = original.substringBefore('#').trim()
                    if (line.isEmpty()) return@forEachIndexed
                    val tokens = StringTokenizer(line)
                    val first = tokens.nextToken()
                    if (first.first().isLetter()) {
                        when (first.uppercase()) {
                            "TITLE" -> Unit
                            "LUT_1D_SIZE" -> error("1D LUTs are not supported; provide LUT_3D_SIZE")
                            "LUT_3D_SIZE" -> {
                                require(tokens.countTokens() == 1) { "Invalid LUT_3D_SIZE at line ${lineIndex + 1}" }
                                size = tokens.nextToken().toInt().also {
                                    require(it in MIN_LUT_SIZE..MAX_LUT_SIZE) { "Unsupported LUT size $it" }
                                }
                                values = FloatArray(requireNotNull(size) * requireNotNull(size) * requireNotNull(size) * 3)
                            }
                            "DOMAIN_MIN", "DOMAIN_MAX" -> {
                                require(tokens.countTokens() == 3) { "Invalid $first at line ${lineIndex + 1}" }
                                val target = if (first.equals("DOMAIN_MIN", true)) domainMin else domainMax
                                for (channel in 0..2) target[channel] = tokens.nextToken().toFloat()
                            }
                            else -> error("Unsupported LUT directive '$first' at line ${lineIndex + 1}")
                        }
                    } else {
                        require(tokens.countTokens() == 2) { "Invalid RGB sample at line ${lineIndex + 1}" }
                        require(size != null) { "LUT_3D_SIZE must appear before RGB samples" }
                        require(valueCount + 3 <= values.size) { "Too many LUT samples" }
                        values[valueCount++] = first.toFloat()
                        values[valueCount++] = tokens.nextToken().toFloat()
                        values[valueCount++] = tokens.nextToken().toFloat()
                    }
                }
            }
            val lutSize = size ?: error("Missing LUT_3D_SIZE header")
            val expected = lutSize * lutSize * lutSize * 3
            require(valueCount == expected) { "Expected $expected LUT values, found $valueCount" }
            require((0..2).all { domainMax[it] > domainMin[it] }) { "Invalid LUT domain" }

            val rgba = FloatArray(lutSize * lutSize * lutSize * 4)
            for (pixel in 0 until lutSize * lutSize * lutSize) {
                val source = pixel * 3
                val dest = pixel * 4
                rgba[dest] = values[source]
                rgba[dest + 1] = values[source + 1]
                rgba[dest + 2] = values[source + 2]
                rgba[dest + 3] = 1f
            }
            return LutColorFilter(lutSize, domainMin, domainMax, rgba)
        }

        fun fromFlattened2d(
            size: Int,
            width: Int,
            height: Int,
            rgbaPixels: FloatArray,
        ): LutColorFilter {
            require(size in MIN_LUT_SIZE..MAX_LUT_SIZE)
            require(width == size * size && height == size) {
                "Expected a tiled LUT image of ${size * size}×$size, got ${width}×$height"
            }
            require(rgbaPixels.size == size * size * size * 4) {
                "Expected ${size * size * size * 4} RGBA values for a $size³ LUT"
            }
            val volume = FloatArray(rgbaPixels.size)
            for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size) {
                val sourceX = blue * size + red
                val source = (green * width + sourceX) * 4
                val destination = ((blue * size + green) * size + red) * 4
                for (channel in 0..3) volume[destination + channel] = rgbaPixels[source + channel]
            }
            return LutColorFilter(size, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), volume)
        }

        fun identity(size: Int = 16): LutColorFilter {
            require(size in MIN_LUT_SIZE..MAX_LUT_SIZE)
            val rgba = FloatArray(size * size * size * 4)
            for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size) {
                val index = ((blue * size + green) * size + red) * 4
                rgba[index] = red.toFloat() / (size - 1)
                rgba[index + 1] = green.toFloat() / (size - 1)
                rgba[index + 2] = blue.toFloat() / (size - 1)
                rgba[index + 3] = 1f
            }
            return LutColorFilter(size, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), rgba)
        }

        private fun directFloatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

        private fun blendChannel(input: Int, mapped: Int, amount: Float): Int =
            (input + (mapped - input) * amount).toInt().coerceIn(0, 255)
    }

    private fun domainIndex(value: Float, channel: Int, tableSize: Int): Int {
        val normalized = ((value - domainMin[channel]) / (domainMax[channel] - domainMin[channel]))
            .coerceIn(0f, 1f)
        return (normalized * (tableSize - 1)).toInt()
    }

    private fun buildCpuTable(tableSize: Int): ByteArray {
        val result = ByteArray(tableSize * tableSize * tableSize * 3)
        for (blue in 0 until tableSize) for (green in 0 until tableSize) for (red in 0 until tableSize) {
            val lutRed = red.toFloat() / (tableSize - 1) * (size - 1)
            val lutGreen = green.toFloat() / (tableSize - 1) * (size - 1)
            val lutBlue = blue.toFloat() / (tableSize - 1) * (size - 1)
            val r0 = lutRed.toInt()
            val g0 = lutGreen.toInt()
            val b0 = lutBlue.toInt()
            val r1 = (r0 + 1).coerceAtMost(size - 1)
            val g1 = (g0 + 1).coerceAtMost(size - 1)
            val b1 = (b0 + 1).coerceAtMost(size - 1)
            val fr = lutRed - r0
            val fg = lutGreen - g0
            val fb = lutBlue - b0
            val destination = ((blue * tableSize + green) * tableSize + red) * 3
            for (channel in 0..2) {
                val c000 = sample(r0, g0, b0, channel)
                val c100 = sample(r1, g0, b0, channel)
                val c010 = sample(r0, g1, b0, channel)
                val c110 = sample(r1, g1, b0, channel)
                val c001 = sample(r0, g0, b1, channel)
                val c101 = sample(r1, g0, b1, channel)
                val c011 = sample(r0, g1, b1, channel)
                val c111 = sample(r1, g1, b1, channel)
                val c00 = c000 + (c100 - c000) * fr
                val c10 = c010 + (c110 - c010) * fr
                val c01 = c001 + (c101 - c001) * fr
                val c11 = c011 + (c111 - c011) * fr
                val c0 = c00 + (c10 - c00) * fg
                val c1 = c01 + (c11 - c01) * fg
                val mapped = (c0 + (c1 - c0) * fb).coerceIn(0f, 1f)
                result[destination + channel] = (mapped * 255f + 0.5f).toInt().toByte()
            }
        }
        return result
    }

    private fun sample(red: Int, green: Int, blue: Int, channel: Int): Float =
        rgba[((blue * size + green) * size + red) * 4 + channel]
}
