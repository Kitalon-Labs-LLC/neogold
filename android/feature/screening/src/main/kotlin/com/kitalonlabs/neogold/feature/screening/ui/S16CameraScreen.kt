package com.kitalonlabs.neogold.feature.screening.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.engine.camera.LiveCardState
import com.kitalonlabs.neogold.engine.camera.ui.CardDetectionOverlay
import com.kitalonlabs.neogold.engine.vision.ImageFrame

// Neutral dark gray interface per doc 6 Section 9.1
private val DarkScreenBg = Color(0xFF121416)
private val DarkOverlayChrome = Color(0xFF1E2124)

/**
 * S16 Camera Screen for live card detection and capture.
 * Follows doc 6 Section 9 (neutral dark gray interface only; no colored elements near baby).
 */
@Composable
fun S16CameraScreen(
    onCaptureCompleted: (ImageFrame) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialLiveCardState: LiveCardState = LiveCardState.IDLE,
    simulatedFrameOnCapture: ImageFrame? = null
) {
    var liveState by remember { mutableStateOf(initialLiveCardState) }

    LaunchedEffect(liveState.readyForCapture) {
        if (liveState.readyForCapture) {
            val frame = simulatedFrameOnCapture ?: createDefaultCaptureFrame()
            onCaptureCompleted(frame)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkScreenBg)
    ) {
        // Camera preview guide overlay
        CardDetectionOverlay(
            state = liveState,
            onManualCapture = {
                val frame = simulatedFrameOnCapture ?: createDefaultCaptureFrame()
                onCaptureCompleted(frame)
            },
            onHelpClicked = { /* Optional help tooltip */ },
            modifier = Modifier.fillMaxSize()
        )

        // Back button in neutral gray
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 40.dp)
                .size(48.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
}

/**
 * Creates a minimal default [ImageFrame] for simulation/fallback in testing and preview.
 */
private fun createDefaultCaptureFrame(): ImageFrame {
    val width = 1280
    val height = 720
    val data = IntArray(width * height) { 0xFF808080.toInt() }
    return ImageFrame(width, height, data)
}
