package com.kitalonlabs.neogold.engine.triage

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class TriagePropertyTest {

    private fun loadConfig(relativePath: String): TriageConfig {
        val candidates = listOf(
            File(relativePath),
            File("../../../$relativePath"),
            File("../../$relativePath")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not find $relativePath")
        return TriageConfigParser.parse(file.readText())
    }

    @Test
    fun propertyTest_every_GA_maps_to_exactly_one_band() {
        val config = loadConfig("testdata/triage/config.synthetic.json")
        val validBands = setOf("ga35", "ga36", "ga38plus")

        for (ga in 35..44) {
            val band = config.bandForGestationalAge(ga)
            assertThat(band).isIn(validBands)

            when (ga) {
                35 -> assertThat(band).isEqualTo("ga35")
                36, 37 -> assertThat(band).isEqualTo("ga36")
                in 38..44 -> assertThat(band).isEqualTo("ga38plus")
            }
        }

        // Under 35 must throw
        for (underAge in 20..34) {
            var threw = false
            try {
                config.bandForGestationalAge(underAge)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertThat(threw).isTrue()
        }
    }

    @Test
    fun propertyTest_all_tables_cover_0_to_336_hours() {
        val synthetic = loadConfig("testdata/triage/config.synthetic.json")
        val clinical = loadConfig("config/triage-config.json")

        for (cfg in listOf(synthetic, clinical)) {
            for (bandName in listOf("ga35", "ga36", "ga38plus")) {
                val table = cfg.phototherapyThresholds[bandName]
                assertThat(table).isNotNull()
                assertThat(table!!.size).isAtLeast(2)

                // First point must be 0 h
                assertThat(table.first().first).isEqualTo(0.0)

                // Last point must be at least 336 h
                assertThat(table.last().first).isAtLeast(336.0)

                // Strictly ascending
                for (i in 0 until table.size - 1) {
                    assertThat(table[i + 1].first).isGreaterThan(table[i].first)
                }

                // Any age from 0 to 336 can be interpolated without throwing
                for (age in listOf(0.0, 1.0, 12.5, 24.0, 36.0, 48.0, 96.0, 150.2, 335.9, 336.0)) {
                    val t = TriageArithmetic.interpolateThresholdTenths(table, age)
                    assertThat(t).isAtLeast(0)
                }
            }
        }
    }

    @Test
    fun propertyTest_monotonic_thresholds_flagged() {
        val synthetic = loadConfig("testdata/triage/config.synthetic.json")
        assertThat(synthetic.nonMonotonicBands).isEmpty()

        val draftClinical = loadConfig("config/triage-config.json")
        assertThat(draftClinical.nonMonotonicBands).isEmpty()

        // Create a non-monotonic test config where threshold drops
        val nonMonotonicJson = """
        {
          "configVersion": "2026.10.0-test",
          "guideline": "TEST",
          "units": "mg/dL",
          "triageEnabled": true,
          "validUntil": "2027-12-31",
          "clinicalSignoff": { "status": "DRAFT", "signoffRecordSha256": null },
          "scope": { "minGestationalAgeWeeks": 35, "maxAgeHours": 336 },
          "hardRules": [
            { "id": "R1", "when": "dangerSigns == true", "action": "REFER_URGENT" },
            { "id": "R2", "when": "jaundiceOnsetUnder24h == true", "action": "REFER_URGENT" },
            { "id": "R3", "when": "gestationalAgeWeeks < 35", "action": "CANNOT_SCREEN_BLOOD_TEST" },
            { "id": "R4", "when": "priorPhototherapy == true", "action": "CANNOT_SCREEN_BLOOD_TEST" },
            { "id": "R5", "when": "ageHours > 336", "action": "CANNOT_SCREEN_CLINICAL_REVIEW" }
          ],
          "phototherapyThresholds": {
            "ga35": [[0, 10.0], [48, 8.0], [336, 8.0]],
            "ga36": [[0, 9.0], [48, 15.5], [336, 18.5]],
            "ga38plus": [[0, 10.0], [48, 17.0], [336, 20.0]]
          },
          "bloodTestZone": { "withinOfThreshold": 3.0, "absoluteAtOrAbove": 15.0 },
          "recheck": { "withinOfThreshold": 5.0, "hours": 24 },
          "maxIntervalWidth": 6.0,
          "qualityThresholds": {}
        }
        """.trimIndent()

        val parsed = TriageConfigParser.parse(nonMonotonicJson)
        // ga35 dropped from 10.0 to 8.0 -> must be flagged
        assertThat(parsed.nonMonotonicBands).containsExactly("ga35")
    }
}
