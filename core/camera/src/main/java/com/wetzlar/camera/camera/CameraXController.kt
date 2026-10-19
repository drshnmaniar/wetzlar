package com.wetzlar.camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small lifecycle-aware CameraX wrapper for an AndroidView-hosted [PreviewView].
 * Binding and image saving complete asynchronously and are reflected through [state].
 * Call [unbind] from the owning screen's disposal callback.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraXController(context: Context) {
    private val appContext = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private val _state = MutableStateFlow<CameraSessionState>(CameraSessionState.Idle)
    val state: StateFlow<CameraSessionState> = _state.asStateFlow()

    @Volatile private var provider: ProcessCameraProvider? = null
    @Volatile private var boundPreview: Preview? = null
    @Volatile private var boundCapture: ImageCapture? = null
    @Volatile private var boundCamera: Camera? = null
    private val disposed = AtomicBoolean(false)

    /** Starts binding preview + still capture to [lifecycleOwner]. Safe to call again. */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    ) = bind(
        lifecycleOwner,
        previewView.surfaceProvider,
        lensFacing,
        previewView.display?.rotation ?: Surface.ROTATION_0,
    )

    /** Binds CameraX preview frames to a custom target such as a SurfaceTexture-backed GL view. */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        targetRotation: Int = Surface.ROTATION_0,
    ) {
        disposed.set(false)
        _state.value = CameraSessionState.Binding
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            if (disposed.get()) return@addListener
            try {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                val oldPreview = boundPreview
                val oldCapture = boundCapture
                if (oldPreview != null && oldCapture != null) {
                    cameraProvider.unbind(oldPreview, oldCapture)
                }
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                val preview = Preview.Builder()
                    .setTargetRotation(targetRotation)
                    .build().also {
                        it.setSurfaceProvider(mainExecutor, surfaceProvider)
                    }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(targetRotation)
                    .build()
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                boundPreview = preview
                boundCapture = capture
                boundCamera = camera
                val info = Camera2CameraInfo.from(camera.cameraInfo)
                _state.value = CameraSessionState.Ready(
                    capabilities = CameraCapabilities(
                        isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                        exposureTimeRangeNs = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                        focusDistanceDiopters = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
                        exposureCompensationRange = camera.cameraInfo.exposureState.exposureCompensationRange,
                    ),
                )
            } catch (error: Exception) {
                _state.value = CameraSessionState.Error(error.message ?: "Unable to open camera", error)
            }
        }, mainExecutor)
    }

    /** Saves a full-resolution JPEG to [outputFile]; the callback runs on the main executor. */
    fun capture(outputFile: File, callback: (CaptureResult) -> Unit) {
        val capture = boundCapture
        if (capture == null || disposed.get()) {
            callback(CaptureResult.Failure("Camera is not ready"))
            return
        }
        outputFile.parentFile?.mkdirs()
        val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                callback(CaptureResult.Saved(outputFile))
            }

            override fun onError(exception: ImageCaptureException) {
                callback(CaptureResult.Failure(exception.message ?: "Image capture failed", exception))
            }
        })
    }

    /** Applies a digital zoom approximating a 35 mm-equivalent focal-length selection. */
    fun setLensFocalLength(focalLengthMm: Int, callback: (Throwable?) -> Unit) {
        val camera = boundCamera
        val zoomState = camera?.cameraInfo?.zoomState?.value
        if (camera == null || zoomState == null) {
            callback(IllegalStateException("Camera zoom is not ready"))
            return
        }
        val ratio = (focalLengthMm / 35f).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        val zoomFuture = camera.cameraControl.setZoomRatio(ratio)
        zoomFuture.addListener(
            {
                callback(
                    try {
                        zoomFuture.get()
                        null
                    } catch (error: ExecutionException) {
                        error.cause ?: error
                    } catch (error: Exception) {
                        error
                    },
                )
            },
            mainExecutor,
        )
    }

    /**
     * Applies the controls CameraX/Camera2 can expose on this device. The result distinguishes
     * controls queued to the camera from unavailable or unsupported controls.
     */
    fun applyManualSettings(settings: ManualSettings): ManualControlResult {
        val camera = boundCamera ?: return ManualControlResult(
            unsupported = mapOf(ManualControl.ALL to "Camera is not ready"),
        )
        val characteristics = Camera2CameraInfo.from(camera.cameraInfo)
        val applied = linkedSetOf<ManualControl>()
        val unsupported = linkedMapOf<ManualControl, String>()
        val requestOptions = CaptureRequestOptions.Builder()
        var hasManualExposurePair = false

        val evRange = camera.cameraInfo.exposureState.exposureCompensationRange
        val evStep = camera.cameraInfo.exposureState.exposureCompensationStep
        if (evStep.toFloat() > 0f) {
            val index = (settings.exposureCompensationEv / evStep.toFloat()).roundToInt()
                .coerceIn(evRange.lower, evRange.upper)
            camera.cameraControl.setExposureCompensationIndex(index)
            applied += ManualControl.EXPOSURE_COMPENSATION
        } else {
            unsupported[ManualControl.EXPOSURE_COMPENSATION] = "Exposure compensation is unavailable"
        }

        // Camera2 manual sensor exposure requires AE off and both values together. CameraX does
        // not offer independent public ISO/shutter controls, so incomplete pairs are not guessed.
        if (settings.iso != null || settings.exposureTimeNs != null) {
            val isoRange = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val timeRange = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val capabilities = characteristics.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val supportsManualSensor = capabilities?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
            ) == true
            if (settings.iso == null || settings.exposureTimeNs == null || isoRange == null || timeRange == null || !supportsManualSensor) {
                val reason = "ISO and shutter time must both be set, and the camera must advertise manual sensor control"
                if (settings.iso != null) unsupported[ManualControl.ISO] = reason
                if (settings.exposureTimeNs != null) unsupported[ManualControl.SHUTTER] = reason
            } else if (settings.iso !in isoRange || settings.exposureTimeNs !in timeRange) {
                unsupported[ManualControl.ISO] = "Requested ISO/shutter is outside the sensor range: ISO $isoRange, time $timeRange ns"
                unsupported[ManualControl.SHUTTER] = "Requested ISO/shutter is outside the sensor range: ISO $isoRange, time $timeRange ns"
            } else {
                requestOptions
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureTimeNs)
                hasManualExposurePair = true
                applied += ManualControl.ISO
                applied += ManualControl.SHUTTER
            }
        }

        settings.focusDistanceMeters?.let { meters ->
            val minDiopters = characteristics.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            if (meters <= 0f || minDiopters == null || minDiopters <= 0f) {
                unsupported[ManualControl.FOCUS] = "Manual lens focus distance is unavailable on this camera"
                // If a previous request put the lens into AF_OFF, explicitly restore AF even
                // when the newly requested distance cannot be honored.
                requestOptions.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                )
            } else {
                // Camera2's focus distance is expressed in diopters (1/meters).
                val diopters = (1f / meters).coerceIn(0f, minDiopters)
                requestOptions
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
                applied += ManualControl.FOCUS
            }
        } ?: run {
            // Restore continuous autofocus after a prior manual-distance request.
            requestOptions
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (settings.whiteBalanceKelvin != null) {
            unsupported[ManualControl.WHITE_BALANCE] = "Kelvin white balance is not implemented; CameraX continues using the device AWB pipeline"
        }
        // Always send the managed options. Sending AE_ON when either manual sensor value is
        // AUTO restores metering after an earlier manual ISO/shutter request; likewise the AF
        // mode above restores autofocus when focusDistanceMeters is null.
        requestOptions.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_MODE,
            if (hasManualExposurePair) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON,
        )
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(requestOptions.build())
        return ManualControlResult(applied, unsupported)
    }

    /** Unbinds only this controller's use cases, leaving other CameraX clients untouched. */
    fun unbind() {
        disposed.set(true)
        val currentProvider = provider
        val preview = boundPreview
        val capture = boundCapture
        if (currentProvider != null && preview != null && capture != null) {
            currentProvider.unbind(preview, capture)
        }
        boundPreview = null
        boundCapture = null
        boundCamera = null
        provider = null
        _state.value = CameraSessionState.Idle
    }
}

sealed interface CameraSessionState {
    data object Idle : CameraSessionState
    data object Binding : CameraSessionState
    data class Ready(val capabilities: CameraCapabilities) : CameraSessionState
    data class Error(val message: String, val cause: Throwable? = null) : CameraSessionState
}

data class CameraCapabilities(
    val isoRange: Range<Int>?,
    val exposureTimeRangeNs: Range<Long>?,
    /** Camera2 minimum focus distance in diopters; null means fixed focus or unavailable. */
    val focusDistanceDiopters: Float?,
    val exposureCompensationRange: Range<Int>,
)

enum class ManualControl { ALL, ISO, SHUTTER, EXPOSURE_COMPENSATION, FOCUS, WHITE_BALANCE }

data class ManualControlResult(
    val applied: Set<ManualControl> = emptySet(),
    val unsupported: Map<ManualControl, String> = emptyMap(),
)

sealed interface CaptureResult {
    data class Saved(val file: File) : CaptureResult
    data class Failure(val message: String, val cause: Throwable? = null) : CaptureResult
}
