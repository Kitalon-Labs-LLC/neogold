package com.kitalonlabs.neogold

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * R07 must write a real export (BUG-2026-025, TC-EXP-01): the file picker's result goes to `ResearchExportManager`, the
 * progress comes from it, the file name follows the spec, and no made-up progress is left in the app. A guard that needs no
 * screen drawn, like `ScreeningWiringGuardTest`.
 */
class ExportWiringGuardTest {

    private val appSources: File by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        File(dir, "app/src/main/kotlin")
    }

    private fun productionText(): String = appSources.walkTopDown().filter {
        it.isFile && it.extension == "kt"
    }.joinToString("\n") { it.readText() }

    @Test
    fun tcExp01_theExportRouteCallsTheRealExportAndNoMadeUpProgressIsLeft_BUG_2026_025() {
        val text = productionText()

        assertThat(text).contains("researchExport.exportToUri(")
        assertThat(text).contains("exportFileName(")
        assertThat(text).doesNotContain("Preparing export package")
        assertThat(text).doesNotContain("totalFiles = 10")
    }

    @Test
    fun tcExp01_theScreenNeverShowsTheManagersOwnMessageText() {
        val repoRoot = appSources.parentFile.parentFile.parentFile.parentFile // app/src/main/kotlin -> android/
        val screen = File(repoRoot, "feature/research/src/main/kotlin")
            .walkTopDown().first { it.name == "R07ExportScreen.kt" }.readText()

        // an exception message can carry a path; the screen writes its own text from the stage (BUG-2026-083 item 25)
        assertThat(screen).doesNotContain("exportProgress.message")
        assertThat(screen).doesNotContain(".message}")
    }

    /**
     * BUG-083 item 30: the picker is owned by the activity, so a result that arrives after the screen is gone (a lock sent
     * the worker back to R01) is deleted instead of leaving an empty document on the drive.
     */
    @Test
    fun tcExp01_theSavePickerIsOwnedByTheActivityAndAnOrphanedResultIsDiscarded_BUG_083() {
        val text = productionText()

        assertThat(text).contains("registerForActivityResult(")
        assertThat(text).contains("ActivityResultContracts.CreateDocument(")
        assertThat(text).doesNotContain("rememberLauncherForActivityResult")
        assertThat(text).contains("pendingExportPick.deliver(")
        assertThat(text).contains("ExportDestination.forUri(contentResolver, uri).discard()")
        assertThat(text).contains("exportPicker.pending.cancel()")
        assertThat(text).contains("exportPicker.pending.reject(uri)")
    }
}
