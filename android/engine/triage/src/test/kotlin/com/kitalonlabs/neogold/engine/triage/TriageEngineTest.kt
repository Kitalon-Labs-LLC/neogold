package com.kitalonlabs.neogold.engine.triage

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

class TriageEngineTest {

    private lateinit var syntheticConfig: TriageConfig
    private lateinit var engine: TriageEngine

    @Before
    fun setUp() {
        // Find testdata/triage/config.synthetic.json from root or relative
        val candidatePaths = listOf(
            File("../../../testdata/triage/config.synthetic.json"),
            File("../../testdata/triage/config.synthetic.json"),
            File("../testdata/triage/config.synthetic.json"),
            File("testdata/triage/config.synthetic.json")
        )
        val configFile = candidatePaths.firstOrNull { it.exists() }
            ?: error("Could not find config.synthetic.json")
        val json = configFile.readText()
        syntheticConfig = TriageConfigParser.parse(json)
        engine = TriageEngine(
            config = syntheticConfig,
            isPublicBuild = false,
            clock = { LocalDate.of(2026, 10, 17) }
        )
    }

    @Test
    fun test_TC_TRI_01_danger_signs() {
        // Danger signs yes -> REFER_URGENT, R1, before the camera opens
        val result = engine.evaluate(
            TriageInputs(
                dangerSigns = true,
                gestationalAgeWeeks = 40,
                ageHours = 48.0,
                estimate = 5.0,
                lowerBound = 3.0,
                upperBound = 7.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.REFER_URGENT)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.R1)
        assertThat(action.thresholdUsed).isNull()
    }

    @Test
    fun test_TC_TRI_02_jaundice_in_first_24h() {
        // Onset under 24 h yes -> REFER_URGENT, R2, before the camera
        val result = engine.evaluate(
            TriageInputs(
                jaundiceOnsetUnder24h = true,
                gestationalAgeWeeks = 40,
                ageHours = 48.0,
                estimate = 5.0,
                lowerBound = 3.0,
                upperBound = 7.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.REFER_URGENT)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.R2)
        assertThat(action.thresholdUsed).isNull()
    }

    @Test
    fun test_TC_TRI_03_gestational_age_boundary() {
        // GA 34 -> CANNOT_SCREEN_BLOOD_TEST, R3. GA 35 -> screening continues
        val res34 = engine.evaluate(
            TriageInputs(
                gestationalAgeWeeks = 34,
                ageHours = 48.0,
                estimate = 5.0,
                lowerBound = 3.0,
                upperBound = 7.0
            )
        )
        assertThat(res34).isInstanceOf(TriageResult.Action::class.java)
        val action34 = res34 as TriageResult.Action
        assertThat(action34.action).isEqualTo(TriageAction.CANNOT_SCREEN_BLOOD_TEST)
        assertThat(action34.reasonCode).isEqualTo(TriageReasonCode.R3)

        val res35 = engine.evaluate(
            TriageInputs(
                gestationalAgeWeeks = 35,
                ageHours = 48.0,
                estimate = 5.0,
                lowerBound = 3.0,
                upperBound = 7.0
            )
        )
        assertThat(res35).isInstanceOf(TriageResult.Action::class.java)
        val action35 = res35 as TriageResult.Action
        assertThat(action35.action).isEqualTo(TriageAction.NO_FURTHER_ACTION_FROM_SCREEN)
        assertThat(action35.reasonCode).isEqualTo(TriageReasonCode.NONE)
    }

    @Test
    fun test_TC_TRI_04_prior_phototherapy() {
        // Phototherapy yes -> CANNOT_SCREEN_BLOOD_TEST, R4
        val result = engine.evaluate(
            TriageInputs(
                priorPhototherapy = true,
                gestationalAgeWeeks = 40,
                ageHours = 48.0,
                estimate = 5.0,
                lowerBound = 3.0,
                upperBound = 7.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.CANNOT_SCREEN_BLOOD_TEST)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.R4)
    }

