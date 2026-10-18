package com.kitalonlabs.neogold.engine.camera.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitalonlabs.neogold.engine.camera.LiveCardState

// Neutral dark gray tokens per doc 6 Section 9 (no colored chrome near the baby)
private val DarkChromeBg = Color(0xFF1E2124)
private val NeutralTextPrimary = Color(0xFFE6E8EA)
private val NeutralTextSecondary = Color(0xFFA0A6AD)
private val OverlayGuideNeutral = Color(0x99FFFFFF)
private val OverlayActiveMarker = Color(0xFFFFFFFF)
private val OverlayProgressTrack = Color(0x40FFFFFF)
private val OverlayProgressFill = Color(0xFFE2E8F0)

/**
 * Camera preview guide overlay.
 * Neutral dark gray interface per doc 6 Section 9 (no colored elements while camera is open).
 * S16 wireframe implementation.
 */
@Composable
fun CardDetectionOverlay(
    state: LiveCardState,
    onManualCapture: () -> Unit,
    onHelpClicked: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.steadyFraction,
        label = "steadyProgress"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // Guide box and corner markers
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            // Target card aspect ratio (1200 x 760 ~ 1.58:1)
            val guideW = canvasW * 0.82f
            val guideH = guideW / 1.579f
            val guideLeft = (canvasW - guideW) / 2f
            val guideTop = (canvasH - guideH) * 0.40f

            // Outer card frame
            drawRoundRect(
                color = OverlayGuideNeutral,
                topLeft = Offset(guideLeft, guideTop),
                size = Size(guideW, guideH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f),
                style = Stroke(width = 3.dp.toPx())
            )

            // Inner skin window guide (at canonical x=360..600, y=260..500)
            val windowW = guideW * (240f / 1200f)
            val windowH = guideH * (240f / 760f)
            val windowLeft = guideLeft + guideW * (360f / 1200f)
            val windowTop = guideTop + guideH * (260f / 760f)

            drawRoundRect(
                color = OverlayGuideNeutral.copy(alpha = 0.5f),
                topLeft = Offset(windowLeft, windowTop),
                size = Size(windowW, windowH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                style = Stroke(width = 2.dp.toPx())
            )

            // Draw 4 corner marker circles (fill in as detected)
            val markerRadius = 14.dp.toPx()
            val cornerOffsets = listOf(
                0 to Offset(guideLeft + 24.dp.toPx(), guideTop + 24.dp.toPx()), // TL
                1 to Offset(guideLeft + guideW - 24.dp.toPx(), guideTop + 24.dp.toPx()), // TR
                2 to Offset(guideLeft + guideW - 24.dp.toPx(), guideTop + guideH - 24.dp.toPx()), // BR
                3 to Offset(guideLeft + 24.dp.toPx(), guideTop + guideH - 24.dp.toPx())  // BL
            )

            for ((id, offset) in cornerOffsets) {
                val isDetected = state.detectedMarkers.containsKey(id)
                if (isDetected) {
                    drawCircle(
                        color = OverlayActiveMarker,
                        radius = markerRadius,
                        center = offset
                    )
                } else {
                    drawCircle(
                        color = OverlayGuideNeutral,
                        radius = markerRadius,
                        center = offset,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        // Bottom control chrome
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(DarkChromeBg)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val statusText = when {
                state.isSteadyForOneSecond -> "Capturing..."
                state.allFourFound -> "Hold the phone steady, 20 cm away"
                state.detectedMarkerCount > 0 -> "Position all 4 corners (${state.detectedMarkerCount}/4 found)"
                else -> "Position the card inside the frame"
            }

            Text(
                text = statusText,
                color = NeutralTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Steady bar
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(6.dp),
                color = OverlayProgressFill,
                trackColor = OverlayProgressTrack,
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Manual capture fallback button (doc 6 S16)
                Button(
                    onClick = onManualCapture,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF33383F),
                        contentColor = NeutralTextPrimary
                    )
                ) {
                    Text("Manual capture", fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.width(16.dp))

                OutlinedButton(
                    onClick = onHelpClicked,
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeutralTextSecondary)
                ) {
                    Text("?", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
