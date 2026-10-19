package com.wetzlar.camera.lensengine

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LutColorFilterTest {
    @Test
    fun parseCubeReadsSamplesAndDomains() {
        val cube = """
            TITLE "test LUT"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.1 0.2 0.3
            DOMAIN_MAX 0.9 0.8 0.7
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1 # trailing comment
        """.trimIndent()

        val lut = LutColorFilter.parseCube(BufferedReader(StringReader(cube)))

        assertEquals(2, lut.size)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(0.9f, 0.8f, 0.7f), lut.domainMax, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseCubeRejectsMalformedSample() {
        val cube = """
            LUT_3D_SIZE 2
            0 0
        """.trimIndent()

        LutColorFilter.parseCube(BufferedReader(StringReader(cube)))
    }
}
