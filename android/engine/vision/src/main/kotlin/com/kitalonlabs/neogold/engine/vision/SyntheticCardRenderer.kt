@file:Suppress("MagicNumber") // test-support card: every number is a pixel or millimetre dimension of the printed card

package com.kitalonlabs.neogold.engine.vision

import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.colorscience.RectPx
import com.kitalonlabs.neogold.engine.colorscience.Rgb

/**
 * Window content options for synthetic testing.
 */
enum class SyntheticWindowContent {
    SKIN_NORMAL,
    SKIN_JAUNDICE,
    CLOTH_BLUE,
    CLOTH_GREEN,
    EMPTY_HOLE,
    HIGH_CONTRAST_EDGE
}

/**
 * Configuration options for rendering a synthetic card frame.
 */
data class SyntheticRenderConfig(
    val scale: Double = 1.0,
    val tiltDegrees: Double = 0.0,
    val rotationDegrees: Double = 0.0,
    val blurRadius: Int = 0,
    val glareRadiusPx: Int = 0,
    val glareCenter: Point2D? = null,
    val exposureMultiplier: Double = 1.0,
    val unevenLightGradientDelta: Double = 0.0, // Multiplicative gradient across card
    val windowContent: SyntheticWindowContent = SyntheticWindowContent.SKIN_NORMAL,
    val omitMarkerId: Int? = null, // Set to 0..3 to simulate missing marker
    val markerFamily: String = "APRILTAG_36H11",
    val duplicateMismatchDeltaE: Double = 0.0 // Deliberate duplicate patch mismatch
)

/**
 * Synthetic card renderer generating camera frames from card geometry and reference values.
 */
class SyntheticCardRenderer {

    /**
     * Renders a 1600x1200 camera frame containing the synthetic card.
     */
    fun renderFrame(
        config: SyntheticRenderConfig = SyntheticRenderConfig(),
        frameWidth: Int = 1600,
        frameHeight: Int = 1200
    ): ImageFrame {
        // 1. Render canonical 1200x760 card image
        val canonicalPixels = renderCanonicalCard(config)

        // 2. Compute 3D transformation and projection to frame
        val cw = CardGeometry.CANONICAL_WIDTH
        val ch = CardGeometry.CANONICAL_HEIGHT
        val fw = frameWidth
        val fh = frameHeight

        val corners3D = arrayOf(
            doubleArrayOf(-cw / 2.0, -ch / 2.0, 0.0),
            doubleArrayOf(cw / 2.0, -ch / 2.0, 0.0),
            doubleArrayOf(cw / 2.0, ch / 2.0, 0.0),
            doubleArrayOf(-cw / 2.0, ch / 2.0, 0.0)
        )

        // Apply scale
        for (pt in corners3D) {
            pt[0] *= config.scale
            pt[1] *= config.scale
        }

        // Apply tilt (rotation around X-axis)
        val radTilt = config.tiltDegrees * StrictMath.PI / 180.0
        val cosT = StrictMath.cos(radTilt)
        val sinT = StrictMath.sin(radTilt)

        // Apply in-plane rotation (around Z-axis)
        val radRot = config.rotationDegrees * StrictMath.PI / 180.0
        val cosR = StrictMath.cos(radRot)
        val sinR = StrictMath.sin(radRot)

        val projectedCorners = mutableListOf<Point2D>()
        val focalDist = 1800.0

        for (pt in corners3D) {
            // Rotate X (tilt)
            val y1 = pt[1] * cosT - pt[2] * sinT
            val z1 = pt[1] * sinT + pt[2] * cosT
            val x1 = pt[0]

            // Rotate Z (in-plane)
            val x2 = x1 * cosR - y1 * sinR
            val y2 = x1 * sinR + y1 * cosR
            val z2 = z1

            // Project to camera screen
            val z = z2 + focalDist
            val projX = (x2 * focalDist) / z + fw / 2.0
            val projY = (y2 * focalDist) / z + fh / 2.0
            projectedCorners.add(Point2D(projX, projY))
        }

        val canonicalCorners = listOf(
            Point2D(0.0, 0.0),
            Point2D(cw.toDouble(), 0.0),
            Point2D(cw.toDouble(), ch.toDouble()),
            Point2D(0.0, ch.toDouble())
        )

        // Homography from projected frame coordinates to canonical coordinates
        val frameToCanon = Homography.findHomography(projectedCorners, canonicalCorners)

        // 3. Render frame pixels using inverse homography
        val bgLum = (220 * config.exposureMultiplier).toInt().coerceIn(10, 255)
        val frameData = IntArray(fw * fh) { (0xFF shl 24) or (bgLum shl 16) or (bgLum shl 8) or bgLum }

        for (fy in 0 until fh) {
            for (fx in 0 until fw) {
                val canonPt = frameToCanon.transform(Point2D(fx.toDouble(), fy.toDouble()))
                val cx = canonPt.x.toInt()
                val cy = canonPt.y.toInt()

                if (cx in 0 until cw && cy in 0 until ch) {
                    val pixel = canonicalPixels[cy * cw + cx]
                    frameData[fy * fw + fx] = pixel
                }
            }
        }

        // 4. Apply Blur if requested
        if (config.blurRadius > 0) {
            applyBoxBlur(frameData, fw, fh, config.blurRadius)
        }

        return ImageFrame(fw, fh, frameData)
    }

