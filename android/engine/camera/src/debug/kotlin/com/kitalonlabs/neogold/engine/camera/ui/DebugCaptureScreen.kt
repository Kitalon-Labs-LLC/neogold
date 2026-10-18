package com.kitalonlabs.neogold.engine.camera.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitalonlabs.neogold.engine.camera.LiveCardState
import com.kitalonlabs.neogold.engine.vision.QualityGateResult
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode

/**
 * Debug-only capture screen showing live detection overlay, homography, and quality gate reasons.
 * Excluded from release builds by placement in src/debug source set.
 */
@Composable
fun DebugCaptureScreen(
    liveCardState: LiveCardState,
    latestQualityResult: QualityGateResult?,
    onCaptureTriggered: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showRawDetails by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121417))
    ) {
        // Base detection overlay
        CardDetectionOverlay(
            state = liveCardState,
            onManualCapture = onCaptureTriggered,
            onHelpClicked = { showRawDetails = !showRawDetails }
        )

        // Debug HUD overlay at top
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xCC000000)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF0B5C68), RoundedCornerShape(8.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DEBUG CAPTURE HUD (NOT IN RELEASE)",
                            color = Color(0xFF64B5F6),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Button(
                            onClick = onClose,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Close", fontSize = 11.sp, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = Color(0x40FFFFFF))
                    Spacer(modifier = Modifier.height(6.dp))

                    // Markers and Angle
                    DebugMetricRow("Fiducial Markers", "${liveCardState.detectedMarkerCount} / 4")
                    DebugMetricRow("Card Angle (Tilt)", "%.1f° (max 20°)".format(liveCardState.cardAngleDegrees))
                    DebugMetricRow("Steady Progress", "%.0f%%".format(liveCardState.steadyFraction * 100f))

                    // Quality Gate Metrics if available
                    if (latestQualityResult != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = Color(0x40FFFFFF))
                        Spacer(modifier = Modifier.height(4.dp))

                        val m = latestQualityResult.metrics
                        DebugMetricRow("Laplacian Blur", "%.1f (min 100)".format(m.blurLaplacianVariance))
                        DebugMetricRow("Glare Fraction", "%.2f%% (max 2.0%%)".format(m.glarePixelFraction * 100.0))
                        DebugMetricRow("Mid-Gray Lum", "%.1f (40..215)".format(m.midGrayLuminance))
                        DebugMetricRow("Duplicate Patch ΔE", "%.2f (max 2.0)".format(m.duplicatePatchDeltaE))
                        DebugMetricRow("Residual ΔE", "%.2f (max 3.0)".format(m.meanCorrectionResidualDeltaE))

                        Spacer(modifier = Modifier.height(4.dp))
                        // Active Quality Failures
                        if (latestQualityResult.failureReasons.isEmpty()) {
                            Text(
                                text = "QUALITY STATUS: PASSED",
                                color = Color(0xFF4CAF50),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text(
                                text = "FAILURES: ${latestQualityResult.failureReasons.joinToString { it.name }}",
                                color = Color(0xFFEF5350),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color(0xFFB0BEC5),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}
