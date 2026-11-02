package com.kitalonlabs.neogold

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * P28 / P25 guardrail check: proves that [com.kitalonlabs.neogold.engine.camera.debug.DebugBenchFeeder]
 * lives exclusively in the `debug` source set and is never referenced from any `src/main` or `src/release` file.
 */
class DebugBenchFeederAbsentFromMainTest {

    private val androidRoot: File by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    private fun productionSources(): Sequence<File> = androidRoot.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" }
        .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
        .filter { file ->
            val path = file.invariantSeparatorsPath
            path.contains("/src/main/") || path.contains("/src/release/")
        }

    @Test
    fun debugBenchFeederExistsOnlyInDebugSourceSet() {
        val debugFile = File(
            androidRoot,
            "engine/camera/src/debug/kotlin/com/kitalonlabs/neogold/engine/camera/debug/DebugBenchFeeder.kt"
        )
        assertThat(debugFile.exists()).isTrue()

        val offenders = mutableListOf<String>()
        for (file in productionSources()) {
            val content = file.readText()
            if (content.contains("DebugBenchFeeder") || content.contains("neogold.debug.bench.scenario")) {
                offenders += file.relativeTo(androidRoot).invariantSeparatorsPath
            }
        }

        assertThat(offenders).isEmpty()
    }
}
