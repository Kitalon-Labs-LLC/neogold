package com.kitalonlabs.neogold.feature.onboarding

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgBadge
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcon
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgIcons
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgPrimaryButton
import com.kitalonlabs.neogold.core.designsystem.components.atoms.NgTextButton
import com.kitalonlabs.neogold.core.designsystem.theme.Dimens
import com.kitalonlabs.neogold.core.designsystem.util.ClinicalNumberFormatter
import com.kitalonlabs.neogold.engine.camera.device.DeviceChecker
import com.kitalonlabs.neogold.engine.camera.device.DeviceCompatibilityReport
import com.kitalonlabs.neogold.engine.camera.device.DeviceSupportStatus

/**
 * S05: Device check and battery settings (NG-004, NG-046, TC-DEV-10, doc 6 Section 5).
 *
 * Checks device RAM, camera hardware level, and whether the model is in the
 * tested device matrix. Displays brand-specific battery setup guidance.
 */
@Composable
fun S05DeviceCheckScreen(
    report: DeviceCompatibilityReport? = null,
    onContinue: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val actualReport = remember(report) {
        report ?: DeviceChecker.checkDevice(context)
    }

    var showSupportedDevicesDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Dimens.ScreenPadding)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.s05_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingLg))

            // Device Status Card
            DeviceSpecsCard(actualReport)

            Spacer(modifier = Modifier.height(Dimens.SpacingLg))

            // Battery Guidance Card
            BatteryGuidanceCard(actualReport)

            Spacer(modifier = Modifier.height(Dimens.SpacingMd))

            // Link to view supported device list
            NgTextButton(
                text = stringResource(R.string.s05_supported_devices_title),
                onClick = { showSupportedDevicesDialog = true },
                modifier = Modifier.testTag("btn_view_supported_devices")
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.SpacingLg)
        ) {
            NgPrimaryButton(
                text = stringResource(R.string.btn_continue),
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_device_check_continue")
            )
        }
    }

    if (showSupportedDevicesDialog) {
        SupportedDevicesDialog(onDismiss = { showSupportedDevicesDialog = false })
    }
}

@Composable
private fun DeviceSpecsCard(report: DeviceCompatibilityReport) {
    val isTested = report.supportStatus == DeviceSupportStatus.TESTED
    val statusText = if (isTested) {
        stringResource(R.string.s05_status_tested)
    } else {
        stringResource(R.string.s05_status_untested)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CardCornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Dimens.CardCornerRadius))
            .padding(Dimens.SpacingMd)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${report.capabilities.manufacturer} ${report.capabilities.deviceModel}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isTested) {
                    NgBadge(
                        text = statusText,
                        modifier = Modifier.testTag("badge_device_tested")
                    )
                } else {
                    NgBadge(
                        text = statusText,
                        modifier = Modifier.testTag("badge_device_untested")
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.SpacingSm))

            Text(
                text = stringResource(R.string.s05_ram, report.capabilities.ramMb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.SpacingXs))

            Text(
                text = stringResource(R.string.s05_camera_level, report.capabilities.cameraHardwareLevel.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BatteryGuidanceCard(report: DeviceCompatibilityReport) {
    val guidance = report.batteryGuidance
    val steps = guidance.stepsEn

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CardCornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Dimens.CardCornerRadius))
            .padding(Dimens.SpacingMd)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NgIcon(
                    imageVector = NgIcons.Clock,
                    contentDescription = stringResource(R.string.battery_steps_title),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(Dimens.SpacingSm))
                Text(
                    text = stringResource(R.string.battery_steps_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(Dimens.SpacingSm))

            for ((index, step) in steps.withIndex()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.SpacingXs),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "${ClinicalNumberFormatter.formatInteger(index + 1)}. ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportedDevicesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.s05_supported_devices_title),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Tested Matrix Models (doc 1 Section 13.5):",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(Dimens.SpacingSm))
                val devices = listOf(
                    "TECNO: Spark 10 Pro, Camon 20",
                    "Infinix: Hot 30 Play, Smart 8",
                    "itel: A60s, Vision 3",
                    "Xiaomi: Redmi 13C, Poco C65",
                    "Samsung: Galaxy A06, Galaxy A05",
                    "Walton: Primo S8"
                )
                devices.forEach { dev ->
                    Text(
                        text = "• $dev",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            NgTextButton(
                text = stringResource(R.string.btn_continue),
                onClick = onDismiss
            )
        }
    )
}