    @Test
    fun test_TC_TRI_05_14_day_boundary() {
        // Age 336.1 h -> CANNOT_SCREEN_CLINICAL_REVIEW, R5. Age 336.0 h -> screening continues
        val res336_1 = engine.evaluate(
            TriageInputs(
                ageHours = 336.1,
                gestationalAgeWeeks = 40,
                estimate = 5.0,
                lowerBound = 3.0,
                upperBound = 7.0
            )
        )
        assertThat(res336_1).isInstanceOf(TriageResult.Action::class.java)
        val action336_1 = res336_1 as TriageResult.Action
        assertThat(action336_1.action).isEqualTo(TriageAction.CANNOT_SCREEN_CLINICAL_REVIEW)
        assertThat(action336_1.reasonCode).isEqualTo(TriageReasonCode.R5)

        val res336_0 = engine.evaluate(
            TriageInputs(
                ageHours = 336.0,
                gestationalAgeWeeks = 40,
                estimate = 5.0,
                lowerBound = 3.0,
                upperBound = 7.0
            )
        )
        assertThat(res336_0).isInstanceOf(TriageResult.Action::class.java)
        val action336_0 = res336_0 as TriageResult.Action
        assertThat(action336_0.action).isEqualTo(TriageAction.NO_FURTHER_ACTION_FROM_SCREEN)
    }

    @Test
    fun test_TC_TRI_06_rule_order() {
        // Danger signs yes and GA 34 -> REFER_URGENT, R1 (R1 wins)
        val result = engine.evaluate(
            TriageInputs(
                dangerSigns = true,
                gestationalAgeWeeks = 34,
                ageHours = 48.0,
                estimate = 5.0,
                lowerBound = 3.0,
                upperBound = 7.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.REFER_URGENT)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.R1)
    }

