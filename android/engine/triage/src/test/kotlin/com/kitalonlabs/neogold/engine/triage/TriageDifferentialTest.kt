package com.kitalonlabs.neogold.engine.triage

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.Random

class TriageDifferentialTest {

    @Suppress("ConstructorParameterNaming") // the names are the JSON keys of the differential vectors
    data class TestCase(
        val name: String,
        val danger: Boolean = false,
        val onset24: Boolean = false,
        val ga: Int = 40,
        val photo: Boolean = false,
        val age_h: Double = 48.0,
        val quality_failed_3: Boolean = false,
        val E: Double? = null,
        val L: Double? = null,
        val U: Double? = null
    )

    private fun findPython(): List<String> {
        val candidates = listOf(
            listOf("py", "-3.13"),
            listOf("py", "-3"),
            listOf("python3"),
            listOf("python")
        )
        for (cmd in candidates) {
            try {
                val p = ProcessBuilder(cmd + listOf("-c", "import sys; sys.exit(0)")).start()
                if (p.waitFor() == 0) {
                    return cmd
                }
            } catch (e: Exception) {
                // try next
            }
        }
        error("No suitable Python executable found for differential test")
    }

    private fun findFile(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("../../../$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not find file '$relative'")
    }

    @Test
    fun test_TC_TRI_24_differential_against_python_reference() {
        val configFile = findFile("testdata/triage/config.synthetic.json")
        val config = TriageConfigParser.parse(configFile.readText())
        val engine = TriageEngine(config = config)

        val testCases = mutableListOf<TestCase>()

        // 1. All boundary cases from doc 10 Section 6
        testCases.add(TestCase("TRI-01 danger signs", danger = true, E = 5.0, L = 3.0, U = 7.0))
        testCases.add(TestCase("TRI-02 onset under 24h", onset24 = true, E = 5.0, L = 3.0, U = 7.0))
        testCases.add(TestCase("TRI-03a GA 34", ga = 34, E = 5.0, L = 3.0, U = 7.0))
        testCases.add(TestCase("TRI-03b GA 35", ga = 35, age_h = 48.0, E = 5.0, L = 3.0, U = 7.0))
        testCases.add(TestCase("TRI-04 phototherapy", photo = true, E = 5.0, L = 3.0, U = 7.0))
        testCases.add(TestCase("TRI-05a age 336.1", age_h = 336.1, E = 5.0, L = 3.0, U = 7.0))
        testCases.add(TestCase("TRI-05b age 336.0", age_h = 336.0, E = 5.0, L = 3.0, U = 7.0))
        testCases.add(TestCase("TRI-06 danger + GA 34", danger = true, ga = 34, E = 5.0, L = 3.0, U = 7.0))
        testCases.add(TestCase("TRI-07 E = T", age_h = 48.0, E = 17.0, L = 15.0, U = 19.0))
        testCases.add(TestCase("TRI-08 E = T-0.1", age_h = 48.0, E = 16.9, L = 14.9, U = 18.9))
        testCases.add(TestCase("TRI-09a U = T-3 (14.0)", age_h = 48.0, E = 12.0, L = 10.0, U = 14.0))
        testCases.add(TestCase("TRI-09b U = T-3.1 (13.9)", age_h = 48.0, E = 11.9, L = 9.9, U = 13.9))
        testCases.add(TestCase("TRI-10a U = 15.0 at 96h", age_h = 96.0, E = 12.0, L = 10.0, U = 15.0))
        testCases.add(TestCase("TRI-10b U = 14.9 at 96h", age_h = 96.0, E = 11.9, L = 9.9, U = 14.9))
        testCases.add(TestCase("TRI-11a width 6.1", age_h = 48.0, E = 6.0, L = 3.0, U = 9.1))
        testCases.add(TestCase("TRI-11b width 6.0", age_h = 48.0, E = 6.0, L = 3.0, U = 9.0))
        testCases.add(TestCase("TRI-12 U = T-5 (12.0)", age_h = 48.0, E = 10.0, L = 8.0, U = 12.0))
        testCases.add(TestCase("TRI-13 U = 11.9", age_h = 48.0, E = 10.0, L = 8.1, U = 11.9))
        testCases.add(TestCase("TRI-14a 36h T=14.5 E=14.5", age_h = 36.0, E = 14.5, L = 12.5, U = 16.5))
        testCases.add(TestCase("TRI-14b 36h E=14.4", age_h = 36.0, E = 14.4, L = 12.4, U = 16.4))
        testCases.add(TestCase("TRI-15 quality fallback", quality_failed_3 = true, E = 5.0, L = 3.0, U = 7.0))
        testCases.add(TestCase("TRI-16a GA 37 48h E=15.5", ga = 37, age_h = 48.0, E = 15.5, L = 13.5, U = 17.5))
        testCases.add(TestCase("TRI-16b GA 38 48h E=15.5", ga = 38, age_h = 48.0, E = 15.5, L = 13.5, U = 17.5))
        testCases.add(TestCase("TRI-23 49.6h T=17.1 U=14.1", age_h = 49.6, ga = 40, E = 12.0, L = 10.0, U = 14.1))
        testCases.add(TestCase("TRI-25a 48h E=16.95", age_h = 48.0, E = 16.95, L = 14.0, U = 18.0))

        // 2. 10,000 generated cases with fixed seed
        val random = Random(20261017L)
        for (i in 1..10000) {
            val danger = random.nextInt(100) < 5
            val onset24 = random.nextInt(100) < 5
            val ga = 34 + random.nextInt(11) // 34 to 44
            val photo = random.nextInt(100) < 5
            val ageH = Math.round(random.nextDouble() * 340.0 * 10.0) / 10.0 // 0.0 to 340.0 in tenths
            val qFail = random.nextInt(100) < 5
            val eVal = Math.round((1.0 + random.nextDouble() * 24.0) * 100.0) / 100.0 // 1.0 to 25.0
            val spanBelow = Math.round((0.5 + random.nextDouble() * 3.5) * 10.0) / 10.0
            val spanAbove = Math.round((0.5 + random.nextDouble() * 3.5) * 10.0) / 10.0
            val lVal = Math.max(0.0, Math.round((eVal - spanBelow) * 100.0) / 100.0)
            val uVal = Math.round((eVal + spanAbove) * 100.0) / 100.0

            testCases.add(
                TestCase(
                    name = "GEN-$i",
                    danger = danger,
                    onset24 = onset24,
                    ga = ga,
                    photo = photo,
                    age_h = ageH,
                    quality_failed_3 = qFail,
                    E = eVal,
                    L = lVal,
                    U = uVal
                )
            )
        }

        // Build JSON payload for Python reference batch execution
        val jsonPayload = buildString {
            append("[")
            testCases.forEachIndexed { idx, tc ->
                if (idx > 0) append(",")
                append("{")
                append("\"danger\":${tc.danger},")
                append("\"onset24\":${tc.onset24},")
                append("\"ga\":${tc.ga},")
                append("\"photo\":${tc.photo},")
                append("\"age_h\":${tc.age_h},")
                append("\"quality_failed_3\":${tc.quality_failed_3},")
                append("\"E\":${tc.E},")
                append("\"L\":${tc.L},")
                append("\"U\":${tc.U}")
                append("}")
            }
            append("]")
        }

        // Run Python reference implementation
        val pythonCmd = findPython()
        val refScript = findFile("tools/triage_reference.py")
        val pb = ProcessBuilder(pythonCmd + listOf(refScript.absolutePath, "--batch", configFile.absolutePath))
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        val proc = pb.start()

        proc.outputStream.bufferedWriter().use { it.write(jsonPayload) }
        val pyOutput = proc.inputStream.bufferedReader().use { it.readText() }
        val exitCode = proc.waitFor()
        assertThat(exitCode).isEqualTo(0)

        val pyResults = TriageConfigParser.JsonParser.parse(pyOutput) as List<*>
        assertThat(pyResults.size).isEqualTo(testCases.size)

        var disagreements = 0
        val discrepancyLog = StringBuilder()

        for (i in testCases.indices) {
            val tc = testCases[i]
            val pyPair = pyResults[i] as List<*>
            val expectedAction = pyPair[0] as String
            val expectedReason = pyPair[1] as String

            val actualRes = engine.evaluate(
                TriageInputs(
                    dangerSigns = tc.danger,
                    jaundiceOnsetUnder24h = tc.onset24,
                    gestationalAgeWeeks = tc.ga,
                    priorPhototherapy = tc.photo,
                    ageHours = tc.age_h,
                    qualityFailedAttempts = if (tc.quality_failed_3) 3 else 0,
                    estimate = tc.E,
                    lowerBound = tc.L,
                    upperBound = tc.U
                )
            )

            val actualAction: String
            val actualReason: String
            when (actualRes) {
                is TriageResult.Action -> {
                    actualAction = actualRes.action.name
                    actualReason = actualRes.reasonCode
                }
                is TriageResult.Unavailable -> {
                    actualAction = "UNAVAILABLE"
                    actualReason = actualRes.reason.name
                }
            }

            if (actualAction != expectedAction || actualReason != expectedReason) {
                disagreements++
                if (disagreements <= 10) {
                    discrepancyLog.appendLine(
                        "Disagreement on case $i (${tc.name}): inputs(ga=${tc.ga}, age=${tc.age_h}, E=${tc.E}, L=${tc.L}, U=${tc.U}) " +
                            "-> Kotlin=($actualAction, $actualReason) vs Python=($expectedAction, $expectedReason)"
                    )
                }
            }
        }

        if (disagreements > 0) {
            System.err.println("Total disagreements: $disagreements out of ${testCases.size}")
            System.err.println(discrepancyLog.toString())
        }

        assertThat(disagreements).isEqualTo(0)
    }
}
