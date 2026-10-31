@file:Suppress("MagicNumber") // synthetic-card test matrix: angles in degrees, scales, pixel tolerances and sizes

package com.kitalonlabs.neogold.engine.vision

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.kitalonlabs.neogold.engine.colorscience.ciede2000
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OpenCV's ArUco detector on a device or an emulator, where its native library can load (ADR-018); the JVM unit
 * tests cannot do that. Frames come from [SyntheticCardRenderer] with the ARUCO_4X4 artwork the card is printed with.
 *
 * They show that the integration works: the library loads, the IDs and the corner order match the card spec, the
 * corners and centres sit where the card puts them, the pipeline reads the patches within 0.5 Delta E 2000 (doc 10,
 * the card detection and patch extraction case), a missing marker is reported as missing (doc 10, the missing card
 * case), and two cards in view are not taken for one. They do not show how detection copes with a printed card in a
 * clinic, or how fast it runs on a phone: that needs bench photos and the device matrix (package D1).
 *
 * CI does not run this class. It needs an emulator or a phone (CLAUDE.md, Commands). It deliberately names no test
 * case ID: tools/trace.py counts any ID in a test file as coverage, and CI cannot vouch for these tests.
 */
@RunWith(AndroidJUnit4::class)
class OpenCvMarkerDetectorInstrumentedTest {

    private val renderer = SyntheticCardRenderer()

    private fun aruco(scale: Double, tilt: Double = 0.0, rotation: Double = 0.0) = SyntheticRenderConfig(
        scale = scale,
        tiltDegrees = tilt,
        rotationDegrees = rotation,
        markerFamily = "ARUCO_4X4"
    )

    @Test
    fun theNativeLibraryLoadsOnThisDevice() {
        assertThat(OpenCvMarkerDetector.isAvailable()).isTrue()
    }

    @Test
    fun theDefaultDetectorUsesOpenCvHereAndAgreesWithIt() {
        val frame = renderer.renderFrame(aruco(0.8))
        val default = DefaultMarkerDetector()

        assertThat(default.usesFallback).isFalse()
        assertThat(default.detectMarkers(frame)).isEqualTo(OpenCvMarkerDetector().detectMarkers(frame))
    }

    @Test
    fun findsAllFourMarkersWithTheirCornersAndCentresAtEveryRotationScaleAndTilt() {
        val detector = OpenCvMarkerDetector()
        var worstCornerErrorPx = 0.0
        var worstCenterErrorPx = 0.0
        for (scale in SCALES) {
            for (tilt in TILTS) {
                for (rotation in ROTATIONS) {
                    val config = aruco(scale, tilt, rotation)
                    val label = "scale=$scale tilt=$tilt rotation=$rotation"
                    val found = detector.detectMarkers(renderer.renderFrame(config)).associateBy { it.id }
                    val trueCorners = renderer.markerCornersInFrame(config)
                    val trueCenters = renderer.markerCentersInFrame(config)

                    assertWithMessage(label).that(found.keys).containsExactly(0, 1, 2, 3)
                    assertWithMessage(label).that(trueCorners).hasSize(4)
                    for ((id, expected) in trueCorners) {
                        val marker = found.getValue(id)
                        for (i in expected.indices) {
                            val error = marker.corners[i].distanceTo(expected[i])
                            worstCornerErrorPx = StrictMath.max(worstCornerErrorPx, error)
                            assertWithMessage("$label marker $id corner $i").that(error).isAtMost(TOLERANCE_PX)
                        }
                        val centerError = marker.center.distanceTo(trueCenters.getValue(id))
                        worstCenterErrorPx = StrictMath.max(worstCenterErrorPx, centerError)
                        assertWithMessage("$label marker $id centre").that(centerError).isAtMost(TOLERANCE_PX)
                    }
                }
            }
        }
        Log.i(TAG, "worst error over ${SCALES.size * TILTS.size * ROTATIONS.size} frames: corner " +
            "$worstCornerErrorPx px, centre $worstCenterErrorPx px")
    }

    @Test
    fun theQualityGatePassesAndThePatchesAreReadWithinHalfADeltaE() {
        val engine = VisionEngine(markerDetector = OpenCvMarkerDetector())
        // The six frames of the JVM case, plus the card at the size the card spec gives it in a photo (43 to 50 %
        // of the frame width), straight and turned.
        val configs = listOf(
            aruco(0.85),
            aruco(1.00, tilt = 5.0),
            aruco(1.15),
            aruco(1.00, rotation = 90.0),
            aruco(1.00, rotation = 180.0),
            aruco(1.00, rotation = 270.0),
            aruco(0.60),
            aruco(0.70, rotation = 30.0),
            aruco(0.60, tilt = 15.0, rotation = 200.0)
        )
        for (cfg in configs) {
            val label = "scale=${cfg.scale} tilt=${cfg.tiltDegrees} rotation=${cfg.rotationDegrees}"
            val result = engine.processFrame(renderer.renderFrame(cfg))

            assertWithMessage("$label: ${result.failureReasons}").that(result.passed).isTrue()
            assertThat(result.metrics.markersDetected).isEqualTo(4)
            val patches = CardSampler.samplePatches(result.canonicalImage!!)
            for ((id, def) in CardGeometry.PATCHES) {
                val deltaE = ciede2000(patches.getValue(id).lab, def.nominalLab)
                assertWithMessage("patch $id at $label: $deltaE").that(deltaE).isAtMost(0.5)
            }
        }
    }

