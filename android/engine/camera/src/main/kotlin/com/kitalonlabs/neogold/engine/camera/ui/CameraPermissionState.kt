package com.kitalonlabs.neogold.engine.camera.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the app may use the camera, and how to ask. The activity owns the system permission request, so this module
 * needs no activity library: the app provides the state, and every screen that opens the camera reads it.
 *
 * @param request shows the system permission dialog (nothing happens if the user already refused twice)
 * @param openSettings opens this app's page in the system settings, where a refused permission can be changed
 */
class CameraPermissionState(
    val granted: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit
)

/** Provided by the app. Null means no host gave one, and the preview shows the camera as unavailable. */
val LocalCameraPermission = staticCompositionLocalOf<CameraPermissionState?> { null }
