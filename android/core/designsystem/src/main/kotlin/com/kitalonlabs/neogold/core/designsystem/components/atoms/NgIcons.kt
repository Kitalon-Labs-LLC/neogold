package com.kitalonlabs.neogold.core.designsystem.components.atoms

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * NeoGold Material Symbols Rounded Vector Icons (doc 6 Section 2.4).
 * Handcrafted vector paths avoiding massive material-icons-extended footprint (keeps APK < 25 MB).
 */
object NgIcons {

    /**
     * Octagon warning icon for "Refer urgently now"
     */
    val WarningOctagon: ImageVector by lazy {
        ImageVector.Builder(
            name = "WarningOctagon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(8.27f, 3f)
            lineTo(15.73f, 3f)
            lineTo(21f, 8.27f)
            lineTo(21f, 15.73f)
            lineTo(15.73f, 21f)
            lineTo(8.27f, 21f)
            lineTo(3f, 15.73f)
            lineTo(3f, 8.27f)
            close()
            moveTo(12f, 17f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
            reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
            reflectiveCurveToRelative(-1f, 0.45f, -1f, 1f)
            reflectiveCurveToRelative(0.45f, 1f, 1f, 1f)
            close()
            moveTo(11f, 7f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
            reflectiveCurveToRelative(1f, -0.45f, 1f, -1f)
            verticalLineTo(7f)
            curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
            reflectiveCurveToRelative(-1f, 0.45f, -1f, 1f)
            close()
        }.build()
    }

    /**
     * Blood drop icon for "Blood test today"
     */
    val BloodDrop: ImageVector by lazy {
        ImageVector.Builder(
            name = "BloodDrop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(12f, 2.69f)
            curveToRelative(-0.38f, 0.52f, -6.5f, 8.97f, -6.5f, 13.31f)
            curveTo(5.5f, 19.59f, 8.41f, 22.5f, 12f, 22.5f)
            reflectiveCurveToRelative(6.5f, -2.91f, 6.5f, -6.5f)
            curveToRelative(0f, -4.34f, -6.12f, -12.79f, -6.5f, -13.31f)
            close()
            moveTo(12f, 19.5f)
            curveToRelative(-2.21f, 0f, -4f, -1.79f, -4f, -4f)
            curveToRelative(0f, -2.18f, 2.76f, -6.64f, 4f, -8.4f)
            curveToRelative(1.24f, 1.76f, 4f, 6.22f, 4f, 8.4f)
            curveToRelative(0f, 2.21f, -1.79f, 4f, -4f, 4f)
            close()
        }.build()
    }

    /**
     * Clock icon for "Screen again in N hours"
     */
    val Clock: ImageVector by lazy {
        ImageVector.Builder(
            name = "Clock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(11.99f, 2f)
            curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            reflectiveCurveTo(17.52f, 2f, 11.99f, 2f)
            close()
            moveTo(12f, 20f)
            curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f)
            reflectiveCurveToRelative(3.58f, -8f, 8f, -8f)
            reflectiveCurveToRelative(8f, 3.58f, 8f, 8f)
            reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f)
            close()
            moveTo(12.5f, 7f)
            horizontalLineTo(11f)
            verticalLineToRelative(6f)
            lineToRelative(5.25f, 3.15f)
            lineToRelative(0.75f, -1.23f)
            lineToRelative(-4.5f, -2.67f)
            close()
        }.build()
    }

    /**
     * Eye icon for "No further action"
     */
    val Eye: ImageVector by lazy {
        ImageVector.Builder(
            name = "Eye",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(12f, 4.5f)
            curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
            curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
            reflectiveCurveToRelative(9.27f, -3.11f, 11f, -7.5f)
            curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
            close()
            moveTo(12f, 17f)
            curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
            reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
            reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
            reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
            close()
            moveTo(12f, 9f)
            curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
            reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
            reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
            reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
            close()
        }.build()
    }

    /**
     * Block icon for "Cannot screen"
     */
    val Block: ImageVector by lazy {
        ImageVector.Builder(
            name = "Block",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveTo(4f, 12f)
            curveToRelative(0f, -1.93f, 0.69f, -3.7f, 1.83f, -5.09f)
            lineTo(17.09f, 18.17f)
            curveTo(15.7f, 19.31f, 13.93f, 20f, 12f, 20f)
            curveToRelative(-4.41f, 0f, -8f, -3.59f, -8f, -8f)
            close()
            moveTo(18.17f, 17.09f)
            lineTo(6.91f, 5.83f)
            curveTo(8.3f, 4.69f, 10.07f, 4f, 12f, 4f)
            curveToRelative(4.41f, 0f, 8f, 3.59f, 8f, 8f)
            curveToRelative(0f, 1.93f, -0.69f, 3.7f, -1.83f, 5.09f)
            close()
        }.build()
    }

