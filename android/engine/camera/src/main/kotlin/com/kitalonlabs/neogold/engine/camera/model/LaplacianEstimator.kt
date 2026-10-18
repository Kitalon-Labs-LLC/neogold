package com.kitalonlabs.neogold.engine.camera.model

import com.kitalonlabs.neogold.engine.vision.ImageFrame

/**
 * Computes sharpness (Laplacian variance) of an ImageFrame or region.
 * Used to select the sharpest frame across a burst capture (doc 4 Section 5.1 step 5/F).
 */
object LaplacianEstimator {

    /**
     * Computes the discrete Laplacian variance of an ImageFrame.
     * Uses step sampling for fast execution on budget phones while preserving ranking.
     */
    fun computeVariance(frame: ImageFrame, step: Int = 4): Double {
        val w = frame.width
        val h = frame.height
        if (w < 3 || h < 3) return 0.0

        var sumLap = 0.0
        var sumLapSq = 0.0
        var count = 0

        val effectiveStep = if (step < 1) 1 else step

        for (y in 1 until (h - 1) step effectiveStep) {
            for (x in 1 until (w - 1) step effectiveStep) {
                val center = frame.getLuminance(x, y)
                val left = frame.getLuminance(x - 1, y)
                val right = frame.getLuminance(x + 1, y)
                val top = frame.getLuminance(x, y - 1)
                val bottom = frame.getLuminance(x, y + 1)

                val lap = left + right + top + bottom - 4.0 * center
                sumLap += lap
                sumLapSq += lap * lap
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sumLap / count
        return StrictMath.max(0.0, (sumLapSq / count) - (mean * mean))
    }

    /**
     * Selects the index of the sharpest frame from a list of frames.
     */
    fun selectSharpestIndex(frames: List<ImageFrame>): Int {
        if (frames.isEmpty()) return 0
        var maxVariance = -1.0
        var bestIndex = 0

        for (i in frames.indices) {
            val variance = computeVariance(frames[i])
            if (variance > maxVariance) {
                maxVariance = variance
                bestIndex = i
            }
        }
        return bestIndex
    }
}
