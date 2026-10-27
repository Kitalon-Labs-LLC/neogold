package com.kitalonlabs.neogold.engine.vision

/**
 * Marker detection interface.
 */
interface MarkerDetector {
    fun detectMarkers(frame: ImageFrame): List<DetectedMarker>
}

/**
 * Robust marker decoder supporting AprilTag 36h11 and ArUco 4x4.
 */
object MarkerCodebook {
    // AprilTag 36h11 bit patterns (8x8 grid with 1 border) from card-v1.json
    val APRILTAG_36H11_BITS = mapOf(
        0 to listOf(
            "00000000",
            "00010000",
            "00110100",
            "00001010",
            "00001100",
            "01011100",
            "01010110",
            "00000000"
        ),
        1 to listOf(
            "00000000",
            "01001000",
            "01011010",
            "00001100",
            "00011110",
            "01110100",
            "00110110",
            "00000000"
        ),
        2 to listOf(
            "00000000",
            "00111000",
            "00010000",
            "01001000",
            "00000010",
            "00100100",
            "01110110",
            "00000000"
        ),
        3 to listOf(
            "00000000",
            "00001100",
            "00100110",
            "01001010",
            "01110010",
            "01110000",
            "01001110",
            "00000000"
        )
    )

    // ArUco 4x4 bit patterns (6x6 grid with 1 border) from card-v1.json
    val ARUCO_4X4_BITS = mapOf(
        0 to listOf("000000", "010110", "001010", "000110", "000100", "000000"),
        1 to listOf("000000", "000000", "011110", "010010", "010100", "000000"),
        2 to listOf("000000", "000110", "000110", "000100", "011010", "000000"),
        3 to listOf("000000", "010010", "010010", "001000", "001100", "000000")
    )
}

/**
 * Pure Kotlin marker detector capable of finding corner markers in camera and synthetic frames.
 */
class FastMarkerDetector : MarkerDetector {

    override fun detectMarkers(frame: ImageFrame): List<DetectedMarker> {
        val detected = mutableListOf<DetectedMarker>()

        // Find candidate quads by adaptive thresholding / contour / connected component search
        val quads = findCandidateQuads(frame)

        for (quad in quads) {
            val marker = decodeQuad(frame, quad)
            if (marker != null) {
                // Ensure no duplicate IDs
                if (detected.none { it.id == marker.id }) {
                    detected.add(marker)
                }
            }
        }

        return detected
    }

    /**
     * Decodes a candidate quadrilateral by sampling its grid of modules.
     */
    fun decodeQuad(frame: ImageFrame, corners: List<Point2D>): DetectedMarker? {
        if (corners.size != 4) return null

        val reversed = listOf(corners[0], corners[3], corners[2], corners[1])
        val orientations = listOf(corners, reversed)

        for (cand in orientations) {
            // Try AprilTag 36h11 (8x8) first
            val aprilResult = decodeGrid(frame, cand, gridSize = 8, MarkerCodebook.APRILTAG_36H11_BITS, maxErrors = 3)
            if (aprilResult != null) {
                val (id, reorderedCorners) = aprilResult
                val center = Point2D(
                    reorderedCorners.sumOf { it.x } / 4.0,
                    reorderedCorners.sumOf { it.y } / 4.0
                )
                return DetectedMarker(id, reorderedCorners, center)
            }

            // Try ArUco 4x4 (6x6)
            val arucoResult = decodeGrid(frame, cand, gridSize = 6, MarkerCodebook.ARUCO_4X4_BITS, maxErrors = 1)
            if (arucoResult != null) {
                val (id, reorderedCorners) = arucoResult
                val center = Point2D(
                    reorderedCorners.sumOf { it.x } / 4.0,
                    reorderedCorners.sumOf { it.y } / 4.0
                )
                return DetectedMarker(id, reorderedCorners, center)
            }
        }

        return null
    }

