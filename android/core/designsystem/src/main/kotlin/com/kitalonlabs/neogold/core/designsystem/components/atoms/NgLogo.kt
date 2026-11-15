package com.kitalonlabs.neogold.core.designsystem.components.atoms

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitalonlabs.neogold.core.designsystem.theme.LightPrimary

/**
 * Official NeoGold Brand Logo Component.
 *
 * Combines:
 * 1. Deep Medical Teal (#0B5C68) squircle base
 * 2. Cyan (#38BDF8) precision optical calibration corner brackets
 * 3. Radiant Warm Gold (#F59E0B) camera aperture & newborn life silhouette
 */
@Composable
fun NgLogoIcon(
    size: Dp = 64.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val scale = w / 108f

        // Squircle background
        drawRoundRect(
            color = Color(0xFF0B5C68),
            topLeft = Offset(w * 0.05f, h * 0.05f),
            size = Size(w * 0.9f, h * 0.9f),
            cornerRadius = CornerRadius(w * 0.22f, h * 0.22f)
        )

        // Calibration corner brackets
        val cyan = Color(0xFF38BDF8)
        val bracketStroke = Stroke(width = 2.5f * scale, cap = StrokeCap.Round)

        // Top-Left
        val tl = Path().apply {
            moveTo(30f * scale, 42f * scale)
            lineTo(30f * scale, 34f * scale)
            quadraticTo(30f * scale, 30f * scale, 34f * scale, 30f * scale)
            lineTo(42f * scale, 30f * scale)
        }
        drawPath(tl, cyan, style = bracketStroke)

        // Top-Right
        val tr = Path().apply {
            moveTo(66f * scale, 30f * scale)
            lineTo(74f * scale, 30f * scale)
            quadraticTo(78f * scale, 30f * scale, 78f * scale, 34f * scale)
            lineTo(78f * scale, 42f * scale)
        }
        drawPath(tr, cyan, style = bracketStroke)

        // Bottom-Left
        val bl = Path().apply {
            moveTo(30f * scale, 66f * scale)
            lineTo(30f * scale, 74f * scale)
            quadraticTo(30f * scale, 78f * scale, 34f * scale, 78f * scale)
            lineTo(42f * scale, 78f * scale)
        }
        drawPath(bl, cyan, style = bracketStroke)

        // Bottom-Right
        val br = Path().apply {
            moveTo(66f * scale, 78f * scale)
            lineTo(74f * scale, 78f * scale)
            quadraticTo(78f * scale, 78f * scale, 78f * scale, 74f * scale)
            lineTo(78f * scale, 66f * scale)
        }
        drawPath(br, cyan, style = bracketStroke)

        // Golden Aperture Ring
        val gold = Color(0xFFF59E0B)
        drawCircle(
            color = gold,
            radius = 16f * scale,
            center = Offset(54f * scale, 54f * scale),
            style = Stroke(width = 2f * scale)
        )

        // Aperture Blades
        val lightGold = Color(0xFFFBBF24)
        val bladeStroke = Stroke(width = 1.2f * scale, cap = StrokeCap.Round)
        drawLine(lightGold, Offset(54f * scale, 38f * scale), Offset(65f * scale, 45f * scale), strokeWidth = 1.2f * scale)
        drawLine(lightGold, Offset(68f * scale, 46f * scale), Offset(70f * scale, 59f * scale), strokeWidth = 1.2f * scale)
        drawLine(lightGold, Offset(68f * scale, 62f * scale), Offset(58f * scale, 69f * scale), strokeWidth = 1.2f * scale)
        drawLine(lightGold, Offset(54f * scale, 70f * scale), Offset(43f * scale, 63f * scale), strokeWidth = 1.2f * scale)
        drawLine(lightGold, Offset(40f * scale, 62f * scale), Offset(38f * scale, 49f * scale), strokeWidth = 1.2f * scale)
        drawLine(lightGold, Offset(40f * scale, 46f * scale), Offset(50f * scale, 39f * scale), strokeWidth = 1.2f * scale)

        // Newborn silhouette
        drawCircle(
            color = Color(0xFFFDE047),
            radius = 4.5f * scale,
            center = Offset(58f * scale, 53.5f * scale)
        )

        val swaddle = Path().apply {
            moveTo(55f * scale, 56f * scale)
            cubicTo(52f * scale, 57f * scale, 48f * scale, 60f * scale, 48f * scale, 63f * scale)
            cubicTo(48f * scale, 67f * scale, 54f * scale, 68f * scale, 59f * scale, 67f * scale)
            cubicTo(63f * scale, 65f * scale, 65f * scale, 62f * scale, 64f * scale, 59f * scale)
        }
        drawPath(swaddle, gold, style = Stroke(width = 2f * scale, cap = StrokeCap.Round))
    }
}

/**
 * Horizontal Full Logo with Logomark and Typography.
 */
@Composable
fun NgLogoHeader(
    modifier: Modifier = Modifier,
    iconSize: Dp = 48.dp
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NgLogoIcon(size = iconSize)
        Column {
            Row {
                Text(
                    text = "Neo",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = LightPrimary,
                    fontSize = 24.sp
                )
                Text(
                    text = "Gold",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD97706),
                    fontSize = 24.sp
                )
            }
            Text(
                text = "Newborn Jaundice Care",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}
