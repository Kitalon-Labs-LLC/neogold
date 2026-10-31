package com.kitalonlabs.neogold.engine.vision

private val CARD_MARKER_IDS = 0..3
private const val PARALLEL_EPSILON = 1e-9

/**
 * The card's markers out of everything a detector reported.
 *
 * Only IDs 0 to 3 are card markers. An ID that was found more than once is dropped, never guessed: two cards in view
 * (a spare card on the cot rail, the next baby's) or a false detection both look like that, and picking one would
 * read the wrong card. The quality gate then reports the missing marker and the photo is retaken. Result is sorted
 * by ID.
 */
internal fun selectCardMarkers(found: List<RawMarker>): List<DetectedMarker> =
    found
        .filter { it.id in CARD_MARKER_IDS }
        .groupBy { it.id }
        .filterValues { it.size == 1 }
        .values
        .map { it.single() }
        .sortedBy { it.id }
        .map { DetectedMarker(it.id, it.corners, diagonalCenter(it.corners)) }

/**
 * Where a square's centre lands after perspective: the crossing of its diagonals (corners 0 to 2 and 1 to 3). The
 * mean of the four corners is off by up to 1.8 px at the 20 degree tilt limit, which the homography then inherits.
 * Falls back to the mean when the diagonals are parallel, which a real marker never produces.
 */
internal fun diagonalCenter(corners: List<Point2D>): Point2D {
    val p0 = corners[0]
    val p1 = corners[1]
    val p2 = corners[2]
    val p3 = corners[3]
    val d1x = p2.x - p0.x
    val d1y = p2.y - p0.y
    val d2x = p3.x - p1.x
    val d2y = p3.y - p1.y
    val denominator = d1x * d2y - d1y * d2x
    if (StrictMath.abs(denominator) < PARALLEL_EPSILON) {
        return Point2D(corners.sumOf { it.x } / corners.size, corners.sumOf { it.y } / corners.size)
    }
    val t = ((p1.x - p0.x) * d2y - (p1.y - p0.y) * d2x) / denominator
    return Point2D(p0.x + t * d1x, p0.y + t * d1y)
}
