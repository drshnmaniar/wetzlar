package com.leica.lux

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.leica.lux.camera.Camera2DepthReader
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Runtime report for validating camera and GL capabilities on physical test devices. */
object PixelCameraProbe {
    suspend fun testDepthStream(context: Context): String {
        val result = CompletableDeferred<String>()
        val reader = Camera2DepthReader(
            context = context,
            onFrame = { frame ->
                val validDepth = frame.depthMillimeters.filter { it > 0 }
                result.complete(
                    "DEPTH16 STREAM OK · ${frame.width}x${frame.height} · " +
                        "${validDepth.size}/${frame.depthMillimeters.size} valid samples",
                )
            },
            onState = { state ->
                when (state) {
                    is com.leica.lux.camera.DepthReaderState.Unsupported -> result.complete("DEPTH16 unsupported: ${state.reason}")
                    is com.leica.lux.camera.DepthReaderState.Failed -> result.complete("DEPTH16 failed: ${state.reason}")
                    else -> Unit
                }
            },
        )
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val candidates = reader.queryDepthSupport()
            if (candidates.isEmpty()) return "DEPTH16 unsupported: no camera advertises DEPTH_OUTPUT."
            val backId = candidates.firstOrNull { support ->
                manager.getCameraCharacteristics(support.cameraId).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }?.cameraId ?: candidates.first().cameraId
            reader.start(backId)
            return withTimeoutOrNull(10_000) { result.await() }
                ?: "DEPTH16 session opened but no frame arrived within 10 seconds."
        } finally {
            reader.close()
        }
    }

    fun collect(context: Context, glInfo: String): String {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val depthReader = Camera2DepthReader(context, onFrame = {})
        val depthById = depthReader.queryDepthSupport().associateBy { it.cameraId }
        val report = buildString {
            appendLine("DEVICE")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Build: ${Build.DISPLAY}")
            appendLine()
            appendLine("OPENGL ES")
            appendLine(glInfo)
            appendLine()
            appendLine("CAMERA2 (${cameraManager.cameraIdList.size} IDs)")
            for (cameraId in cameraManager.cameraIdList) {
                appendLine("Camera $cameraId")
                runCatching {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                        else -> "unknown"
                    }
                    val hardware = when (characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                        else -> "unknown"
                    }
                    val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.toSet().orEmpty()
                    val capabilityLabels = buildList {
                        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps) add("MANUAL_SENSOR")
                        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps) add("RAW")
                        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) add("LOGICAL_MULTI_CAMERA")
                        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps) add("DEPTH_OUTPUT")
                        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING in caps) add("YUV_REPROCESSING")
                    }
                    val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val previewSizes = streamMap?.getOutputSizes(SurfaceTexture::class.java)
                        ?.sortedByDescending { it.width.toLong() * it.height }
                        ?.take(5)
                        ?.joinToString { "${it.width}x${it.height}" }
                        ?: "unavailable"
                    val depth = depthById[cameraId]
                    val depthText = if (depth == null) {
                        "no DEPTH16 output advertised"
                    } else {
                        "DEPTH16 ${depth.outputSizes.joinToString { "${it.width}x${it.height}" }}; " +
                            "exclusive=${depth.depthIsExclusive}; min stall=${depth.minimumStallDurationNs / 1_000_000} ms"
                    }
                    appendLine("  facing=$facing · hardware=$hardware")
                    appendLine("  capabilities=${capabilityLabels.ifEmpty { listOf("none of the probed capabilities") }.joinToString()}")
                    appendLine("  physical IDs=${characteristics.physicalCameraIds.ifEmpty { setOf("none") }.joinToString()}")
                    appendLine("  preview PRIVATE sizes=$previewSizes")
                    appendLine("  depth=$depthText")
                    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    appendLine("  focal lengths=${focalLengths?.joinToString { String.format(Locale.US, "%.2f", it) } ?: "unknown"} mm")
                    appendLine("  physical apertures=${apertures?.joinToString { "f/${String.format(Locale.US, "%.1f", it)}" } ?: "unknown"}")
                }.onFailure { error ->
                    appendLine("  query failed: ${error.javaClass.simpleName}: ${error.localizedMessage}")
                }
            }
            appendLine()
            appendLine("DEPTH16 probe is metadata only; stream open is a separate Camera2 session test.")
        }
        depthReader.close()
        return report
    }
}
