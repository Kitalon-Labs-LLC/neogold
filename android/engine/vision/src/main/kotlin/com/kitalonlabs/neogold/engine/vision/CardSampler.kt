package com.kitalonlabs.neogold.engine.vision

import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.colorscience.RectPx
import com.kitalonlabs.neogold.engine.colorscience.RegionSampling
import com.kitalonlabs.neogold.engine.colorscience.Rgb

/**
 * Patch sampling output containing trimmed mean RGB, Lab, and glare metrics.
 */
data class SampledPatch(
    val id: String,
    val trimmedMeanRgb: Rgb,
    val lab: Lab,
    val saturatedPixelFraction: Double
)

/**
 * Samples calibration patches and the punched skin window from a canonical card image.
 */
object CardSampler {

    const val SATURATION_THRESHOLD = 250.0 / 255.0

    /**
     * Samples a rectangular region from canonical card, returning all pixels and saturated fraction.
     */
    fun extractRegionPixels(card: CanonicalCardImage, rect: RectPx): Pair<List<Rgb>, Double> {
        val pixels = ArrayList<Rgb>(rect.w * rect.h)
        var saturatedCount = 0

        for (y in rect.y until (rect.y + rect.h)) {
            for (x in rect.x until (rect.x + rect.w)) {
                val rgb = card.getRgb(x, y)
                pixels.add(rgb)
                if (rgb.r >= SATURATION_THRESHOLD || rgb.g >= SATURATION_THRESHOLD || rgb.b >= SATURATION_THRESHOLD) {
                    saturatedCount++
                }
            }
        }

        val fractionSaturated = if (pixels.isNotEmpty()) saturatedCount.toDouble() / pixels.size else 0.0
        return Pair(pixels, fractionSaturated)
    }

    /**
     * Samples all 23 patches on the canonical card.
     */
    fun samplePatches(card: CanonicalCardImage): Map<String, SampledPatch> {
        val result = HashMap<String, SampledPatch>(PATCH_COUNT)

        for ((id, patchDef) in CardGeometry.PATCHES) {
            val (pixels, satFraction) = extractRegionPixels(card, patchDef.innerSampleRect)
            val trimmedRgb = RegionSampling.trimmedMeanRgb(pixels, RegionSampling.DEFAULT_TRIM_FRACTION)
            val lab = trimmedRgb.toLab()
            result[id] = SampledPatch(id, trimmedRgb, lab, satFraction)
        }

        return result
    }

    /**
     * Samples the punched skin window (inner 70%) and evaluates skin plausibility.
     */
    fun sampleSkinWindow(card: CanonicalCardImage, thresholds: QualityThresholds): SkinWindowSample {
        val innerRect = CardGeometry.WINDOW_INNER_RECT
        val (pixels, satFraction) = extractRegionPixels(card, innerRect)

        val trimmedRgb = RegionSampling.trimmedMeanRgb(pixels, RegionSampling.DEFAULT_TRIM_FRACTION)
        val meanLab = trimmedRgb.toLab()

        // Compute luminance variance across the inner window
        var sumLum = 0.0
        var sumLumSq = 0.0
        val lumList = DoubleArray(pixels.size)
        for (i in pixels.indices) {
            val lum = pixels[i].luminance()
            lumList[i] = lum
            sumLum += lum
            sumLumSq += lum * lum
        }
        val n = pixels.size.toDouble()
        val meanLum = sumLum / n
        val lumVariance = StrictMath.max(0.0, (sumLumSq / n) - (meanLum * meanLum))

        // Compute maximum edge gradient across the window (3x3 Sobel or central differences)
        var maxGradient = 0.0
        val stride = innerRect.w
        for (y in 1 until (innerRect.h - 1)) {
            for (x in 1 until (innerRect.w - 1)) {
                val lumL = lumList[y * stride + (x - 1)]
                val lumR = lumList[y * stride + (x + 1)]
                val lumT = lumList[(y - 1) * stride + x]
                val lumB = lumList[(y + 1) * stride + x]

                val dx = (lumR - lumL) * 0.5
                val dy = (lumB - lumT) * 0.5
                val grad = StrictMath.sqrt(dx * dx + dy * dy)
                if (grad > maxGradient) maxGradient = grad
            }
        }

        // Skin plausibility evaluation
        val isPlausible = (meanLab.l in thresholds.minSkinL..thresholds.maxSkinL) &&
            (meanLab.a in thresholds.minSkinA..thresholds.maxSkinA) &&
            (meanLab.b in thresholds.minSkinB..thresholds.maxSkinB) &&
            (lumVariance <= thresholds.maxSkinVariance) &&
            (maxGradient <= thresholds.maxSkinEdgeGradient)

        return SkinWindowSample(
            innerRect = innerRect,
            trimmedMeanRgb = trimmedRgb,
            meanLab = meanLab,
            luminanceVariance = lumVariance,
            maxEdgeGradient = maxGradient,
            saturatedPixelFraction = satFraction,
            isPlausibleSkin = isPlausible
        )
    }
}

private const val PATCH_COUNT = 23
