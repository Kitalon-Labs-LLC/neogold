package com.kitalonlabs.neogold.core.designsystem.components.organisms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.theme.CameraScreenOverlay

/**
 * NgCaptureOverlay (doc 6 Section 2.1, Section 4.3, Section 7.3).
 * Neutral dark gray chrome (#2B2F33) with white text.
 * No colored elements near the baby while capturing (Rule 9).
 */
@Composable
fun NgCaptureOverlay(
    markersDetectedCount: Int,
    stabilityProgress: Float,
    statusMessage: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // Card Outline Guide (1200 x 760 canonical aspect ratio approx 1.58:1)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .aspectRatio(1.58f)
                .align(Alignment.Center)
                .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
        ) {
            // Four corner marker indicators
            CornerMarkerIndicator(
                isDetected = markersDetectedCount >= 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
            CornerMarkerIndicator(
                isDetected = markersDetectedCount >= 2,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
            CornerMarkerIndicator(
                isDetected = markersDetectedCount >= 3,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            )
            CornerMarkerIndicator(
                isDetected = markersDetectedCount >= 4,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            )

            // Center punched window indicator
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .align(Alignment.Center)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            )
        }

        // Bottom status & steadiness indicator (Neutral dark gray)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(CameraScreenOverlay)
                .padding(16.dp)
        ) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { stabilityProgress.coerceIn(0f, 1f) },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun CornerMarkerIndicator(
    isDetected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(if (isDetected) Color.White else Color.Transparent)
            .border(1.5.dp, Color.White, CircleShape)
    )
}