    /**
     * Science icon for experimental banner
     */
    val Science: ImageVector by lazy {
        ImageVector.Builder(
            name = "Science",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(19.8f, 18.4f)
            lineTo(14f, 9.5f)
            verticalLineTo(4f)
            horizontalLineToRelative(1f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
            reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
            horizontalLineTo(9f)
            curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
            reflectiveCurveToRelative(0.45f, 1f, 1f, 1f)
            horizontalLineToRelative(1f)
            verticalLineToRelative(5.5f)
            lineTo(4.2f, 18.4f)
            curveTo(3.43f, 19.58f, 4.28f, 21.16f, 5.7f, 21.16f)
            horizontalLineToRelative(12.6f)
            curveToRelative(1.42f, 0f, 2.27f, -1.58f, 1.5f, -2.76f)
            close()
            moveTo(6.72f, 19f)
            lineTo(11f, 12.44f)
            verticalLineTo(4f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(8.44f)
            lineTo(17.28f, 19f)
            horizontalLineTo(6.72f)
            close()
        }.build()
    }

    /**
     * Cloud upload icon for sync status
     */
    val CloudUpload: ImageVector by lazy {
        ImageVector.Builder(
            name = "CloudUpload",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
            curveToRelative(0f, 3.31f, 2.69f, 6f, 6f, 6f)
            horizontalLineToRelative(13f)
            curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f)
            curveToRelative(0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
            close()
            moveTo(14f, 13f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(-4f)
            verticalLineToRelative(-4f)
            horizontalLineTo(7f)
            lineToRelative(5f, -5f)
            lineToRelative(5f, 5f)
            horizontalLineToRelative(-3f)
            close()
        }.build()
    }

    /**
     * Check circle icon
     */
    val CheckCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "CheckCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveTo(10f, 17f)
            lineToRelative(-5f, -5f)
            lineToRelative(1.41f, -1.41f)
            lineTo(10f, 14.17f)
            lineToRelative(7.59f, -7.59f)
            lineTo(19f, 8f)
            lineToRelative(-9f, 9f)
            close()
        }.build()
    }

    /**
     * Cancel / Close circle icon
     */
    val CancelCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "CancelCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(12f, 2f)
            curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.47f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.53f, 2f, 12f, 2f)
            close()
            moveTo(17f, 15.59f)
            lineTo(15.59f, 17f)
            lineTo(12f, 13.41f)
            lineTo(8.41f, 17f)
            lineTo(7f, 15.59f)
            lineTo(10.59f, 12f)
            lineTo(7f, 8.41f)
            lineTo(8.41f, 7f)
            lineTo(12f, 10.59f)
            lineTo(15.59f, 7f)
            lineTo(17f, 8.41f)
            lineTo(13.41f, 12f)
            lineTo(17f, 15.59f)
            close()
        }.build()
    }

    /**
     * Danger sign: Feeding icon
     */
    val DangerFeeding: ImageVector by lazy {
        ImageVector.Builder(
            name = "DangerFeeding",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveTo(12f, 6f)
            curveToRelative(1.66f, 0f, 3f, 1.34f, 3f, 3f)
            reflectiveCurveToRelative(-1.34f, 3f, -3f, 3f)
            reflectiveCurveToRelative(-3f, -1.34f, -3f, -3f)
            reflectiveCurveToRelative(1.34f, -3f, 3f, -3f)
            close()
            moveTo(12f, 18.5f)
            curveToRelative(-2.5f, 0f, -4.71f, -1.28f, -6f, -3.22f)
            curveToRelative(0.03f, -1.99f, 4f, -3.08f, 6f, -3.08f)
            curveToRelative(1.99f, 0f, 5.97f, 1.09f, 6f, 3.08f)
            curveToRelative(-1.29f, 1.94f, -3.5f, 3.22f, -6f, 3.22f)
            close()
        }.build()
    }

    /**
     * Danger sign: Sleepy icon
     */
    val DangerSleepy: ImageVector by lazy {
        ImageVector.Builder(
            name = "DangerSleepy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveTo(17f, 13f)
            horizontalLineTo(7f)
            curveToRelative(-0.55f, 0f, -1f, -0.45f, -1f, -1f)
            reflectiveCurveToRelative(0.45f, -1f, 1f, -1f)
            horizontalLineToRelative(10f)
            curveToRelative(0.55f, 0f, 1f, 0.45f, 1f, 1f)
            reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
            close()
        }.build()
    }

    /**
     * Danger sign: High pitched cry icon
     */
    val DangerCry: ImageVector by lazy {
        ImageVector.Builder(
            name = "DangerCry",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(3f, 9f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(4f)
            lineToRelative(5f, 5f)
            verticalLineTo(4f)
            lineTo(7f, 9f)
            horizontalLineTo(3f)
            close()
            moveTo(16.5f, 12f)
            curveToRelative(0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
            verticalLineToRelative(8.05f)
            curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
            close()
            moveTo(14f, 3.23f)
            verticalLineToRelative(2.06f)
            curveToRelative(2.89f, 0.86f, 5f, 3.54f, 5f, 6.71f)
            reflectiveCurveToRelative(-2.11f, 5.85f, -5f, 6.71f)
            verticalLineToRelative(2.06f)
            curveToRelative(4.01f, -0.91f, 7f, -4.49f, 7f, -8.77f)
            reflectiveCurveToRelative(-2.99f, -7.86f, -7f, -8.77f)
            close()
        }.build()
    }

    /**
     * Danger sign: Arching icon
     */
    val DangerArching: ImageVector by lazy {
        ImageVector.Builder(
            name = "DangerArching",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.White)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveTo(12f, 18f)
            curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
            curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
            reflectiveCurveToRelative(1f, 0.45f, 1f, 1f)
            curveToRelative(0f, 2.21f, 1.79f, 4f, 4f, 4f)
            reflectiveCurveToRelative(4f, -1.79f, 4f, -4f)
            curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
            reflectiveCurveToRelative(1f, 0.45f, 1f, 1f)
            curveToRelative(0f, 3.31f, -2.69f, 6f, -6f, 6f)
            close()
        }.build()
    }
}
