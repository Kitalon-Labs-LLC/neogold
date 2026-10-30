package com.kitalonlabs.neogold

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The study server side of research mode must be wired for real (BUG-2026-026, BUG-2026-027, BUG-2026-083 item 33): R07 to
 * R09 are not placeholders, uploads are started by the app, the upload worker builds nothing of its own, retention asks the
 * server before it purges, and no server text reaches a screen. Source guards, like `ExportWiringGuardTest`.
 */
class StudyServerWiringGuardTest {

    private val androidRoot: File by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    private fun sources(module: String): String = File(androidRoot, "$module/src/main/kotlin")
        .walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }

    private fun file(path: String): String = File(androidRoot, path).readText()

    @Test
    fun tcSync10_theCloudCardIsShownAndR08AndR09AreTheRealScreens() {
        val app = sources("app")

        assertThat(app).doesNotContain("PlaceholderScreen")
        assertThat(app).doesNotContain("showCloudSync = false")
        assertThat(app).contains("R08EnrollmentScreen(")
        assertThat(app).contains("R09LeaveStudyScreen(")
        assertThat(app).contains("onUploadNowClicked = { server?.uploadNow() }")
    }

    @Test
    fun tcSync10_uploadsAreStartedAfterASaveAfterEnrollmentAndAtAppStart_BUG_027() {
        val container = file("app/src/main/kotlin/com/kitalonlabs/neogold/NeoGoldContainer.kt")
        val application = file("app/src/main/kotlin/com/kitalonlabs/neogold/NeoGoldApplication.kt")
        val viewModel =
            file("feature/research/src/main/kotlin/com/kitalonlabs/neogold/feature/research/ui/StudyServerViewModel.kt")

        // after a save: the research view model tells the app
        assertThat(container).contains("onDataQueued = { studyServer(database)?.scheduleUploads() }")
        // after enrollment
        assertThat(viewModel).contains("server.scheduleUploads()")
        // at app start, once storage is open
        assertThat(application).contains("container.scheduleUploads()")
    }

    @Test
    fun tcSync11_theUploadWorkerBuildsNoDatabaseNoKeysAndNoHttpClientOfItsOwn() {
        val worker =
            file("sync/research/src/main/kotlin/com/kitalonlabs/neogold/sync/research/worker/ResearchUploadWorker.kt")

        assertThat(worker).contains("ResearchSyncComponent")
        assertThat(worker).doesNotContain("buildDatabase")
        assertThat(worker).doesNotContain("AndroidMasterKeyProvider")
        assertThat(worker).doesNotContain("DefaultDatabaseKeyProvider")
        assertThat(worker).doesNotContain("OkHttpClient")
        assertThat(worker).doesNotContain("AndroidDeviceKeyManager")
        // only the kind of an exception is logged, never its message
        assertThat(worker).doesNotContain(".message}")
        assertThat(worker).doesNotContain("Timber.w(e,")
        assertThat(worker).doesNotContain("Timber.e(e,")
    }

    @Test
    fun tcSync13_retentionAsksTheServerBeforeItPurgesResearchData() {
        val container = file("app/src/main/kotlin/com/kitalonlabs/neogold/NeoGoldContainer.kt")

        assertThat(container).contains("serverConfirmationHook = syncGraph(database)?.retentionHook")
        // with no graph the safe default confirms nothing
        assertThat(container).contains("NoOpResearchRetentionServerConfirmationHook()")
    }

    @Test
    fun tcSync_onlyTheSyncModuleBuildsTheHttpClient_andTheAppAndFeatureNeverSeeItsTypes() {
        val app = sources("app")
        val feature = sources("feature/research")

        for (text in listOf(app, feature)) {
            assertThat(text).doesNotContain("import okhttp3")
            assertThat(text).doesNotContain("ApiException")
        }
        assertThat(
            sources("sync/research")
        ).contains("OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)")
    }

    @Test
    fun tcSync_noServerTextReachesAScreenOrALog() {
        val screens = listOf("R07ExportScreen", "R08EnrollmentScreen", "R09LeaveStudyScreen").map { name ->
            File(
                androidRoot,
                "feature/research/src/main/kotlin"
            ).walkTopDown().first { it.name == "$name.kt" }.readText()
        }
        val sync = file("app/src/main/kotlin/com/kitalonlabs/neogold/StudyServerSync.kt")

        for (screen in screens) {
            assertThat(screen).doesNotContain(".message")
            assertThat(screen).doesNotContain("StudyServerFailure")
        }
        // StudyServerSync logs the kind and the exception's class name, never the exception or its message
        assertThat(sync).doesNotContain("Timber.w(e")
        assertThat(sync).doesNotContain("e.message")
    }

    @Test
    fun tcApi07_theAppsPhotoLimitIsTheOneEnrollmentChecksTheServerAgainst() {
        val processor = file(
            "feature/research/src/main/kotlin/com/kitalonlabs/neogold/feature/research/capture/ResearchCaptureProcessor.kt"
        )
        val enrollment = file(
            "sync/research/src/main/kotlin/com/kitalonlabs/neogold/sync/research/enrollment/StudyEnrollmentManager.kt"
        )

        assertThat(processor).contains("const val MAX_IMAGE_BYTES = 5_000_000")
        assertThat(enrollment).contains("const val APP_MAX_IMAGE_BYTES = 5_000_000L")
    }

    @Test
    fun tcSync_theDeviceFactsSentToTheServerComeFromTheDeviceProfileAndNeverFromADefault() {
        val enrollment = file(
            "sync/research/src/main/kotlin/com/kitalonlabs/neogold/sync/research/enrollment/StudyEnrollmentManager.kt"
        )
        val sync = file("app/src/main/kotlin/com/kitalonlabs/neogold/StudyServerSync.kt")

        assertThat(enrollment).doesNotContain("\"LIMITED\"")
        assertThat(enrollment).doesNotContain("\"QUALIFIED\"")
        assertThat(sync).contains("profile.cameraHardwareLevel")
        assertThat(sync).contains("profile.qualification")
    }
}
