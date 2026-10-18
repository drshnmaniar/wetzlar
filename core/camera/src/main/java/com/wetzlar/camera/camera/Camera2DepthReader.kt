package com.wetzlar.camera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.nio.ByteOrder

/** DEPTH16 availability and the stream constraint reported by a Camera2 camera. */
data class Camera2DepthSupport(
    val cameraId: String,
    val outputSizes: List<Size>,
    val depthIsExclusive: Boolean,
    val minimumStallDurationNs: Long,
)

/** Dense depth map. Depth values are millimeters; confidence is normalized to [0, 1]. */
data class Depth16Frame(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val depthMillimeters: IntArray,
    val confidence: FloatArray,
)

sealed interface DepthReaderState {
    data object Idle : DepthReaderState
    data object Opening : DepthReaderState
    data object Streaming : DepthReaderState
    data class Unsupported(val reason: String) : DepthReaderState
    data class Failed(val reason: String, val cause: Throwable? = null) : DepthReaderState
}

/**
 * Camera2 DEPTH16 capability query and reader. Camera2 exclusively owns the device while this is
 * running; stop/unbind CameraX before starting it. DEPTH16 output and simultaneous color streams
 * are device-specific, so the caller supplies an optional preview Surface only when the reported
 * configuration permits it.
 */
class Camera2DepthReader(
    context: Context,
    private val onFrame: (Depth16Frame) -> Unit,
    private val onState: (DepthReaderState) -> Unit = {},
) : Closeable {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var closed = true

    fun queryDepthSupport(): List<Camera2DepthSupport> = cameraManager.cameraIdList.mapNotNull { id ->
        queryDepthSupport(id)
    }

    fun queryDepthSupport(cameraId: String): Camera2DepthSupport? {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if (capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) != true) return null
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = streamMap.getOutputSizes(ImageFormat.DEPTH16)?.toList().orEmpty()
        if (sizes.isEmpty()) return null
        val exclusive = characteristics.get(CameraCharacteristics.DEPTH_DEPTH_IS_EXCLUSIVE) ?: false
        val minStall = sizes.minOfOrNull { streamMap.getOutputStallDuration(ImageFormat.DEPTH16, it) } ?: 0L
        return Camera2DepthSupport(cameraId, sizes, exclusive, minStall)
    }

    /** Opens a depth stream. Set [previewSurface] only for a non-exclusive supported configuration. */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun start(cameraId: String, requestedSize: Size? = null, previewSurface: android.view.Surface? = null) {
        stop()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Camera permission is required for DEPTH16")
        }
        val support = queryDepthSupport(cameraId)
        if (support == null) {
            onState(DepthReaderState.Unsupported("Camera $cameraId does not advertise DEPTH16 output"))
            return
        }
        if (support.depthIsExclusive && previewSurface != null) {
            onState(DepthReaderState.Unsupported("Camera $cameraId requires an exclusive DEPTH16 stream"))
            return
        }
        val size = requestedSize?.takeIf { it in support.outputSizes }
            ?: support.outputSizes.minByOrNull { it.width.toLong() * it.height }
            ?: run {
                onState(DepthReaderState.Unsupported("No DEPTH16 output size is available"))
                return
            }
        closed = false
        onState(DepthReaderState.Opening)
        val cameraThread = HandlerThread("Camera2DepthReader").apply { start() }
        thread = cameraThread
        val cameraHandler = Handler(cameraThread.looper)
        handler = cameraHandler
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.DEPTH16, MAX_IMAGES)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use { depthImage ->
                runCatching { onFrame(decode(depthImage)) }
                    .onFailure { onState(DepthReaderState.Failed("DEPTH16 frame callback failed", it)) }
            }
        }, cameraHandler)

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    device = camera
                    val outputs = buildList {
                        add(imageReader.surface)
                        if (previewSurface != null) add(previewSurface)
                    }
                    camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if (closed) { configured.close(); return }
                            session = configured
                            try {
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(imageReader.surface)
                                    if (previewSurface != null) addTarget(previewSurface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                }.build()
                                configured.setRepeatingRequest(request, null, cameraHandler)
                                onState(DepthReaderState.Streaming)
                            } catch (error: Exception) {
                                onState(DepthReaderState.Failed("Could not start DEPTH16 repeating request", error))
                                releaseResources()
                            }
                        }

                        override fun onConfigureFailed(configured: CameraCaptureSession) {
                            configured.close()
                            onState(DepthReaderState.Unsupported("Camera $cameraId rejected the DEPTH16 output combination"))
                            releaseResources()
                        }
                    }, cameraHandler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (!closed) onState(DepthReaderState.Failed("Camera $cameraId disconnected"))
                    releaseResources()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    onState(DepthReaderState.Failed("Camera $cameraId failed with code $error"))
                    releaseResources()
                }
            }, cameraHandler)
        } catch (error: Exception) {
            onState(DepthReaderState.Failed("Could not open Camera2 depth stream", error))
            releaseResources()
        }
    }

    fun stop() {
        releaseResources()
        onState(DepthReaderState.Idle)
    }

    private fun releaseResources() {
        closed = true
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun close() = stop()

    private fun decode(image: Image): Depth16Frame {
        val plane = image.planes.single()
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val depth = IntArray(image.width * image.height)
        val confidence = FloatArray(depth.size)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val byteIndex = buffer.position() + y * rowStride + x * pixelStride
            val sample = buffer.getShort(byteIndex).toInt() and 0xffff
            val index = y * image.width + x
            depth[index] = sample and DEPTH_MASK
            val confidenceCode = (sample ushr CONFIDENCE_SHIFT) and CONFIDENCE_MASK
            confidence[index] = if (confidenceCode == 0) 1f else (confidenceCode - 1) / 7f
        }
        return Depth16Frame(image.width, image.height, image.timestamp, depth, confidence)
    }

    companion object {
        private const val MAX_IMAGES = 2
        private const val DEPTH_MASK = 0x1fff
        private const val CONFIDENCE_SHIFT = 13
        private const val CONFIDENCE_MASK = 0x7
    }
}
