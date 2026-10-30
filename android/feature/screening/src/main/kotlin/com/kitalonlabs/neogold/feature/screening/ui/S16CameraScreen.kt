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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.engine.camera.LiveCardState
import com.kitalonlabs.neogold.engine.camera.host.CameraMode
import com.kitalonlabs.neogold.engine.camera.host.CameraSession
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.camera.ui.CameraPreviewHost
import com.kitalonlabs.neogold.engine.camera.ui.CardDetectionOverlay
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Neutral dark gray interface per doc 6 Section 9.1
private val DarkScreenBg = Color(0xFF121416)

/**
 * S16 Camera Screen: live card detection over the real camera, and the photo (doc 6 Section 9; neutral dark gray, no colored
 * elements near the baby). The photo is taken when the card has been steady for a second, or when the worker taps capture.
 * The frame goes to the caller in memory; nothing is written to disk (doc 8 Section 2).
 *
 * There is no stand-in frame: if the camera cannot be opened the preview host says so and no photo is taken (BUG-2026-003).
 */
@Composable
fun S16CameraScreen(
    onCaptureCompleted: (frame: ImageFrame, metadata: CaptureMetadata) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var liveState by remember { mutableStateOf(LiveCardState.IDLE) }
    var session by remember { mutableStateOf<CameraSession?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // One burst at a time; the sharpest of its frames is the photo
    val capture = {
        val open = session
        if (open != null && !capturing) {
            capturing = true
            scope.launch {
                try {
                    val burst = open.captureBurst()
                    onCaptureCompleted(burst.selectedFrame, burst.selectedMetadata)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // the camera could not take the photo; the worker can tap again
                    capturing = false
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkScreenBg)
    ) {
        CameraPreviewHost(
            mode = CameraMode.LiveCard(onState = { liveState = it }, onAutoCapture = { capture() }),
            onSession = { session = it },
            modifier = Modifier.fillMaxSize()
        )

        CardDetectionOverlay(
            state = liveState,
            onManualCapture = { capture() },
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
                contentDescription = stringResource(R.string.btn_back),
                tint = Color.White
            )
        }
    }
}
