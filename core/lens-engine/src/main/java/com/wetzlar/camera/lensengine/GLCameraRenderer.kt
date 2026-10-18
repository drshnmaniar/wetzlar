package com.wetzlar.camera.lensengine

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** GLES 3 renderer for a camera SurfaceTexture plus a 3D color LUT. */
class GLCameraRenderer(
    context: Context,
    private val requestRender: () -> Unit,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val onGlInfo: (String) -> Unit = {},
) : GLSurfaceView.Renderer {
    private val appContext = context.applicationContext
    private var program = 0
    private var cameraTextureId = 0
    private var lutTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var frameAvailable = false
    private var lut: LutColorFilter = LutColorFilter.identity()
    private var lutIntensity = 1f
    @Volatile private var rotationDegrees = 0
    @Volatile private var cropRect = floatArrayOf(0f, 0f, 1f, 1f)
    private val textureMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private var lutDomainMin = floatArrayOf(0f, 0f, 0f)
    private var lutDomainMax = floatArrayOf(1f, 1f, 1f)

    private val vertices = floatBuffer(floatArrayOf(
        -1f, -1f, 0f, 1f,
         1f, -1f, 1f, 1f,
        -1f,  1f, 0f, 0f,
         1f,  1f, 1f, 0f,
    ))

    fun currentSurfaceTexture(): SurfaceTexture? = surfaceTexture

    fun setPreviewTransform(rotation: Int, crop: FloatArray) {
        rotationDegrees = ((rotation % 360) + 360) % 360
        cropRect = crop.copyOf()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        surfaceTexture?.release()
        surfaceTexture = null
        android.opengl.Matrix.setIdentityM(textureMatrix, 0)
        // Texture names belong to the old EGL context after a context loss.
        lutTextureId = 0
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        val maxTextureSize = IntArray(1)
        val max3dTextureSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, max3dTextureSize, 0)
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        onGlInfo(
            listOf(
                "GL_RENDERER: ${GLES30.glGetString(GLES30.GL_RENDERER)}",
                "GL_VERSION: ${GLES30.glGetString(GLES30.GL_VERSION)}",
                "MAX_TEXTURE_SIZE: ${maxTextureSize[0]}",
                "MAX_3D_TEXTURE_SIZE: ${max3dTextureSize[0]}",
                "OES_external_essl3: ${extensions.contains("GL_OES_EGL_image_external_essl3")}",
            ).joinToString("\n"),
        )
        program = createProgram(VERTEX_SHADER, loadFragmentShader())
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(cameraTextureId).also { texture ->
            texture.setOnFrameAvailableListener {
                synchronized(this) { frameAvailable = true }
                requestRender()
            }
            onSurfaceTextureReady(texture)
        }
        uploadLut()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    override fun onDrawFrame(gl: GL10?) {
        val texture = surfaceTexture
        if (texture != null) {
            val shouldUpdate = synchronized(this) {
                val pending = frameAvailable
                frameAvailable = false
                pending
            }
            if (shouldUpdate) {
                runCatching { texture.updateTexImage() }
                texture.getTransformMatrix(textureMatrix)
            }
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (program == 0 || cameraTextureId == 0) return
        GLES30.glUseProgram(program)

        vertices.position(0)
        val position = GLES30.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES30.glGetAttribLocation(program, "aTextureCoord")
        vertices.position(0)
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES30.glEnableVertexAttribArray(texCoord)
        GLES30.glVertexAttribPointer(texCoord, 2, GLES30.GL_FLOAT, false, 16, vertices)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCameraTexture"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uTextureTransform"), 1, false, textureMatrix, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(program, "uCropRect"), 1, cropRect, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uRotationDegrees"), rotationDegrees)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLutTexture"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uHasLut"), if (lutTextureId == 0) 0 else 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uLutIntensity"), lutIntensity)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uDomainMin"), 1, lutDomainMin, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uDomainMax"), 1, lutDomainMax, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(position)
        GLES30.glDisableVertexAttribArray(texCoord)
    }

    /** Must be called on the GL thread. */
    fun replaceLut(filter: LutColorFilter, intensity: Float = 1f) {
        lut = filter
        lutIntensity = intensity.coerceIn(0f, 1f)
        uploadLut()
    }

    private fun uploadLut() {
        // A queued UI update can arrive before GLSurfaceView has created its EGL context.
        if (cameraTextureId == 0) return
        if (lutTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
        lutTextureId = lut.uploadTexture()
        lutDomainMin = lut.domainMin.copyOf()
        lutDomainMax = lut.domainMax.copyOf()
    }

    private fun loadFragmentShader(): String = appContext.assets.open(SHADER_ASSET).use { stream ->
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val linked = GLES30.glCreateProgram()
        GLES30.glAttachShader(linked, vertex)
        GLES30.glAttachShader(linked, fragment)
        GLES30.glLinkProgram(linked)
        val status = IntArray(1)
        GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
        val log = GLES30.glGetProgramInfoLog(linked)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        check(status[0] == GLES30.GL_TRUE) { "Could not link camera shader: $log" }
        return linked
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        val log = GLES30.glGetShaderInfoLog(shader)
        check(status[0] == GLES30.GL_TRUE) { "Could not compile camera shader: $log" }
        return shader
    }

    companion object {
        private const val SHADER_ASSET = "shaders/LutShader.glsl"
        private val VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 aPosition;
            in vec2 aTextureCoord;
            out vec2 vTextureCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTextureCoord = aTextureCoord;
            }
        """.trimIndent()

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }
    }
}
