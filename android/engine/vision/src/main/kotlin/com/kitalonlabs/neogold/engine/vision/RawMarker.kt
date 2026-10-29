package com.kitalonlabs.neogold.engine.vision

/**
 * What a marker detector reported for one marker, before the card rules of [selectCardMarkers] are applied. Kept free
 * of OpenCV types so that those rules run in JVM unit tests (ADR-018).
 */
internal data class RawMarker(val id: Int, val corners: List<Point2D>)