    @Suppress(
        "CyclomaticComplexMethod",
        "LoopWithTooManyJumpStatements"
    ) // test-support renderer, mirrors the printed card layout
    private fun renderCanonicalCard(config: SyntheticRenderConfig): IntArray {
        val cw = CardGeometry.CANONICAL_WIDTH
        val ch = CardGeometry.CANONICAL_HEIGHT
        val pixels = IntArray(cw * ch) { (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255 }

        fun fillRect(rect: RectPx, rgb: Rgb) {
            val r = (rgb.r * 255.0 * config.exposureMultiplier).toInt().coerceIn(0, 255)
            val g = (rgb.g * 255.0 * config.exposureMultiplier).toInt().coerceIn(0, 255)
            val b = (rgb.b * 255.0 * config.exposureMultiplier).toInt().coerceIn(0, 255)
            val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

            for (y in rect.y until (rect.y + rect.h)) {
                for (x in rect.x until (rect.x + rect.w)) {
                    if (x in 0 until cw && y in 0 until ch) {
                        var finalRgb = argb
                        if (config.unevenLightGradientDelta > 0.0) {
                            // Apply horizontal lighting gradient across the card
                            val gradFactor = 1.0 + (x.toDouble() / cw - 0.5) * config.unevenLightGradientDelta
                            val gr = ((r * gradFactor)).toInt().coerceIn(0, 255)
                            val gg = ((g * gradFactor)).toInt().coerceIn(0, 255)
                            val gb = ((b * gradFactor)).toInt().coerceIn(0, 255)
                            finalRgb = (0xFF shl 24) or (gr shl 16) or (gg shl 8) or gb
                        }
                        pixels[y * cw + x] = finalRgb
                    }
                }
            }
        }

        // 1. Draw Fiducial Markers
        val codebook = if (config.markerFamily == "ARUCO_4X4") {
            MarkerCodebook.ARUCO_4X4_BITS
        } else {
            MarkerCodebook.APRILTAG_36H11_BITS
        }
        val gridSize = if (config.markerFamily == "ARUCO_4X4") 6 else 8

        for ((id, rect) in CardGeometry.MARKER_RECTS) {
            if (config.omitMarkerId == id) {
                // Omitted marker (e.g. blanked out or occluded)
                continue
            }
            val rows = codebook[id] ?: continue
            val modSize = rect.w.toDouble() / gridSize

            for (r in 0 until gridSize) {
                for (c in 0 until gridSize) {
                    val isBlack = rows[r][c] == '0'
                    val mx = (rect.x + c * modSize).toInt()
                    val my = (rect.y + r * modSize).toInt()
                    val mw = StrictMath.ceil(modSize).toInt()
                    val mh = StrictMath.ceil(modSize).toInt()
                    val color = if (isBlack) Rgb(0.0, 0.0, 0.0) else Rgb(1.0, 1.0, 1.0)
                    fillRect(RectPx(mx, my, mw, mh), color)
                }
            }
        }

        // 2. Draw 23 Color Patches
        for ((id, patchDef) in CardGeometry.PATCHES) {
            val lab = if (config.duplicateMismatchDeltaE > 0.0 && id in listOf("G2D", "G4D", "K5D", "K8D")) {
                // Apply deliberate color shift on duplicate patch to trigger NONUNIFORM_PATCHES
                Lab(
                    patchDef.nominalLab.l + config.duplicateMismatchDeltaE,
                    patchDef.nominalLab.a,
                    patchDef.nominalLab.b
                )
            } else {
                patchDef.nominalLab
            }
            val rgb = lab.toSRgb()
            fillRect(patchDef.outerRect, rgb)
        }

        // 3. Draw Skin Window
        val winRect = CardGeometry.WINDOW_OUTER_RECT
        when (config.windowContent) {
            SyntheticWindowContent.SKIN_NORMAL -> {
                // Healthy newborn skin: L*=62, a*=14, b*=20
                val skinRgb = Lab(62.0, 14.0, 20.0).toSRgb()
                fillRect(winRect, skinRgb)
            }
            SyntheticWindowContent.SKIN_JAUNDICE -> {
                // Jaundiced infant skin: L*=66, a*=10, b*=32
                val jaundiceRgb = Lab(66.0, 10.0, 32.0).toSRgb()
                fillRect(winRect, jaundiceRgb)
            }
            SyntheticWindowContent.CLOTH_BLUE -> {
                // Blue cloth: L*=40, a*=5, b*=-35 (fails skin chromaticity)
                fillRect(winRect, Rgb.from8Bit(40, 80, 200))
            }
            SyntheticWindowContent.CLOTH_GREEN -> {
                // Green cloth: L*=50, a*=-30, b*=25 (fails skin chromaticity)
                fillRect(winRect, Rgb.from8Bit(30, 180, 50))
            }
            SyntheticWindowContent.EMPTY_HOLE -> {
                // Dark empty window aperture
                fillRect(winRect, Rgb.from8Bit(15, 15, 15))
            }
            SyntheticWindowContent.HIGH_CONTRAST_EDGE -> {
                // High contrast edge: half black, half white (fails edge gradient & variance)
                val halfW = winRect.w / 2
                fillRect(RectPx(winRect.x, winRect.y, halfW, winRect.h), Rgb(0.0, 0.0, 0.0))
                fillRect(RectPx(winRect.x + halfW, winRect.y, halfW, winRect.h), Rgb(1.0, 1.0, 1.0))
            }
        }

        // 4. Draw Glare Spot if requested
        if (config.glareRadiusPx > 0) {
            val center = config.glareCenter ?: Point2D(CardGeometry.WINDOW_INNER_RECT.x + 80.0, CardGeometry.WINDOW_INNER_RECT.y + 80.0)
            val rSq = (config.glareRadiusPx * config.glareRadiusPx).toDouble()
            val gx = center.x.toInt()
            val gy = center.y.toInt()
            val gr = config.glareRadiusPx

            for (y in (gy - gr)..(gy + gr)) {
                for (x in (gx - gr)..(gx + gr)) {
                    val dx = x - center.x
                    val dy = y - center.y
                    if (dx * dx + dy * dy <= rSq && x in 0 until cw && y in 0 until ch) {
                        pixels[y * cw + x] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
                    }
                }
            }
        }

        return pixels
    }

    private fun applyBoxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val temp = pixels.clone()
        val div = 2 * radius + 1

        // 1. Horizontal blur
        val horizontal = IntArray(w * h)
        for (y in 0 until h) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val px = temp[y * w + i.coerceIn(0, w - 1)]
                sumR += (px shr 16) and 0xFF
                sumG += (px shr 8) and 0xFF
                sumB += px and 0xFF
            }

