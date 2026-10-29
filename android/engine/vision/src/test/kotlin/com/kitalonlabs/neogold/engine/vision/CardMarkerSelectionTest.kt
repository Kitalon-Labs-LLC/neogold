package com.kitalonlabs.neogold.engine.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The card rules of ADR-018. They run on the JVM because they use no OpenCV type; the OpenCV detector itself is
 * exercised on a device by OpenCvMarkerDetectorInstrumentedTest.
 */
class CardMarkerSelectionTest {

    private fun square(left: Double, top: Double = 0.0, side: Double = 10.0) = listOf(
        Point2D(left, top),
        Point2D(left + side, top),
        Point2D(left + side, top + side),
        Point2D(left, top + side)
    )

    private fun raw(id: Int, left: Double = 0.0, side: Double = 10.0) = RawMarker(id, square(left, side = side))

    @Test
    fun keepsOnlyTheFourCardIdsAndSortsThemById() {
        val found = listOf(raw(3), raw(7), raw(0), raw(49), raw(2), raw(1), raw(4))

        assertThat(selectCardMarkers(found).map { it.id }).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test
    fun anIdSeenTwiceIsDroppedNotGuessed() {
        val found = listOf(raw(0), raw(1, left = 0.0), raw(1, left = 500.0), raw(2), raw(3))

        assertThat(selectCardMarkers(found).map { it.id }).containsExactly(0, 2, 3).inOrder()
    }

    @Test
    fun aLargerForeignMarkerWithTheSameIdDoesNotDisplaceTheRealOne() {
        // Two cards in view, the neighbour's nearer and so bigger. The first version of the detector let the bigger
        // marker win, which would have read the wrong card.
        val real = raw(1, left = 0.0, side = 20.0)
        val foreign = raw(1, left = 300.0, side = 90.0)

        assertThat(selectCardMarkers(listOf(real, foreign))).isEmpty()
        assertThat(selectCardMarkers(listOf(foreign, real))).isEmpty()
    }

    @Test
    fun nothingFoundMeansNoMarkers() {
        assertThat(selectCardMarkers(emptyList())).isEmpty()
    }

    @Test
    fun aSelectedMarkerKeepsItsCornersAndGetsTheDiagonalCentre() {
        val corners = listOf(Point2D(0.0, 0.0), Point2D(4.0, 0.0), Point2D(3.0, 2.0), Point2D(1.0, 2.0))

        val marker = selectCardMarkers(listOf(RawMarker(2, corners))).single()

        assertThat(marker.corners).isEqualTo(corners)
        assertThat(marker.center).isEqualTo(diagonalCenter(corners))
    }

    @Test
    fun theCentreOfASquareIsItsMiddle() {
        val centre = diagonalCenter(square(left = 100.0, top = 40.0, side = 10.0))

        assertThat(centre.x).isWithin(1e-12).of(105.0)
        assertThat(centre.y).isWithin(1e-12).of(45.0)
    }

    @Test
    fun theCentreOfAMarkerSeenInPerspectiveIsWhereTheDiagonalsCross() {
        // A square seen at a slant is a trapezoid. Its projected centre is the crossing of the diagonals,
        // (2, 4/3); the mean of the four corners, (2, 1), is a third of a unit off.
        val trapezoid = listOf(Point2D(0.0, 0.0), Point2D(4.0, 0.0), Point2D(3.0, 2.0), Point2D(1.0, 2.0))

        val centre = diagonalCenter(trapezoid)

        assertThat(centre.x).isWithin(1e-12).of(2.0)
        assertThat(centre.y).isWithin(1e-12).of(4.0 / 3.0)
    }

    @Test
    fun parallelDiagonalsFallBackToTheMeanOfTheCorners() {
        val degenerate = listOf(Point2D(0.0, 0.0), Point2D(1.0, 0.0), Point2D(2.0, 0.0), Point2D(3.0, 0.0))

        val centre = diagonalCenter(degenerate)

        assertThat(centre.x).isWithin(1e-12).of(1.5)
        assertThat(centre.y).isWithin(1e-12).of(0.0)
    }
}
