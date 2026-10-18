package com.leica.lux

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.leica.lux.lensengine.LutColorFilter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Applies a look to a captured JPEG without decoding full-resolution phone images into memory. */
object LutJpegProcessor {
    private const val MAX_DECODED_PIXELS = 12_000_000L

    fun apply(file: File, filter: LutColorFilter, intensity: Float) {
        require(file.isFile) { "Captured JPEG does not exist: ${file.absolutePath}" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not read JPEG dimensions" }

        var sampleSize = 1
        while ((bounds.outWidth.toLong() / sampleSize) * (bounds.outHeight.toLong() / sampleSize) > MAX_DECODED_PIXELS) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: error("Could not decode captured JPEG")
        val originalExif = ExifInterface(file.absolutePath)
        val mapped = try {
            filter.applyToBitmap(decoded, intensity)
        } finally {
            decoded.recycle()
        }

        val temp = File.createTempFile("${file.nameWithoutExtension}_lut_", ".jpg", file.parentFile)
        try {
            FileOutputStream(temp).use { output ->
                check(mapped.compress(Bitmap.CompressFormat.JPEG, 96, output)) { "Could not encode filtered JPEG" }
                output.fd.sync()
            }
            mapped.recycle()

            ExifInterface(temp.absolutePath).apply {
                copyExif(originalExif, this)
                saveAttributes()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (!mapped.isRecycled) mapped.recycle()
            temp.delete()
        }
    }

    private fun copyExif(source: ExifInterface, target: ExifInterface) {
        val tags = arrayOf(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
        )
        for (tag in tags) source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
    }
}
