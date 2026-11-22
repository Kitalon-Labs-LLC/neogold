@file:Suppress("MagicNumber") // default threshold table (doc 4 Section 5.2), canonical card size and luma weights

package com.kitalonlabs.neogold.engine.vision

import com.kitalonlabs.neogold.engine.colorscience.ColorConversions
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.colorscience.RectPx
import com.kitalonlabs.neogold.engine.colorscience.Rgb

/**
 * Quality failure reason codes in exact order specified in doc 4 Section 5.3.
 */
enum class QualityReasonCode {
    NO_CARD,
    CARD_ANGLE,
    NONUNIFORM_PATCHES,
    BLUR,
    GLARE,
    UNDEREXPOSED,
    OVEREXPOSED,
    UNEVEN_LIGHT,
    HIGH_RESIDUAL,
    WINDOW_NOT_SKIN
}

/**
 * Quality thresholds injected from triage configuration (never hardcoded constants in code).
 */
data class QualityThresholds(
    val maxCardAngleDegrees: Double = 20.0,
    val maxPatchNonuniformityDelta: Double = 8.0,
    val minBlurLaplacianVariance: Double = 100.0,
    val maxGlarePixelFraction: Double = 0.02,
    val minExposureMidGray: Double = 40.0,
    val maxExposureMidGray: Double = 215.0,
    val maxLightUniformityDelta: Double = 8.0,
    val maxCorrectionResidualDeltaE: Double = 5.0,
    // Skin plausibility thresholds
    val minSkinL: Double = 25.0,
    val maxSkinL: Double = 85.0,
    val minSkinA: Double = 3.0,
    val maxSkinA: Double = 35.0,
    val minSkinB: Double = 8.0,
    val maxSkinB: Double = 45.0,
    val maxSkinVariance: Double = 250.0,
    val maxSkinEdgeGradient: Double = 25.0
) {
    companion object {
        /**
         * Parse from key-value map loaded from triage-config.json qualityThresholds object.
         */
        fun fromMap(map: Map<String, Any?>): QualityThresholds {
            fun getDouble(key: String, default: Double): Double {
                val value = map[key] ?: return default
                return when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull() ?: default
                    else -> default
                }
            }

            return QualityThresholds(
                maxCardAngleDegrees = getDouble("maxCardAngleDegrees", 20.0),
                maxPatchNonuniformityDelta = getDouble("maxPatchNonuniformityDelta", 8.0),
                minBlurLaplacianVariance = getDouble("minBlurLaplacianVariance", 100.0),
                maxGlarePixelFraction = getDouble("maxGlarePixelFraction", 0.02),
                minExposureMidGray = getDouble("minExposureMidGray", 40.0),
                maxExposureMidGray = getDouble("maxExposureMidGray", 215.0),
                maxLightUniformityDelta = getDouble("maxLightUniformityDelta", 8.0),
                maxCorrectionResidualDeltaE = getDouble("maxCorrectionResidualDeltaE", 5.0),
                minSkinL = getDouble("minSkinL", 25.0),
                maxSkinL = getDouble("maxSkinL", 85.0),
                minSkinA = getDouble("minSkinA", 3.0),
                maxSkinA = getDouble("maxSkinA", 35.0),
                minSkinB = getDouble("minSkinB", 8.0),
                maxSkinB = getDouble("maxSkinB", 45.0),
                maxSkinVariance = getDouble("maxSkinVariance", 250.0),
                maxSkinEdgeGradient = getDouble("maxSkinEdgeGradient", 25.0)
            )
        }
    }
}

/**
 * 2D point with subpixel precision.
 */
data class Point2D(val x: Double, val y: Double) {
    fun distanceTo(other: Point2D): Double {
        val dx = x - other.x
        val dy = y - other.y
        return StrictMath.sqrt(dx * dx + dy * dy)
    }
}

fun Rgb.toLab(): Lab = ColorConversions.xyzToLab(ColorConversions.sRgbToXyz(this))
fun Lab.toSRgb(): Rgb = ColorConversions.xyzToSRgb(ColorConversions.labToXyz(this))

/**
 * Image container supporting packed 24-bit RGB or 32-bit ARGB/RGBA/gray formats.
 */
class ImageFrame(
    val width: Int,
    val height: Int,
    val data: IntArray // ARGB format 0xAARRGGBB
) {
    init {
        require(width > 0 && height > 0) { "Dimensions must be positive ($width x $height)" }
        require(
            data.size >= width * height
        ) { "Data 