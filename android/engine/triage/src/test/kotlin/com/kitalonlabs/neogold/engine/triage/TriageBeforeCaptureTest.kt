package com.kitalonlabs.neogold.engine.triage

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * `evaluateBeforeCapture` is the question the screening flow asks before the camera opens (package E1): is triage available,
 * and does a hard rule already decide? It runs the same code as steps 1 and 2 of `evaluate`, so these tests pin that the two
 * never disagree: TC-TRI-01 to TC-TRI-05 (hard rules), TC-TRI-26 (triage off, unsigned, expired), TC-TRI-24 (the same
 * decision as the full evaluation).
 */
class TriageBeforeCaptureTest {

    private lateinit var config: TriageConfig
    private val today = LocalDate.of(2026, 10, 17)

    @Before
    fun setUp() {
        val file = listOf(
            File("../../../testdata/triage/config.synthetic.json"),
            File("../../testdata/triage/config.synthetic.json"),
            File("../testdata/triage/config.synthetic.json"),
            File("testdata/triage/config.synthetic.json")
        ).firstOrNull { it.exists() } ?: error("Could not find config.synthetic.json")
        config = TriageConfigParser.parse(file.readText())
    }

    private fun engine(cfg: TriageConfig = config, publicBuild: Boolean = false) =
        TriageEngine(cfg, isPublicBuild = publicBuild, clock = { today })

    private fun inputs(
        danger: Boolean = false,
        early: Boolean = false,
        ga: Int = 38,
        phototherapy: Boolean = false,
        ageHours: Double = 48.0
    ) = TriageInputs(
        dangerSigns = danger,
        jaundiceOnsetUnder24h = early,
        gestationalAgeWeeks = ga,
        priorPhototherapy = phototherapy,
        ageHours = ageHours
    )

    @Test
    fun tcTri01_to_05_eachHardRuleDecidesBeforeTheCameraOpens() {
        val e = engine()
        val cases = listOf(
            inputs(danger = true) to (TriageAction.REFER_URGENT to TriageReasonCode.R1),
            inputs(early = true) to (TriageAction.REFER_URGENT to TriageReasonCode.R2),
            inputs(ga = 34) to (TriageAction.CANNOT_SCREEN_BLOOD_TEST to TriageReasonCode.R3),
            inputs(phototherapy = true) to (TriageAction.CANNOT_SCREEN_BLOOD_TEST to TriageReasonCode.R4),
            inputs(ageHours = 336.1) to (TriageAction.CANNOT_SCREEN_CLINICAL_REVIEW to TriageReasonCode.R5)
        )
        for ((input, expected) in cases) {
            val result = e.evaluateBeforeCapture(input) as TriageResult.Action
            assertThat(result.action).isEqualTo(expected.first)
            assertThat(result.reasonCode).isEqualTo(expected.second)
            assertThat(result.interval).isNull()
        }
    }

    @Test
    fun tcTri_firstMatchingRuleWins_inTheStrictOrder() {
        val result = engine().evaluateBeforeCapture(
            inputs(danger = true, early = true, ga = 30, phototherapy = true, ageHours = 400.0)
        ) as TriageResult.Action
        assertThat(result.reasonCode).isEqualTo(TriageReasonCode.R1)
    }

    @Test
    fun tcTri_whenNoHardRuleFires_thereIsNothingToShowAndTheCameraMayOpen() {
        assertThat(engine().evaluateBeforeCapture(inputs())).isNull()
        // the boundaries: 35 weeks and 336 hours are in scope
        assertThat(engine().evaluateBeforeCapture(inputs(ga = 35, ageHours = 336.0))).isNull()
    }

    @Test
    fun tcTri26_aDisabledConfigIsUnavailableBeforeAnyRuleIsLookedAt() {
        val off = config.copy(triageEnabled = false)
        // even a danger sign: the flow does not pretend to triage; the screen shows the blood test message
        val result = engine(off).evaluateBeforeCapture(inputs(danger = true))
        assertThat((result as TriageResult.Unavailable).reason).isEqualTo(TriageUnavailableReason.TRIAGE_DISABLED)
    }

    @Test
    fun tcTri26_anUnsignedConfigInAPublicBuildAndAnExpiredConfigAreUnavailable() {
        val unsigned = config.copy(clinicalSignoff = ClinicalSignoff(SignoffStatus.DRAFT, null))
        val unsignedResult = engine(unsigned, publicBuild = true).evaluateBeforeCapture(inputs())
        assertThat(
            (unsignedResult as TriageResult.Unavailable).reason
        ).isEqualTo(TriageUnavailableReason.CONFIG_UNSIGNED)
        // the same unsigned config is fine for a development build
        assertThat(engine(unsigned, publicBuild = false).evaluateBeforeCapture(inputs())).isNull()

        val expired = config.copy(validUntil = today.minusDays(1))
        val expiredResult = engine(expired).evaluateBeforeCapture(inputs())
        assertThat((expiredResult as TriageResult.Unavailable).reason).isEqualTo(TriageUnavailableReason.CONFIG_EXPIRED)
    }

    @Test
    fun tcTri24_theSameDecisionAsTheFullEvaluation_forEveryHardRuleCombination() {
        val e = engine()
        for (danger in listOf(false, true)) for (early in listOf(false, true)) for (ga in listOf(34, 35, 40)) {
            for (photo in listOf(false, true)) for (age in listOf(24.0, 336.0, 340.0)) {
                val input = inputs(danger, early, ga, photo, age)
                val before = e.evaluateBeforeCapture(input)
                if (before != null) {
                    // a rule or an unavailable config: the full evaluation says exactly the same
                    val full = e.evaluate(input.copy(estimate = 9.0, lowerBound = 8.0, upperBound = 10.0))
                    val beforeAction = before as TriageResult.Action
                    val fullAction = full as TriageResult.Action
                    assertThat(fullAction.action).isEqualTo(beforeAction.action)
                    assertThat(fullAction.reasonCode).isEqualTo(beforeAction.reasonCode)
                } else {
                    // no hard rule: the full evaluation reaches the threshold step and so needs an estimate
                    val full = e.evaluate(input.copy(estimate = 9.0, lowerBound = 8.0, upperBound = 10.0))
                    assertThat((full as TriageResult.Action).reasonCode).isNotIn(
                        listOf(
                            TriageReasonCode.R1,
                            TriageReasonCode.R2,
                            TriageReasonCode.R3,
                            TriageReasonCode.R4,
                            TriageReasonCode.R5
                        )
                    )
                }
            }
        }
    }
}
