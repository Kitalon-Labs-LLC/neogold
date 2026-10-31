package com.kitalonlabs.neogold

import android.Manifest
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.camera.host.CameraHost
import com.kitalonlabs.neogold.engine.camera.host.CameraMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The camera host on a real camera or the emulator's virtual one (TC-CAP-15, BUG-2026-032, C1). The JVM tests cover the
 * logic; this shows that a photo really arrives with the camera's own record of how it was taken, through the Camera2
 * interop callback and not through CameraX internals. Run by hand with the debug test APK.
 */
@RunWith(AndroidJUnit4::class)
class CameraHostInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun grantCamera() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    }

    private fun <T> onActivity(block: suspend (LifecycleOwner) -> T): T {
        grantCamera()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var owner: LifecycleOwner? = null
            scenario.onActivity { owner = it }
            return runBlocking { withContext(Dispatchers.Main) { block(owner!!) } }
        }
    }

    @Test
    fun aPhotoCarriesTheCamerasOwnRecordOfHowItWasTaken_TC_CAP_15() = onActivity { owner ->
        val session = CameraHost(context).open(owner, CameraMode.Stills)
        try {
            val captured = session.captureFrame()

            assertThat(captured.frame.width).isGreaterThan(0)
            assertThat(captured.frame.height).isGreaterThan(0)
            // Taken from the capture result, not from the fallback that says nothing is known.
            assertThat(captured.metadata.sensorSensitivity).isNotNull()
            assertThat(captured.metadata.exposureTimeNs).isNotNull()
            assertThat(session.characteristics).isNotNull()
        } finally {
            session.close()
        }
    }

    @Test
    fun aBurstGivesTwoOrThreePhotosAndRecordsEach_TC_CAP_15() = onActivity { owner ->
        val session = CameraHost(context).open(owner, CameraMode.Stills)
        try {
            val burst = session.captureBurst()

            assertThat(burst.frames.size).isIn(2..3)
            assertThat(burst.metadata.map { it.sensorSensitivity }).doesNotContain(null)
        } finally {
            session.close()
        }
    }
}