            for (x in 0 until w) {
                horizontal[y * w + x] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                val leftPx = temp[y * w + (x - radius).coerceIn(0, w - 1)]
                val rightPx = temp[y * w + (x + radius + 1).coerceIn(0, w - 1)]
                sumR += ((rightPx shr 16) and 0xFF) - ((leftPx shr 16) and 0xFF)
                sumG += ((rightPx shr 8) and 0xFF) - ((leftPx shr 8) and 0xFF)
                sumB += (rightPx and 0xFF) - (leftPx and 0xFF)
            }
        }

        // 2. Vertical blur
        for (x in 0 until w) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val px = horizontal[i.coerceIn(0, h - 1) * w + x]
                sumR += (px shr 16) and 0xFF
                sumG += (px shr 8) and 0xFF
                sumB += px and 0xFF
            }

            for (y in 0 until h) {
                pixels[y * w + x] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                val topPx = horizontal[(y - radius).coerceIn(0, h - 1) * w + x]
                val botPx = horizontal[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                sumR += ((botPx shr 16) and 0xFF) - ((topPx shr 16) and 0xFF)
                sumG += ((botPx shr 8) and 0xFF) - ((topPx shr 8) and 0xFF)
                sumB += (botPx and 0xFF) - (topPx and 0xFF)
            }
        }
    }
}
