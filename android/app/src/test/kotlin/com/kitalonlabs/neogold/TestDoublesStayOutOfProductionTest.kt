package com.kitalonlabs.neogold

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The key providers that keep a fixed key in the source tree exist for tests (TC-RSH-06, TC-LOG-11). If production
 * code could name one, a failing Keystore could be answered with a key everyone knows, which is how BUG-2026-011
 * happened. They sit in `src/main` only because several modules' tests share them, so this test is what keeps them
 * out of the app: no `src/main` file may name them, except the file that declares each one.
 */
class TestDoublesStayOutOfProductionTest {

    private val androidRoot: File by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    private val testDoubles = mapOf(
        "FakePinHmacKeyProvider" to "FakePinHmacKeyProvider.kt",
        "FakeMasterKeyProvider" to "FakeMasterKeyProvider.kt",
        "SoftwareDeviceKeyManager" to "DeviceKeyManager.kt"
    )

    private fun productionSources(): Sequence<File> = androidRoot.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" }
        .filter { it.isFile && it.extension == "kt" }
        .filter { file -> file.invariantSeparatorsPath.contains("/src/main/") }

    @Test
    fun noProductionFileNamesATestDouble_TC_RSH_06() {
        val sources = productionSources().toList()
        assertThat(sources).isNotEmpty()

        val offenders = mutableListOf<String>()
        for ((double, declaringFile) in testDoubles) {
            for (file in sources) {
                if (file.name != declaringFile && file.readText().contains(double)) {
                    offenders += "${file.relativeTo(androidRoot).invariantSeparatorsPath} names $double"
                }
            }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun theTestDoublesAreWhereThisTestExpectsThem() {
        val names = productionSources().map { it.name }.toSet()

        for (declaringFile in testDoubles.values) {
            assertThat(names).contains(declaringFile)
        }
    }
}
