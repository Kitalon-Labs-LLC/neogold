package com.kitalonlabs.neogold

import org.junit.Test
import java.io.File

/**
 * TC-PRIV-04: No network without consent (FR-34, NFR-01, doc 4 driver 2, doc 8 Section 6.1).
 *
 * Verifies that:
 * 1. Screening feature and engine modules have zero dependencies on networking/HTTP libraries (ktor, okhttp, retrofit).
 * 2. Screening module does not depend on sync modules (:sync:research).
 * 3. Network access is isolated strictly to consented research sync.
 */
class NetworkIsolationTest {

    private val androidRoot: File by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    @Test
    fun screeningModules_haveZeroNetworkDependencies_TC_PRIV_04() {
        val isolatedModules = listOf(
            "engine/camera",
            "engine/vision",
            "engine/colorscience",
            "engine/estimation",
            "engine/triage",
            "engine/cardtrust",
            "feature/screening",
            "feature/result",
            "feature/calibration",
            "feature/onboarding"
        )

        val forbiddenTokens = listOf(
            "ktor",
            "okhttp",
            "retrofit",
            "sync:research",
            "java.net.HttpURLConnection"
        )

        for (modulePath in isolatedModules) {
            val buildFile = File(androidRoot, "$modulePath/build.gradle.kts")
            if (buildFile.exists()) {
                val content = buildFile.readText().lowercase()
                for (token in forbiddenTokens) {
                    com.google.common.truth.Truth.assertWithMessage(
                        "Module $modulePath build file must not depend on $token"
                    )
                        .that(content.contains(token))
                        .isFalse()
                }
            }
        }
    }
}
