package com.kitalonlabs.neogold.engine.colorscience

/**
 * Pixel bounding box in canonical card coordinates.
 */
data class RectPx(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
) {
    init {
        require(w > 0 && h > 0) { "Rect dimensions must be positive (got ${w}x$h)" }
    }
}

/**
 * Region sampling and trimmed statistics.
 * Drops the brightest and darkest 10% of pixels to eliminate edge bleed,
 * contact boundaries, minor scratches, and specular highlights.
 */
object RegionSampling {

    const val PATCH_INNER_FRACTION = 0.60
    const val WINDOW_INNER_FRACTION = 0.70
    const val DEFAULT_TRIM_FRACTION = 0.10

    /**
     * Calculates the inner sampling rectangle centered within the given outer rectangle.
     * E.g. innerFraction = 0.60 for calibration patches, 0.70 for the punched skin window.
     */
    fun computeInnerRegion(rect: RectPx, innerFraction: Double): RectPx {
        require(innerFraction in 0.01..1.0) { "Inner fraction must be in (0.0, 1.0] (got $innerFraction)" }
        val innerW = StrictMath.round(rect.w * innerFraction).toInt()
        val innerH = StrictMath.round(rect.h * innerFraction).toInt()
        val marginX = (rect.w - innerW) / 2
        val marginY = (rect.h - innerH) / 2
        return RectPx(
            x = rect.x + marginX,
            y = rect.y + marginY,
            w = innerW,
            h = innerH
        )
    }

    /**
     * Computes the 1D trimmed mean of an array of Double values,
     * discarding [trimFraction] from the lowest and highest ends.
     */
    fun trimmedMean(values: DoubleArray, trimFraction: Double = DEFAULT_TRIM_FRACTION): Double {
        require(values.isNotEmpty()) { "Values array must not be empty" }
        require(trimFraction in 0.0..0.49) { "Trim fraction must be in [0.0, 0.49] (got $trimFraction)" }

        val n = values.size
        val dropCount = StrictMath.floor(n * trimFraction).toInt()
        if (dropCount == 0 || n <= 2 * dropCount) {
            var sum = 0.0
            for (v in values) sum += v
            return sum / n
        }

        val sorted = values.clone()
        sorted.sort()

        val start = dropCount
        val end = n - dropCount
        var sum = 0.0
        val count = end - start
        for (i in start until end) {
            sum += sorted[i]
        }
        return sum / count
    }

    /**
     * Computes the trimmed mean of a list of RGB pixels.
     * Pixels are sorted by relative luminance, and the darkest [trimFraction]
     * and brightest [trimFraction] pixels are discarded.
     * The mean R, G, and B of the remaining central pixels is returned.
     */
    fun trimmedMeanRgb(pixels: List<Rgb>, trimFraction: Double = DEFAULT_TRIM_FRACTION): Rgb {
        require(pixels.isNotEmpty()) { "Pixels list must not be empty" }
        require(trimFraction in 0.0..0.49) { "Trim fraction must be in [0.0, 0.49] (got $trimFraction)" }

        val n = pixels.size
        val dropCount = StrictMath.floor(n * trimFraction).toInt()
        if (dropCount == 0 || n <= 2 * dropCount) {
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            for (p in pixels) {
                sumR += p.r
                sumG += p.g
                sumB += p.b
            }
            return Rgb(sumR / n, sumG / n, sumB / n)
        }

        // Sort by luminance
        val sorted = pixels.sortedBy { it.luminance() }

        val start = dropCount
        val end = n - dropCount
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        val count = end - start
        for (i in start until end) {
            val p = sorted[i]
            sumR += p.r
            sumG += p.g
            sumB += p.b
        }
        return Rgb(sumR / count, sumG / count, sumB / count)
    }

    /**
     * Samples the inner region of a rectangle from a 2D image pixel accessor.
     */
    fun sampleInnerRegionPixels(
        imageWidth: Int,
        imageHeight: Int,
        outerRect: RectPx,
        innerFraction: Double,
        getPixel: (x: Int, y: Int) -> Rgb
    ): List<Rgb> {
        val inner = computeInnerRegion(outerRect, innerFraction)
        val pixels = ArrayList<Rgb>(inner.w * inner.h)
        val xEnd = StrictMath.min(imageWidth, inner.x + inner.w)
        val yEnd = StrictMath.min(imageHeight, inner.y + inner.h)

        for (y in inner.y until yEnd) {
            for (x in inner.x until xEnd) {
                pixels.add(getPixel(x, y))
            }
        }
        return pixels
    }

    /**
     * Samples a color calibration patch (inner 60% with 10% trimmed mean).
     */
    fun samplePatchRgb(
        imageWidth: Int,
        imageHeight: Int,
        patchRect: RectPx,
        getPixel: (x: Int, y: Int) -> Rgb
    ): Rgb {
        val pixels = sampleInnerRegionPixels(imageWidth, imageHeight, patchRect, PATCH_INNER_FRACTION, getPixel)
        return trimmedMeanRgb(pixels, DEFAULT_TRIM_FRACTION)
    }

    /**
     * Samples the punched skin window (inner 70% with 10% trimmed mean).
     */
    fun sampleWindowRgb(
        imageWidth: Int,
        imageHeight: Int,
        windowRect: RectPx,
        getPixel: (x: Int, y: Int) -> Rgb
    ): Rgb {
        val pixels = sampleInnerRegionPixels(imageWidth, imageHeight, windowRect, WINDOW_INNER_FRACTION, getPixel)
        return trimmedMeanRgb(pixels, DEFAULT_TRIM_FRACTION)
    }
}
