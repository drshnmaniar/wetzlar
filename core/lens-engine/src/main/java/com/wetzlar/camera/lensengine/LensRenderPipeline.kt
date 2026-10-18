package com.wetzlar.camera.lensengine

import com.wetzlar.camera.lensengine.model.ColorFilter
import com.wetzlar.camera.lensengine.model.WetzlarLens
import com.wetzlar.camera.lensengine.model.WetzlarLook

/** GPU operations represented by the compute shaders bundled in this module. */
enum class LensPassKind(val shaderAsset: String) {
    LENS_SHADING("shaders/apply_shading.comp"),
    DEPTH_OF_FIELD("shaders/psf_blur.comp"),
    BLOOM("shaders/bloom.comp"),
    COLOR_LOOK("shaders/apply_tones.comp"),
    FILM_GRAIN("shaders/film_grain.comp"),
}

/** Immutable settings for one render. Passes with zero strength or disabled effects are omitted. */
data class LensRenderRequest(
    val lens: WetzlarLens,
    val aperture: Float = lens.maxAperture,
    val look: WetzlarLook,
    val lookIntensity: Float = look.defaultIntensity,
    val colorFilter: ColorFilter = ColorFilter.NONE,
    val grainStrength: Float = look.defaultGrain,
    val bloomIntensity: Float = 0f,
    val shadingIntensity: Float = 1f,
    val depthBlurEnabled: Boolean = false,
    val focusDepthMeters: Float = 2f,
    val maxCircleOfConfusionPixels: Float = 32f,
    val randomSeed: Float = 0f,
) {
    /** Resolves arbitrary UI input to one aperture supported by the selected lens profile. */
    val effectiveAperture: Float
        get() = lens.selectableApertures.minByOrNull { kotlin.math.abs(it - aperture) } ?: lens.maxAperture

    fun passes(): List<LensRenderPass> = buildList {
        if (shadingIntensity > 0f) add(
            LensRenderPass(LensPassKind.LENS_SHADING, shadingIntensity = shadingIntensity.coerceIn(0f, 1f), lspAssetPath = lens.getLspAssetPath(effectiveAperture)),
        )
        if (depthBlurEnabled) add(
            LensRenderPass(
                kind = LensPassKind.DEPTH_OF_FIELD,
                aperture = effectiveAperture,
                focusDepthMeters = focusDepthMeters.coerceAtLeast(0.01f),
                focalLengthMm = lens.focalLengthMm,
                maxCircleOfConfusionPixels = maxCircleOfConfusionPixels.coerceAtLeast(0f),
                psfAssetPath = lens.getPsfAssetPath(effectiveAperture),
            ),
        )
        if (bloomIntensity > 0f) add(LensRenderPass(LensPassKind.BLOOM, bloomIntensity = bloomIntensity.coerceIn(0f, 1f)))
        if (lookIntensity > 0f) add(
            LensRenderPass(
                kind = LensPassKind.COLOR_LOOK,
                lookIntensity = lookIntensity.coerceIn(0f, 1f),
                look = look,
                colorFilter = colorFilter,
            ),
        )
        if (grainStrength > 0f) add(
            LensRenderPass(LensPassKind.FILM_GRAIN, grainStrength = grainStrength.coerceIn(0f, 1f), randomSeed = randomSeed),
        )
    }
}

/** Fully resolved shader inputs; assets are module-relative and must be loaded by a GPU backend. */
data class LensRenderPass(
    val kind: LensPassKind,
    val shadingIntensity: Float = 0f,
    val lspAssetPath: String? = null,
    val aperture: Float = 0f,
    val focusDepthMeters: Float = 0f,
    val focalLengthMm: Int = 0,
    val maxCircleOfConfusionPixels: Float = 0f,
    val psfAssetPath: String? = null,
    val bloomIntensity: Float = 0f,
    val lookIntensity: Float = 0f,
    val look: WetzlarLook? = null,
    val colorFilter: ColorFilter = ColorFilter.NONE,
    val grainStrength: Float = 0f,
    val randomSeed: Float = 0f,
)

/** Opaque image type: the backend owns its Bitmap, texture, or hardware-buffer representation. */
interface LensFrame

/** A platform GPU implementation must explicitly declare and execute each supported shader pass. */
interface LensGpuBackend<F : LensFrame> {
    fun supports(pass: LensPassKind): Boolean
    fun execute(input: F, pass: LensRenderPass): LensPassResult<F>
}

sealed interface LensPassResult<out F : LensFrame> {
    data class Finished<F : LensFrame>(val frame: F) : LensPassResult<F>
    data class Failed(val reason: String) : LensPassResult<Nothing>
}

enum class LensPassStatus { EXECUTED, UNSUPPORTED, FAILED, SKIPPED }

data class LensPassReport(
    val pass: LensRenderPass,
    val status: LensPassStatus,
    val detail: String? = null,
)

data class LensRenderResult<F : LensFrame>(
    /** Last successful frame; if a pass is unsupported/fails, this remains the best partial output. */
    val frame: F,
    val reports: List<LensPassReport>,
) {
    val isComplete: Boolean get() = reports.all { it.status == LensPassStatus.EXECUTED }
    val hasUnsupportedPasses: Boolean get() = reports.any { it.status == LensPassStatus.UNSUPPORTED }
}

/**
 * Plans and dispatches the shader pipeline in a stable order. This module currently ships GLSL
 * source only: without an injected device-specific [LensGpuBackend], every requested pass is
 * returned as UNSUPPORTED and the input frame is preserved. No pass is claimed as applied just
 * because its shader asset exists. Call from a worker/render thread, not the Android main thread.
 */
class LensRenderPipeline<F : LensFrame>(private val backend: LensGpuBackend<F>? = null) {
    fun render(input: F, request: LensRenderRequest): LensRenderResult<F> {
        val passes = request.passes()
        val reports = ArrayList<LensPassReport>(passes.size)
        var output = input
        var stopped = false

        for (pass in passes) {
            if (stopped) {
                reports += LensPassReport(pass, LensPassStatus.SKIPPED, "An earlier pass failed")
                continue
            }
            val activeBackend = backend
            if (activeBackend == null || !activeBackend.supports(pass.kind)) {
                val reason = if (activeBackend == null) {
                    "No GPU backend is installed; bundled ${pass.kind.shaderAsset} is source only"
                } else {
                    "The installed GPU backend does not support ${pass.kind.shaderAsset}"
                }
                reports += LensPassReport(pass, LensPassStatus.UNSUPPORTED, reason)
                continue
            }
            when (val result = activeBackend.execute(output, pass)) {
                is LensPassResult.Finished -> {
                    output = result.frame
                    reports += LensPassReport(pass, LensPassStatus.EXECUTED)
                }
                is LensPassResult.Failed -> {
                    reports += LensPassReport(pass, LensPassStatus.FAILED, result.reason)
                    stopped = true
                }
            }
        }
        return LensRenderResult(output, reports)
    }
}