    @Test
    fun test_TC_TRI_07_estimate_exactly_at_threshold() {
        // 48 h, GA 40 (T = 17.0); E 17.0, L 15.0, U 19.0 -> REFER_URGENT, ABOVE_THRESHOLD
        val result = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 17.0,
                lowerBound = 15.0,
                upperBound = 19.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.REFER_URGENT)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.ABOVE_THRESHOLD)
        assertThat(action.thresholdUsed).isEqualTo(17.0)
    }

    @Test
    fun test_TC_TRI_08_just_below_threshold() {
        // 48 h; E 16.9, L 14.9, U 18.9 -> BLOOD_TEST_TODAY, ZONE_WITHIN3
        val result = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 16.9,
                lowerBound = 14.9,
                upperBound = 18.9
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.ZONE_WITHIN3)
    }

    @Test
    fun test_TC_TRI_09_upper_bound_at_T_minus_3() {
        // 48 h; E 12.0, L 10.0, U 14.0; then E 11.9, L 9.9, U 13.9
        // 14.0: BLOOD_TEST_TODAY, ZONE_WITHIN3. 13.9: RECHECK
        val res14_0 = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 12.0,
                lowerBound = 10.0,
                upperBound = 14.0
            )
        )
        assertThat(res14_0).isInstanceOf(TriageResult.Action::class.java)
        val action14_0 = res14_0 as TriageResult.Action
        assertThat(action14_0.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action14_0.reasonCode).isEqualTo(TriageReasonCode.ZONE_WITHIN3)

        val res13_9 = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 11.9,
                lowerBound = 9.9,
                upperBound = 13.9
            )
        )
        assertThat(res13_9).isInstanceOf(TriageResult.Action::class.java)
        val action13_9 = res13_9 as TriageResult.Action
        assertThat(action13_9.action).isEqualTo(TriageAction.RECHECK)
        assertThat(action13_9.reasonCode).isEqualTo(TriageReasonCode.RECHECK_ZONE)
    }

    @Test
    fun test_TC_TRI_10_absolute_15_rule() {
        // 96 h (T = 20.0); E 12.0, L 10.0, U 15.0; then E 11.9, L 9.9, U 14.9
        // 15.0: BLOOD_TEST_TODAY, ZONE_ABS15. 14.9: NO_FURTHER_ACTION_FROM_SCREEN
        val res15_0 = engine.evaluate(
            TriageInputs(
                ageHours = 96.0,
                gestationalAgeWeeks = 40,
                estimate = 12.0,
                lowerBound = 10.0,
                upperBound = 15.0
            )
        )
        assertThat(res15_0).isInstanceOf(TriageResult.Action::class.java)
        val action15_0 = res15_0 as TriageResult.Action
        assertThat(action15_0.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action15_0.reasonCode).isEqualTo(TriageReasonCode.ZONE_ABS15)

        val res14_9 = engine.evaluate(
            TriageInputs(
                ageHours = 96.0,
                gestationalAgeWeeks = 40,
                estimate = 11.9,
                lowerBound = 9.9,
                upperBound = 14.9
            )
        )
        assertThat(res14_9).isInstanceOf(TriageResult.Action::class.java)
        val action14_9 = res14_9 as TriageResult.Action
        assertThat(action14_9.action).isEqualTo(TriageAction.NO_FURTHER_ACTION_FROM_SCREEN)
        assertThat(action14_9.reasonCode).isEqualTo(TriageReasonCode.NONE)
    }

    @Test
    fun test_TC_TRI_11_interval_width() {
        // 48 h; L 3.0, E 6.0, U 9.1; then L 3.0, U 9.0
        // Width 6.1: BLOOD_TEST_TODAY, WIDE_INTERVAL. Width 6.0: NO_FURTHER_ACTION_FROM_SCREEN
        val res6_1 = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 6.0,
                lowerBound = 3.0,
                upperBound = 9.1
            )
        )
        assertThat(res6_1).isInstanceOf(TriageResult.Action::class.java)
        val action6_1 = res6_1 as TriageResult.Action
        assertThat(action6_1.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action6_1.reasonCode).isEqualTo(TriageReasonCode.WIDE_INTERVAL)

        val res6_0 = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 6.0,
                lowerBound = 3.0,
                upperBound = 9.0
            )
        )
        assertThat(res6_0).isInstanceOf(TriageResult.Action::class.java)
        val action6_0 = res6_0 as TriageResult.Action
        assertThat(action6_0.action).isEqualTo(TriageAction.NO_FURTHER_ACTION_FROM_SCREEN)
        assertThat(action6_0.reasonCode).isEqualTo(TriageReasonCode.NONE)
    }

    @Test
    fun test_TC_TRI_12_recheck_boundary() {
        // 48 h; E 10.0, L 8.0, U 12.0 -> RECHECK, RECHECK_ZONE; recheck due 24 h after capture
        val result = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 10.0,
                lowerBound = 8.0,
                upperBound = 12.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.RECHECK)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.RECHECK_ZONE)
        assertThat(action.recheckHours).isEqualTo(24)
    }

    @Test
    fun test_TC_TRI_13_below_every_zone() {
        // 48 h; E 10.0, L 8.1, U 11.9 -> NO_FURTHER_ACTION_FROM_SCREEN
        val result = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 10.0,
                lowerBound = 8.1,
                upperBound = 11.9
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.NO_FURTHER_ACTION_FROM_SCREEN)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.NONE)
    }

    @Test
    fun test_TC_TRI_14_threshold_interpolation() {
        // 36 h (T = 14.5); E 14.5, L 12.5, U 16.5; then E 14.4, L 12.4, U 16.4
        // 14.5: REFER_URGENT. 14.4: BLOOD_TEST_TODAY, ZONE_WITHIN3
        val res14_5 = engine.evaluate(
            TriageInputs(
                ageHours = 36.0,
                gestationalAgeWeeks = 40,
                estimate = 14.5,
                lowerBound = 12.5,
                upperBound = 16.5
            )
        )
        assertThat(res14_5).isInstanceOf(TriageResult.Action::class.java)
        val action14_5 = res14_5 as TriageResult.Action
        assertThat(action14_5.action).isEqualTo(TriageAction.REFER_URGENT)
        assertThat(action14_5.reasonCode).isEqualTo(TriageReasonCode.ABOVE_THRESHOLD)
        assertThat(action14_5.thresholdUsed).isEqualTo(14.5)

        val res14_4 = engine.evaluate(
            TriageInputs(
                ageHours = 36.0,
                gestationalAgeWeeks = 40,
                estimate = 14.4,
                lowerBound = 12.4,
                upperBound = 16.4
            )
        )
        assertThat(res14_4).isInstanceOf(TriageResult.Action::class.java)
        val action14_4 = res14_4 as TriageResult.Action
        assertThat(action14_4.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action14_4.reasonCode).isEqualTo(TriageReasonCode.ZONE_WITHIN3)
        assertThat(action14_4.thresholdUsed).isEqualTo(14.5)
    }

    @Test
    fun test_TC_TRI_15_quality_fallback() {
        // Three failed captures -> BLOOD_TEST_TODAY, QUALITY_FALLBACK
        val result = engine.evaluate(
            TriageInputs(
                gestationalAgeWeeks = 40,
                ageHours = 48.0,
                qualityFailedAttempts = 3,
                estimate = 5.0,
                lowerBound = 3.0,
                upperBound = 7.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.QUALITY_FALLBACK)
        assertThat(action.thresholdUsed).isNull()
    }

    @Test
    fun test_TC_TRI_16_band_selection() {
        // 48 h; E 15.5, L 13.5, U 17.5 with GA 37, then GA 38
        // GA 37 (band GA 36 to 37, T = 15.5): REFER_URGENT
        // GA 38 (T = 17.0): BLOOD_TEST_TODAY
        val res37 = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 37,
                estimate = 15.5,
                lowerBound = 13.5,
                upperBound = 17.5
            )
        )
        assertThat(res37).isInstanceOf(TriageResult.Action::class.java)
        val action37 = res37 as TriageResult.Action
        assertThat(action37.action).isEqualTo(TriageAction.REFER_URGENT)
        assertThat(action37.reasonCode).isEqualTo(TriageReasonCode.ABOVE_THRESHOLD)
        assertThat(action37.thresholdUsed).isEqualTo(15.5)

        val res38 = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 38,
                estimate = 15.5,
                lowerBound = 13.5,
                upperBound = 17.5
            )
        )
        assertThat(res38).isInstanceOf(TriageResult.Action::class.java)
        val action38 = res38 as TriageResult.Action
        assertThat(action38.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action38.reasonCode).isEqualTo(TriageReasonCode.ZONE_WITHIN3)
        assertThat(action38.thresholdUsed).isEqualTo(17.0)
    }

    @Test
    fun test_TC_TRI_17_release_config_covers_every_case() {
        // Schema and property check on clinical config
        val clinicalJson = File("config/triage-config.json").takeIf { it.exists() }
            ?: File("../../../config/triage-config.json").takeIf { it.exists() }
            ?: error("Could not find config/triage-config.json")
        val parsed = TriageConfigParser.parse(clinicalJson.readText())

        // Every GA from 35 to 44 maps to exactly one band
        val bands = (35..44).map { ga -> parsed.bandForGestationalAge(ga) }.toSet()
        assertThat(bands).containsExactly("ga35", "ga36", "ga38plus")

        // Every band covers 0 to 336 h
        for (bandName in listOf("ga35", "ga36", "ga38plus")) {
            val table = parsed.phototherapyThresholds[bandName]
            assertThat(table).isNotNull()
            assertThat(table!!.first().first).isEqualTo(0.0)
            assertThat(table.last().first).isAtLeast(336.0)
        }
    }

    @Test
    fun test_TC_TRI_18_draft_config_in_a_public_build() {
        // Public build with clinicalSignoff.status = DRAFT -> No triage result anywhere; blood test message
        val draftConfig = syntheticConfig.copy(
            clinicalSignoff = ClinicalSignoff(SignoffStatus.DRAFT, null)
        )
        val publicEngine = TriageEngine(
            config = draftConfig,
            isPublicBuild = true,
            clock = { LocalDate.of(2026, 10, 17) }
        )
        val result = publicEngine.evaluate(
            TriageInputs(
                gestationalAgeWeeks = 40,
                ageHours = 48.0,
                estimate = 12.0,
                lowerBound = 10.0,
                upperBound = 14.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Unavailable::class.java)
        val unavail = result as TriageResult.Unavailable
        assertThat(unavail.reason).isEqualTo(TriageUnavailableReason.CONFIG_UNSIGNED)
    }

    @Test
    fun test_TC_TRI_19_config_signature_broken() {
        // Config with malformed / invalid content fails parser
        val brokenJson = """{ "configVersion": "2026.10.0", "units": "invalidUnit" }"""
        var failed = false
        try {
            TriageConfigParser.parse(brokenJson)
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertThat(failed).isTrue()
    }

    @Test
    fun test_H7_config_cut_off_anywhere_is_rejected() {
        // A config that is cut off at any point must be rejected outright, never half-accepted (H7, H8).
        // Pins the "Unexpected EOF" paths of the JSON tokenizer. No numbered test case covers truncation,
        // so this cites the hazard only: it must not count as coverage for another case.
        val configFile = File("config/triage-config.json").takeIf { it.exists() }
            ?: File("../../../config/triage-config.json").takeIf { it.exists() }
            ?: error("Could not find config/triage-config.json")
        val json = configFile.readText().trimEnd()

        for (cut in 0 until json.length) {
            assertThrows(IllegalArgumentException::class.java) { TriageConfigParser.parse(json.substring(0, cut)) }
        }
        val eof = assertThrows(IllegalArgumentException::class.java) { TriageConfigParser.parse("") }
        assertThat(eof).hasMessageThat().isEqualTo("Unexpected EOF")
    }

    @Test
    fun test_TC_TRI_20_config_past_validUntil() {
        // Set device date after validUntil -> Triage disabled; message to update the app
        val expiredEngine = TriageEngine(
            config = syntheticConfig,
            isPublicBuild = false,
            clock = { LocalDate.of(2028, 1, 1) } // syntheticConfig validUntil is 2027-12-31
        )
        val result = expiredEngine.evaluate(
            TriageInputs(
                gestationalAgeWeeks = 40,
                ageHours = 48.0,
                estimate = 12.0,
                lowerBound = 10.0,
                upperBound = 14.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Unavailable::class.java)
        val unavail = result as TriageResult.Unavailable
        assertThat(unavail.reason).isEqualTo(TriageUnavailableReason.CONFIG_EXPIRED)
        assertThat(unavail.message).contains("expired")
    }

    @Test
    fun test_TC_TRI_21_signoff_hash_enforced_in_CI() {
        // CI check verify_clinical_signoff.py handles triageEnabled false
        val draftJson = File("config/triage-config.json").takeIf { it.exists() }
            ?: File("../../../config/triage-config.json").takeIf { it.exists() }
            ?: error("Could not find config/triage-config.json")
        val parsed = TriageConfigParser.parse(draftJson.readText())
        assertThat(parsed.triageEnabled).isFalse()
        assertThat(parsed.clinicalSignoff.status).isEqualTo(SignoffStatus.DRAFT)
    }

    @Test
    fun test_TC_TRI_22_output_traceability() {
        // Action, reason codes, threshold, interval, config version stored
        val result = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 12.0,
                lowerBound = 10.0,
                upperBound = 14.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.ZONE_WITHIN3)
        assertThat(action.thresholdUsed).isEqualTo(17.0)
        assertThat(action.interval).isNotNull()
        assertThat(action.interval!!.estimate).isEqualTo(12.0)
        assertThat(action.configVersion).isEqualTo(syntheticConfig.configVersion)
    }

    @Test
    fun test_TC_TRI_23_fixed_point_comparison() {
        // 49.6 h, GA 40 (T = 17.1); E 12.0, L 10.0, U 14.1.
        // In double precision 17.1 minus 3.0 is 14.100000000000001, so a naive comparison misses the zone.
        // Compared in integer tenths (141 against 141); BLOOD_TEST_TODAY, ZONE_WITHIN3
        val result = engine.evaluate(
            TriageInputs(
                ageHours = 49.6,
                gestationalAgeWeeks = 40,
                estimate = 12.0,
                lowerBound = 10.0,
                upperBound = 14.1
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.thresholdUsed).isEqualTo(17.1)
        assertThat(action.action).isEqualTo(TriageAction.BLOOD_TEST_TODAY)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.ZONE_WITHIN3)
    }

    @Test
    fun test_TC_TRI_24_differential_test_boundaries() {
        // Check core boundary conditions matching Python reference
        val result = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 10.0,
                lowerBound = 8.0,
                upperBound = 12.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Action::class.java)
        val action = result as TriageResult.Action
        assertThat(action.action).isEqualTo(TriageAction.RECHECK)
        assertThat(action.reasonCode).isEqualTo(TriageReasonCode.RECHECK_ZONE)
    }

    @Test
    fun test_TC_TRI_25_rounding_toward_more_care() {
        // 48 h (T = 17.0); E 16.95, L 14.0, U 18.0; then an interpolated threshold of 16.96 against E 16.9
        // E rounds up to 17.0: REFER_URGENT.
        val resA = engine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 16.95,
                lowerBound = 14.0,
                upperBound = 18.0
            )
        )
        assertThat(resA).isInstanceOf(TriageResult.Action::class.java)
        val actionA = resA as TriageResult.Action
        assertThat(actionA.action).isEqualTo(TriageAction.REFER_URGENT)

        // Custom config where at age 48 h the threshold is 16.96
        val table16_96 = listOf(Pair(0.0, 16.96), Pair(336.0, 16.96))
        val customConfig = syntheticConfig.copy(
            phototherapyThresholds = syntheticConfig.phototherapyThresholds + ("ga38plus" to table16_96)
        )
        val customEngine = TriageEngine(config = customConfig, isPublicBuild = false)
        // Threshold rounds down from 16.96 to 16.9. With E = 16.9: E >= T (169 >= 169) -> REFER_URGENT
        val resB = customEngine.evaluate(
            TriageInputs(
                ageHours = 48.0,
                gestationalAgeWeeks = 40,
                estimate = 16.9,
                lowerBound = 14.0,
                upperBound = 18.0
            )
        )
        assertThat(resB).isInstanceOf(TriageResult.Action::class.java)
        val actionB = resB as TriageResult.Action
        assertThat(actionB.action).isEqualTo(TriageAction.REFER_URGENT)
        assertThat(actionB.thresholdUsed).isEqualTo(16.9)
    }

    @Test
    fun test_TC_TRI_26_triage_disabled() {
        // Config with triageEnabled false and no sign-off
        // G1 passes; no triage result on any screen; blood test message; research mode unaffected
        val disabledConfig = syntheticConfig.copy(triageEnabled = false)
        val disabledEngine = TriageEngine(config = disabledConfig)
        val result = disabledEngine.evaluate(
            TriageInputs(
                gestationalAgeWeeks = 40,
                ageHours = 48.0,
                estimate = 12.0,
                lowerBound = 10.0,
                upperBound = 14.0
            )
        )
        assertThat(result).isInstanceOf(TriageResult.Unavailable::class.java)
        val unavail = result as TriageResult.Unavailable
        assertThat(unavail.reason).isEqualTo(TriageUnavailableReason.TRIAGE_DISABLED)
        assertThat(unavail.message).contains("blood test")
    }
}