    private fun decodeGrid(
        frame: ImageFrame,
        corners: List<Point2D>,
        gridSize: Int,
        codebook: Map<Int, List<String>>,
        maxErrors: Int
    ): Pair<Int, List<Point2D>>? {
        // Compute homography from unit square [0,1]^2 to quad corners
        val unitSquare = listOf(
            Point2D(0.0, 0.0),
            Point2D(1.0, 0.0),
            Point2D(1.0, 1.0),
            Point2D(0.0, 1.0)
        )
        val H = try {
            Homography.findHomography(unitSquare, corners)
        } catch (_: Exception) {
            return null
        }

        // Sample grid cells
        val cellValues = Array(gridSize) { DoubleArray(gridSize) }
        var minLum = 255.0
        var maxLum = 0.0

        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                val u = (c + 0.5) / gridSize
                val v = (r + 0.5) / gridSize
                val pt = H.transform(Point2D(u, v))
                val px = pt.x.toInt().coerceIn(0, frame.width - 1)
                val py = pt.y.toInt().coerceIn(0, frame.height - 1)
                val lum = frame.getLuminance(px, py)
                cellValues[r][c] = lum
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }
        }

        // Contrast check
        if (maxLum - minLum < 30.0) return null
        val thresh = (minLum + maxLum) * 0.5

        // Binarize
        val bits = Array(gridSize) { r ->
            CharArray(gridSize) { c ->
                if (cellValues[r][c] > thresh) '1' else '0'
            }
        }

        // Check against codebook across 4 rotations (0, 90, 180, 270)
        for ((tagId, expectedRows) in codebook) {
            for (rot in 0 until 4) {
                var errors = 0
                for (r in 0 until gridSize) {
                    for (c in 0 until gridSize) {
                        val (rr, cc) = rotateIndices(r, c, gridSize, rot)
                        if (bits[rr][cc] != expectedRows[r][c]) {
                            errors++
                        }
                    }
                }
                if (errors <= maxErrors) {
                    // Reorder corners according to detected rotation
                    val reordered = List(4) { i ->
                        corners[(i + rot) % 4]
                    }
                    return Pair(tagId, reordered)
                }
            }
        }

        return null
    }

    private fun rotateIndices(r: Int, c: Int, n: Int, rot: Int): Pair<Int, Int> {
        return when (rot % 4) {
            0 -> Pair(r, c)
            1 -> Pair(c, n - 1 - r) // 90 deg clockwise
            2 -> Pair(n - 1 - r, n - 1 - c) // 180 deg
            3 -> Pair(n - 1 - c, r) // 270 deg
            else -> Pair(r, c)
        }
    }

    /**
     * Extracts potential quadrilateral corners from frame.
     * Uses thresholding and polygon contour tracking.
     */
    fun findCandidateQuads(frame: ImageFrame): List<List<Point2D>> {
        val w = frame.width
        val h = frame.height
        val quads = mutableListOf<List<Point2D>>()

        // Scan image in blocks to find dark connected regions (markers)
        // Adaptive thresholding sampling
        val step = 4
        val darkMask = BooleanArray((w / step) * (h / step))
        val mw = w / step
        val mh = h / step

        var sumLum = 0.0
        var count = 0
        for (y in 0 until mh) {
            for (x in 0 until mw) {
                val lum = frame.getLuminance(x * step, y * step)
                sumLum += lum
                count++
            }
        }
        val avgLum = if (count > 0) sumLum / count else 128.0
        val darkThresh = avgLum * 0.65 // Black marker modules

        for (y in 0 until mh) {
            for (x in 0 until mw) {
                val lum = frame.getLuminance(x * step, y * step)
                darkMask[y * mw + x] = (lum < darkThresh)
            }
        }

        // Group connected dark blobs and fit bounding quads
        val visited = BooleanArray(darkMask.size)
        val minBlob = 16 // Minimum size in downsampled units
        val maxBlob = (mw * mh) / 4

        for (y in 0 until mh) {
            for (x in 0 until mw) {
                val idx = y * mw + x
                if (darkMask[idx] && !visited[idx]) {
                    var blobCount = 0
                    var minX = x
                    var maxX = x
                    var minY = y
                    var maxY = y

                    // Breadth-first search
                    val queue = IntArray(mw * mh)
                    var qHead = 0
                    var qTail = 0
                    queue[qTail++] = idx
                    visited[idx] = true
                    val boundaryX = mutableListOf<Int>()
                    val boundaryY = mutableListOf<Int>()
                    var sumX = 0L
                    var sumY = 0L

                    while (qHead < qTail) {
                        val curr = queue[qHead++]
                        val cx = curr % mw
                        val cy = curr / mw
                        blobCount++
                        sumX += cx
                        sumY += cy

                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        var isBoundary = false

                        // Neighbors
                        val n1 = (cy - 1) * mw + cx
                        if (cy > 0) {
                            if (darkMask[n1] && !visited[n1]) {
                                visited[n1] = true
                                queue[qTail++] = n1
                            } else if (!darkMask[n1]) isBoundary = true
                        } else {
                            isBoundary = true
                        }

                        val n2 = (cy + 1) * mw + cx
                        if (cy < mh - 1) {
                            if (darkMask[n2] && !visited[n2]) {
                                visited[n2] = true
                                queue[qTail++] = n2
                            } else if (!darkMask[n2]) isBoundary = true
                        } else {
                            isBoundary = true
                        }

                        val n3 = cy * mw + (cx - 1)
                        if (cx > 0) {
                            if (darkMask[n3] && !visited[n3]) {
                                visited[n3] = true
                                queue[qTail++] = n3
                            } else if (!darkMask[n3]) isBoundary = true
                        } else {
                            isBoundary = true
                        }

                        val n4 = cy * mw + (cx + 1)
                        if (cx < mw - 1) {
                            if (darkMask[n4] && !visited[n4]) {
                                visited[n4] = true
                                queue[qTail++] = n4
                            } else if (!darkMask[n4]) isBoundary = true
                        } else {
                            isBoundary = true
                        }

                        if (isBoundary) {
                            boundaryX.add(cx)
                            boundaryY.add(cy)
                        }
                    }

                    if (blobCount in minBlob..maxBlob) {
                        val bw = (maxX - minX + 1) * step
                        val bh = (maxY - minY + 1) * step
                        val aspect = bw.toDouble() / StrictMath.max(1, bh)

                        // Marker outer boundary is square-ish (aspect between 0.5 and 2.0)
                        if (aspect in 0.5..2.0) {
                            // 1. Axis-aligned bounding box
                            val px1 = (minX * step).toDouble()
                            val py1 = (minY * step).toDouble()
                            val px2 = ((maxX + 1) * step).toDouble()
                            val py2 = ((maxY + 1) * step).toDouble()

                            quads.add(
                                listOf(
                                    Point2D(px1, py1),
                                    Point2D(px2, py1),
                                    Point2D(px2, py2),
                                    Point2D(px1, py2)
                                )
                            )

                            // 2. Rotated 4-corner quad from extremal boundary points
                            if (boundaryX.size >= 8) {
                                val bCount = boundaryX.size
                                val meanX = (sumX.toDouble() / blobCount)
                                val meanY = (sumY.toDouble() / blobCount)

                                // Farthest point from centroid
                                var maxDistSq0 = -1.0
                                var p0Idx = 0
                                for (i in 0 until bCount) {
                                    val dx = boundaryX[i] - meanX
                                    val dy = boundaryY[i] - meanY
                                    val dSq = dx * dx + dy * dy
                                    if (dSq > maxDistSq0) {
                                        maxDistSq0 = dSq
                                        p0Idx = i
                                    }
                                }

                                val p0x = boundaryX[p0Idx]
                                val p0y = boundaryY[p0Idx]

                                // Point farthest from p0 (opposite corner)
                                var maxDistSq2 = -1.0
                                var p2Idx = 0
                                for (i in 0 until bCount) {
                                    val dx = (boundaryX[i] - p0x).toDouble()
                                    val dy = (boundaryY[i] - p0y).toDouble()
                                    val dSq = dx * dx + dy * dy
                                    if (dSq > maxDistSq2) {
                                        maxDistSq2 = dSq
                                        p2Idx = i
                                    }
                                }

                                val p2x = boundaryX[p2Idx]
                                val p2y = boundaryY[p2Idx]

                                // Diagonal line p0 -> p2: points with max and min signed area (cross product)
                                val lineDx = (p2x - p0x).toDouble()
                                val lineDy = (p2y - p0y).toDouble()
                                var maxCross = 0.0
                                var minCross = 0.0
                                var p1Idx = p0Idx
                                var p3Idx = p2Idx

                                for (i in 0 until bCount) {
                                    val cross = lineDx * (boundaryY[i] - p0y) - lineDy * (boundaryX[i] - p0x)
                                    if (cross > maxCross) {
                                        maxCross = cross
                                        p1Idx = i
                                    } else if (cross < minCross) {
                                        minCross = cross
                                        p3Idx = i
                                    }
                                }

                                if (maxCross > 0.0 && minCross < 0.0) {
                                    val s = step.toDouble()
                                    val half = s * 0.5
                                    quads.add(
                                        listOf(
                                            Point2D(p0x * s + half, p0y * s + half),
                                            Point2D(boundaryX[p1Idx] * s + half, boundaryY[p1Idx] * s + half),
                                            Point2D(p2x * s + half, p2y * s + half),
                                            Point2D(boundaryX[p3Idx] * s + half, boundaryY[p3Idx] * s + half)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        return quads
    }
}
