package com.kitalonlabs.neogold.engine.camera.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kitalonlabs.neogold.engine.camera.R
import com.kitalonlabs.neogold.engine.camera.host.CameraHost
import com.kitalonlabs.neogold.engine.camera.host.CameraMode
import com.kitalonlabs.neogold.engine.camera.host.CameraSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

/**
 * The camera preview for a screen: asks for camera access when it is missing, opens the camera when it is granted,
 * and closes it when the screen leaves (doc 6 Section 8). The screen gets a [CameraSession] through [onSession] once
 * the camera is open, and null when it closes. Only NeoGold types are exposed.
 */
@Composable
fun CameraPreviewHost(
    mode: CameraMode,
    onSession: (CameraSession?) -> Unit,
    modifier: Modifier = Modifier
) {
    val permission = LocalCameraPermission.current
    val currentMode by rememberUpdatedState(mode)
    val currentOnSession by rememberUpdatedState(onSession)
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val granted = permission?.granted == true

    LaunchedEffect(permission) {
        if (permission != null && !permission.granted) permission.request()
    }

    Box(modifier = modifier.testTag("camera_preview_host")) {
        when {
            !granted -> Message(
                title = stringResource(R.string.camera_permission_title),
                body = stringResource(R.string.camera_permission_body),
                primaryLabel = stringResource(R.string.camera_permission_allow),
                onPrimary = { permission?.request?.invoke() },
                secondaryLabel = stringResource(R.string.camera_permission_settings),
                onSecondary = { permission?.openSettings?.invoke() },
                tag = "camera_permission_needed"
            )
            failed -> Message(
                title = stringResource(R.string.camera_unavailable_title),
                body = stringResource(R.string.camera_unavailable_body),
                primaryLabel = stringResource(R.string.camera_unavailable_retry),
                onPrimary = {
                    failed = false
                    attempt++
                },
                tag = "camera_unavailable"
            )
            else -> {
                val previewView = remember {
                    PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                }
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                OpenCamera(
                    attempt = attempt,
                    open = { CameraHost(context).open(lifecycleOwner, previewView.surfaceProvider, currentMode) },
                    onSession = { currentOnSession(it) },
                    onFailed = { failed = true }
                )
            }
        }
    }
}

/** Opens the camera for as long as this is in the composition and closes it after. */
@Composable
@Suppress("TooGenericExceptionCaught") // any failure to open the camera is shown as "could not be opened"
private fun OpenCamera(
    attempt: Int,
    open: suspend () -> CameraSession,
    onSession: (CameraSession?) -> Unit,
    onFailed: () -> Unit
) {
    LaunchedEffect(attempt) {
        var session: CameraSession? = null
        try {
            session = open()
            onSession(session)
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onFailed()
        } finally {
            session?.close()
            onSession(null)
        }
    }
}

@Composable
private fun Message(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    tag: String,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().testTag("${tag}_primary")) {
            Text(text = primaryLabel)
        }
        if (secondaryLabel != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth().testTag("${tag}_secondary")) {
                Text(text = secondaryLabel)
            }
        }
    }
}
