package com.kitalonlabs.neogold.feature.screening.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitalonlabs.neogold.engine.triage.TriageResult
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningIntermediateData
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningState
import java.util.Locale

/**
 * Debug screen (debug builds only) showing every intermediate pipeline value,
 * stage timings, and the versions record (NFR-10, NG-025).
 * Excluded from release builds by placement in src/debug source set.
 */
@Composable
fun ScreeningDebugScreen(
    state: ScreeningState,
    intermediateData: ScreeningIntermediateData?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F1216))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NEOGOLD DEBUG SCREEN",
                        color = Color(0xFF64B5F6),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "v0.1 Pipeline Integration (DEBUG ONLY)",
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Close", fontSize = 12.sp, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // State Machine Status
            DebugSectionCard(title = "1. STATE MACHINE STATUS") {
                val stateName = state::class.simpleName ?: "Unknown"
                DebugDataRow("Current State", stateName)
                when (state) {
                    is ScreeningState.UrgentReferral -> {
                        DebugDataRow("Referral Reason", state.reasonCode)
                    }
                    is ScreeningState.CannotScreen -> {
                        DebugDataRow("Cannot Screen Reason", state.reasonCode)
                    }
                    is ScreeningState.Blocked -> {
                        DebugDataRow("Card Blocked Reason", state.reason.name)
                    }
                    is ScreeningState.Capture -> {
                        DebugDataRow("Capture Attempt", "${state.attempt} / 3")
                        DebugDataRow("Previous Failure", state.previousFailure?.name ?: "None")
                    }
                    is ScreeningState.BloodTestFallback -> {
                        DebugDataRow("Fallback Reason", "QUALITY_FALLBACK (3 failed captures)")
                    }
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Clinical Inputs
            val inputs = when (state) {
                is ScreeningState.Inputs -> state.inputs
                is ScreeningState.RuleCheck -> state.inputs
                is ScreeningState.UrgentReferral -> state.inputs
                is ScreeningState.CannotScreen -> state.inputs
                is ScreeningState.CardCheck -> state.inputs
                is ScreeningState.Blocked -> state.inputs
                is ScreeningState.Capture -> state.inputs
                is ScreeningState.Quality -> state.inputs
                is ScreeningState.BloodTestFallback -> state.inputs
                is ScreeningState.Analysis -> state.inputs
                is ScreeningState.Result -> state.inputs
                is ScreeningState.Saved -> state.summary.inputs
                else -> null
            }

            if (inputs != null) {
                DebugSectionCard(title = "2. CLINICAL SCREENING INPUTS") {
                    DebugDataRow("Gestational Age", "${inputs.gestationalAgeWeeks} weeks")
                    DebugDataRow("Age in Hours", "%.1f h".format(Locale.US, inputs.ageHours))
                    DebugDataRow("Guardian Consent", if (inputs.guardianConsentConfirmed) "CONFIRMED" else "REFUSED")
                    DebugDataRow("Danger Signs (R1)", if (inputs.dangerSigns) "YES (CRITICAL)" else "No")
                    DebugDataRow("Onset < 24h (R2)", if (inputs.jaundiceOnsetUnder24h) "YES (CRITICAL)" else "No")
                    DebugDataRow("Prior Phototherapy (R4)", if (inputs.priorPhototherapy) "YES" else "No")
                    DebugDataRow("Facility Register No", inputs.registerNumber ?: "None")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Intermediate Data Sections
            if (intermediateData != null) {
                // Card Trust
                DebugSectionCard(title = "3. CARD TRUST & CALIBRATION BATCH") {
                    val batch = intermediateData.cardPayload
                    DebugDataRow("Batch ID", batch.batchId)
                    DebugDataRow("Issuer Key ID", batch.issuerKeyId)
                    DebugDataRow("Format Version", "${batch.formatVersion}")
                    DebugDataRow("Issued Date", batch.issuedAt.toString())
                    DebugDataRow("Expires Date", batch.expiresAt.toString())
                    DebugDataRow("Measured Patches", "${batch.patchCount} patches in QR")
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quality Gate Metrics
                DebugSectionCard(title = "4. VISION & QUALITY GATE METRICS") {
                    val m = intermediateData.qualityResult.metrics
                    DebugDataRow("Fiducial Markers", "${m.markersDetected} / 4")
                    DebugDataRow("Card Tilt Angle", "%.1f° (max 20°)".format(Locale.US, m.cardAngleDegrees))
                    DebugDataRow("Blur Laplacian Var", "%.1f (min 100)".format(Locale.US, m.blurLaplacianVariance))
                    DebugDataRow("Glare Fraction", "%.2f%% (max 2%%)".format(Locale.US, m.glarePixelFraction * 100))
                    DebugDataRow("Mid-Gray Lum (G4)", "%.1f (40..215)".format(Locale.US, m.midGrayLuminance))
                    DebugDataRow("Duplicate ΔE", "%.2f (max 2.0)".format(Locale.US, m.duplicatePatchDeltaE))
                    DebugDataRow("Light Uniformity Δ", "%.2f (max 2.0)".format(Locale.US, m.lightUniformityDelta))
                    DebugDataRow("Correction Residual ΔE", "%.2f (max 3.0)".format(Locale.US, m.meanCorrectionResidualDeltaE))
                    DebugDataRow("Skin Plausibility", if (m.skinWindowSample?.isPlausibleSkin == true) "PASSED" else "REJECTED")
                    DebugDataRow("Quality Gate Status", if (intermediateData.qualityResult.passed) "PASSED" else "FAILED")
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Color Science
                DebugSectionCard(title = "5. COLOR CORRECTION & CIELAB (D65)") {
                    val rawRgb = intermediateData.rawWindowRgb
                    val linRgb = intermediateData.linearizedWindowRgb
                    val lab = intermediateData.correctedWindowLab
                    DebugDataRow("Raw Window RGB", "[%.3f, %.3f, %.3f]".format(Locale.US, rawRgb.r, rawRgb.g, rawRgb.b))
                    DebugDataRow("Linearized RGB", "[%.3f, %.3f, %.3f]".format(Locale.US, linRgb.r, linRgb.g, linRgb.b))
                    DebugDataRow("Corrected Lab", "L*=%.2f, a*=%.2f, b*=%.2f".format(Locale.US, lab.l, lab.a, lab.b))

                    val ccm = intermediateData.ccmMatrix
                    DebugDataRow("CCM Row 0", "[%.3f, %.3f, %.3f]".format(Locale.US, ccm[0][0], ccm[0][1], ccm[0][2]))
                    DebugDataRow("CCM Row 1", "[%.3f, %.3f, %.3f]".format(Locale.US, ccm[1][0], ccm[1][1], ccm[1][2]))
                    DebugDataRow("CCM Row 2", "[%.3f, %.3f, %.3f]".format(Locale.US, ccm[2][0], ccm[2][1], ccm[2][2]))
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Features & Estimation
                DebugSectionCard(title = "6. FEATURE VECTOR & BILIRUBIN ESTIMATION") {
                    val f = intermediateData.extractedFeatures
                    DebugDataRow("L* Feature", "%.4f".format(Locale.US, f["l_star"] ?: 0.0))
                    DebugDataRow("a* Feature", "%.4f".format(Locale.US, f["a_star"] ?: 0.0))
                    DebugDataRow("b* Feature", "%.4f".format(Locale.US, f["b_star"] ?: 0.0))
                    DebugDataRow("b*/L* Ratio", "%.4f".format(Locale.US, f["b_over_l"] ?: 0.0))
                    DebugDataRow("a*/b* Ratio", "%.4f".format(Locale.US, f["a_over_b"] ?: 0.0))

                    val est = intermediateData.estimationOutput
                    DebugDataRow("Point Estimate (E)", "%.2f %s".format(Locale.US, est.estimate, est.units))
                    DebugDataRow("90% Interval [L, U]", "[%.2f, %.2f] %s".format(Locale.US, est.lowerBound, est.upperBound, est.units))
                    DebugDataRow("Interval Width", "%.2f %s".format(Locale.US, est.upperBound - est.lowerBound, est.units))
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Triage Decision
                DebugSectionCard(title = "7. TRIAGE ENGINE DECISION") {
                    when (val tr = intermediateData.triageResult) {
                        is TriageResult.Action -> {
                            DebugDataRow("Triage Action", tr.action.name)
                            DebugDataRow("Reason Code", tr.reasonCode)
                            DebugDataRow("Threshold T Used", tr.thresholdUsed?.let { "%.1f mg/dL".format(Locale.US, it) } ?: "N/A")
                            DebugDataRow("Config Version", tr.configVersion)
                        }
                        is TriageResult.Unavailable -> {
                            DebugDataRow("Triage Status", "UNAVAILABLE")
                            DebugDataRow("Unavailable Reason", tr.reason.name)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Versions & Traceability
                DebugSectionCard(title = "8. VERSION TRACEABILITY RECORD (NFR-10, NG-025)") {
                    val v = intermediateData.versions
                    DebugDataRow("App Version", v.appVersion)
                    DebugDataRow("Model Version", v.modelVersion)
                    DebugDataRow("Config Version", v.configVersion)
                    DebugDataRow("Card Batch ID", v.cardBatchId)
                    DebugDataRow("Device Model", "${v.deviceManufacturer} ${v.deviceModel}")
                    DebugDataRow("OS Version", v.osVersion)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Stage Timings
                DebugSectionCard(title = "9. PERFORMANCE TIMINGS (DOC 4 SEC 5.6)") {
                    val timings = intermediateData.stageTimingsMs
                    DebugDataRow("Warp & Sample", "${timings["decode_warp_sample_ms"] ?: 0} ms (budget < 700 ms)")
                    DebugDataRow("Quality & Analysis", "${timings["quality_and_analysis_ms"] ?: 0} ms (budget < 200 ms)")
                    DebugDataRow("Capture to Result", "${timings["total_capture_to_result_ms"] ?: 0} ms (target < 4000 ms, max 10000 ms)")
                }
            }
        }
    }
}

@Composable
private fun DebugSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                color = Color(0xFF58A6FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = Color(0xFF21262D))
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun DebugDataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF8B949E),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = Color(0xFFF0F6FC),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}
