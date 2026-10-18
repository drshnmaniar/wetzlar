package com.wetzlar.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import com.wetzlar.camera.lensengine.GLCameraRenderer
import com.wetzlar.camera.lensengine.LutColorFilter
import java.util.concurrent.Executor

/** GLSurfaceView that gives CameraX a Surface backed by the renderer's external OES texture. */
class GLCameraView(context: Context) : GLSurfaceView(context) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    @Volatile var graphicsInfo: String = "Waiting for OpenGL context…"
        private set
    @Volatile private var previewTexture: SurfaceTexture? = null
    @Volatile private var pendingRequest: SurfaceRequest? = null
    private val cameraRenderer = GLCameraRenderer(
        context = context,
        requestRender = { requestRender() },
        onGlInfo = { graphicsInfo = it },
        onSurfaceTextureReady = { texture ->
            mainExecutor.execute {
                previewTexture = texture
                pendingRequest?.also { request ->
                    pendingRequest = null
                    provideCameraSurface(request, texture)
                }
            }
        },
    )

    /** Pass this to CameraX Preview.setSurfaceProvider. */
    val previewSurfaceProvider = Preview.SurfaceProvider(::onSurfaceRequested)

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(cameraRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setLut(filter: LutColorFilter, intensity: Float) {
        queueEvent { cameraRenderer.replaceLut(filter, intensity) }
        requestRender()
    }

    /** Create a Surface for a direct Camera2 session after first unbinding the CameraX preview. */
    fun createCamera2Surface(): Surface? = previewTexture?.let(::Surface)

    private fun onSurfaceRequested(request: SurfaceRequest) {
        val texture = previewTexture
        if (texture == null) {
            pendingRequest?.willNotProvideSurface()
            pendingRequest = request
        } else {
            provideCameraSurface(request, texture)
        }
    }

    private fun provideCameraSurface(request: SurfaceRequest, texture: SurfaceTexture) {
        val resolution = request.resolution
        request.setTransformationInfoListener(mainExecutor) { info ->
            val crop = info.cropRect
            cameraRenderer.setPreviewTransform(
                info.rotationDegrees,
                floatArrayOf(
                    crop.left.toFloat() / resolution.width,
                    crop.top.toFloat() / resolution.height,
                    crop.width().toFloat() / resolution.width,
                    crop.height().toFloat() / resolution.height,
                ),
            )
            requestRender()
        }
        queueEvent {
            texture.setDefaultBufferSize(resolution.width, resolution.height)
            val cameraSurface = Surface(texture)
            mainExecutor.execute {
                request.provideSurface(cameraSurface, mainExecutor) { cameraSurface.release() }
            }
        }
    }
}