    // A covered or missing marker is reported as missing, never invented.
    @Test
    fun aMissingMarkerIsReportedAsMissing() {
        val detector = OpenCvMarkerDetector()
        val engine = VisionEngine(markerDetector = detector)
        for (omitted in 0..3) {
            val frame = renderer.renderFrame(aruco(0.8).copy(omitMarkerId = omitted))

            assertThat(detector.detectMarkers(frame).map { it.id })
                .containsExactlyElementsIn((0..3).filter { it != omitted })
            val result = engine.processFrame(frame)
            assertThat(result.passed).isFalse()
            assertThat(result.failureReasons).contains(QualityReasonCode.NO_CARD)
        }
    }

    // A spare card on the cot rail: every ID turns up twice, so none of them may be trusted.
    @Test
    fun twoCardsInViewAreNotTakenForOne() {
        val frame = twoCardsInView()
        val detector = OpenCvMarkerDetector()

        assertThat(detector.detectMarkers(frame)).isEmpty()
        val result = VisionEngine(markerDetector = detector).processFrame(frame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.NO_CARD)
    }

    @Test
    fun aFrameWithoutACardHasNoMarkers() {
        val blank = ImageFrame(1600, 1200, IntArray(1600 * 1200) { 0xFFDCDCDC.toInt() })

        assertThat(OpenCvMarkerDetector().detectMarkers(blank)).isEmpty()
        val result = VisionEngine(markerDetector = OpenCvMarkerDetector()).processFrame(blank)
        assertThat(result.failureReasons).contains(QualityReasonCode.NO_CARD)
    }

    @Test
    fun tinyAndOddlyShapedFramesGiveNoMarkersAndNoException() {
        val detector = OpenCvMarkerDetector()
        for ((width, height) in listOf(1 to 1, 3 to 3, 64 to 8, 8 to 64, 1 to 1000, 1200 to 1600)) {
            val frame = ImageFrame(width, height, IntArray(width * height) { 0xFFDCDCDC.toInt() })

            assertWithMessage("${width}x$height").that(detector.detectMarkers(frame)).isEmpty()
        }
    }

    @Test
    fun aPortraitFrameFindsTheMarkers() {
        val config = aruco(0.6, rotation = 90.0)
        val frame = renderer.renderFrame(config, frameWidth = 1200, frameHeight = 1600)

        val found = OpenCvMarkerDetector().detectMarkers(frame).associateBy { it.id }

        assertThat(found.keys).containsExactly(0, 1, 2, 3)
        for ((id, truth) in renderer.markerCentersInFrame(config, frameWidth = 1200, frameHeight = 1600)) {
            assertThat(found.getValue(id).center.distanceTo(truth)).isAtMost(TOLERANCE_PX)
        }
    }

    // Recorded for the log, and only sanity-checked: an emulator's speed says nothing about a phone's (doc 4 Section 5.6).
    @Test
    fun detectionTimeIsRecordedAndNotAbsurd() {
        val detector = OpenCvMarkerDetector()
        val frame = renderer.renderFrame(aruco(0.8))
        detector.detectMarkers(frame) // warm up
        val millis = List(TIMING_RUNS) {
            val start = System.nanoTime()
            detector.detectMarkers(frame)
            (System.nanoTime() - start) / 1e6
        }.sorted()
        val median = millis[millis.size / 2]

        Log.i(TAG, "detectMarkers on 1600x1200: median $median ms, min ${millis.first()}, max ${millis.last()}")
        assertThat(median).isLessThan(SANITY_LIMIT_MS)
    }

    /** The card of [aruco] twice in one frame, side by side, on the same background. */
    private fun twoCardsInView(): ImageFrame {
        val single = renderer.renderFrame(aruco(0.4))
        val width = single.width
        val background = single.data[0]
        val data = IntArray(single.data.size) { background }
        // At scale 0.4 the 1200 x 760 card covers 480 x 304 px around the centre of the frame; copy a little more.
        val left = width / 2 - 244
        val top = single.height / 2 - 156
        for (shift in listOf(-500, 500)) {
            for (y in 0 until 312) {
                for (x in 0 until 488) {
                    data[(top + y) * width + left + x + shift] = single.data[(top + y) * width + left + x]
                }
            }
        }
        return ImageFrame(width, single.height, data)
    }

    private companion object {
        const val TAG = "NeoGoldVision"
        const val TOLERANCE_PX = 1.25 // the worst error measured with sub-pixel refinement is 0.8 px; 1.5 px without it
        const val TIMING_RUNS = 20
        const val SANITY_LIMIT_MS = 500.0
        val SCALES = listOf(0.5, 0.65, 0.8)
        val TILTS = listOf(0.0, 10.0, 20.0) // the quality gate rejects a card tilted more than 20 degrees
        val ROTATIONS = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
    }
}
