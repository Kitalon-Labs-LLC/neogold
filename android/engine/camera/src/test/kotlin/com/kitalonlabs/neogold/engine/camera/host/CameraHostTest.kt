package com.kitalonlabs.neogold.engine.camera.host

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.camera.BurstCaptureController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * The camera session's logic without a camera (TC-CAP-15, TC-PRIV-01, BUG-2026-032): pairing a photo with its capture
 * result, and a burst built from scripted photos. The real camera path is covered by the emulator test in :app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraHostTest {

    // --- CaptureResultRegistry ---

    @Test
    fun aResultRecordedBeforeTheAskIsHandedOverOnce() = runTest {
        val registry = CaptureResultRegistry<String>()
        registry.record(100L, "first")

        assertThat(registry.await(100L, timeoutMs = 50)).isEqualTo("first")
        assertThat(registry.await(100L, timeoutMs = 20)).isNull() // handed over once, not kept
    }

    @Test
    fun anAskThatComesBeforeTheResultWaitsForIt() = runTest {
        val registry = CaptureResultRegistry<String>()

        val answer = withContext(Dispatchers.Default) {
            val asked = async { registry.await(7L, timeoutMs = 5_000) }
            Thread.sleep(100) // the ask is waiting by now
            registry.record(7L, "late")
            asked.await()
        }

        assertThat(answer).isEqualTo("late")
    }

    @Test
    fun aResultThatNeverComesGivesNullAfterTheTimeout() = runTest {
        val registry = CaptureResultRegistry<String>()

        val answer = withContext(Dispatchers.Default) { registry.await(5L, timeoutMs = 30) }

        assertThat(answer).isNull()
    }

    @Test
    fun unclaimedResultsAreCappedSoTheyCannotPileUp() = runTest {
        val registry = CaptureResultRegistry<String>(capacity = 2)
        registry.record(1L, "a")
        registry.record(2L, "b")
        registry.record(3L, "c")

        assertThat(registry.await(1L, timeoutMs = 20)).isNull() // the oldest was dropped
        assertThat(registry.await(2L, timeoutMs = 20)).isEqualTo("b")
        assertThat(registry.await(3L, timeoutMs = 20)).isEqualTo("c")
    }

    // --- BurstCaptureController with scripted photos ---

    private fun jpeg(sharp: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.GRAY)
        if (sharp) {
            val paint = Paint().apply { color = Color.BLACK }
            for (x in 0 until 320 step 20) for (y in 0 until 240 step 20) {
                if ((x / 20 + y / 20) % 2 == 0) canvas.drawRect(x.toFloat(), y.toFloat(), x + 20f, y + 20f, paint)
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private class ScriptedStills(private val photos: List<ByteArray>) : StillSource {
        var taken = 0
        override suspend fun takeStill(): Still = Still(
            photos[taken],
            sensorTimestampNs = 1_000L + taken
        ).also { taken++ }
    }

    @Test
    fun aBurstTakesThreePhotosAndKeepsAMetadataRecordForEach_TC_CAP_15() = runTest {
        val source = ScriptedStills(listOf(jpeg(false), jpeg(true), jpeg(false)))

        val burst = BurstCaptureController().executeBurst(source)

        assertThat(burst.frames).hasSize(3)
        assertThat(burst.metadata).hasSize(3)
        // Which one is sharpest is the Laplacian estimator's job (testSharpestFrameSelectedViaLaplacianVariance); the
        // unit-test bitmaps do not draw real pixels, so this only checks that the choice is one of the photos taken.
        assertThat(burst.sharpestIndex).isIn(0..2)
        assertThat(source.taken).isEqualTo(3)
    }

    @Test
    fun aLowMemoryPhoneTakesTwo_TC_CAP_15() = runTest {
        val source = ScriptedStills(listOf(jpeg(true), jpeg(false), jpeg(false)))

        val burst = BurstCaptureController(isLowRamDevice = true).executeBurst(source)

        assertThat(burst.frames).hasSize(2)
        assertThat(source.taken).isEqualTo(2)
    }

    @Test
    fun withoutACaptureResultThePhotoSaysWhatIsNotKnown_TC_CAP_15() = runTest {
        val captured = BurstCaptureController().captureFrame(ScriptedStills(listOf(jpeg(true))))

        val metadata = captured.metadata
        assertThat(metadata.sensorSensitivity).isNull()
        assertThat(metadata.exposureTimeNs).isNull()
        assertThat(metadata.aeState).isNull()
        // An unknown is not recorded as "locked".
        assertThat(metadata.aeLocked).isFalse()
        assertThat(metadata.awbLocked).isFalse()
    }
}
