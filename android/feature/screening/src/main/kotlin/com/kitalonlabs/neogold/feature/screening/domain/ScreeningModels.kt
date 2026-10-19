package com.kitalonlabs.neogold.feature.screening.domain

import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.colorscience.Rgb
import com.kitalonlabs.neogold.engine.estimation.EstimationOutput
import com.kitalonlabs.neogold.engine.estimation.SingleSiteEstimate
import com.kitalonlabs.neogold.engine.triage.TriageResult
import com.kitalonlabs.neogold.engine.vision.QualityGateResult

/**
 * Clinical inputs collected during the screening flow.
 * Note: No birth date or time is stored; age is computed in hours (doc 4 Section 8).
 */
data class ScreeningInputs(
    val gestationalAgeWeeks: Int,
    val ageHours: Double,
    val dangerSigns: Boolean,
    val jaundiceOnsetUnder24h: Boolean,
    val priorPhototherapy: Boolean,
    val guardianConsentConfirmed: Boolean,
    val registerNumber: String? = null
) {
    init {
        require(gestationalAgeWeeks >= 0) { "Gestational age weeks must be non-negative" }
        require(ageHours >= 0.0) { "Age in hours must be non-negative" }
    }
}

/**
 * Output traceability record (NFR-10, NG-025, TC-TRI-22).
 * Every screening result is traceable to app, model, config, card batch, and device.
 */
data class VersionsRecord(
    val appVersion: String,
    val modelVersion: String,
    val configVersion: String,
    val cardBatchId: String,
    val deviceModel: String,
    val deviceManufacturer: String,
    val osVersion: String
)

/**
 * Reason a card is blocked from screening (doc 4 Section 4.4).
 */
enum class CardBlockReason {
    UNVERIFIED_SIGNATURE,
    UNKNOWN_ISSUER,
    BATCH_EXPIRED,
    BATCH_REVOKED,
    WRONG_PURPOSE,
    MALFORMED_PAYLOAD,
    ASSET_INTEGRITY_COMPROMISED
}

/**
 * All intermediate values computed during the capture-to-estimate pipeline.
 * Exhibited on the debug screen (debug builds only) and recorded for traceability.
 */
data class ScreeningIntermediateData(
    val cardPayload: CardBatchPayload,
    val captureMetadata: CaptureMetadata?,
    val qualityResult: QualityGateResult,
    val rawWindowRgb: Rgb,
    val linearizedWindowRgb: Rgb,
    val ccmMatrix: List<List<Double>>,
    val correctedWindowLab: Lab,
    val extractedFeatures: Map<String, Double>,
    val standardizedFeatures: List<Double>,
    val singleSiteEstimate: SingleSiteEstimate,
    val estimationOutput: EstimationOutput,
    val triageResult: TriageResult,
    val versions: VersionsRecord,
    val stageTimingsMs: Map<String, Long> = emptyMap()
)

/**
 * Summary written to screening log upon reaching a terminal state.
 */
data class ScreeningResultSummary(
    val sessionId: String,
    val inputs: ScreeningInputs,
    val triageResult: TriageResult,
    val versions: VersionsRecord,
    val timestampEpochMs: Long = System.currentTimeMillis()
)
