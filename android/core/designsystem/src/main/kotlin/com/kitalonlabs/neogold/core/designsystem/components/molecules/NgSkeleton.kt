package com.kitalonlabs.neogold.core.designsystem.components.molecules

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Loading placeholders (doc 6 Section 3.3: a screen that is still reading its data says so by
 * showing the shape of that data, and nothing else moves).
 *
 * These are only for a wait that really happens: the log reading its first page out of the
 * encrypted database on a cold start. A screen that already holds its data must never show one
 * of these, because a placeholder over instant local data is friction, not polish.
 *
 * The placeholders are hidden from TalkBack (`clearAndSetSemantics`) — a screen reader should
 * hear the list once it exists, not three invented rows. When the system animation scale is
 * zero (the "remove animations" accessibility setting) the shimmer stops and the blocks stay
 * static.
 */
@Composable
fun NgSkeletonList(
    modifier: Modifier = Modifier,
    itemCount: Int = 3
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(itemCount) {
            NgSkeletonRow()
        }
    }
}

@Composable
fun NgSkeletonRow(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NgSkeletonBlock(width = 116.dp, height = 12.dp)
            NgSkeletonBlock(width = 76.dp, height = 16.dp)
        }
        NgSkeletonBlock(width = 184.dp, height = 18.dp)
        NgSkeletonBlock(width = 96.dp, height = 12.dp)
    }
}

@Composable
fun NgSkeletonBlock(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val animate = rememberAnimationsEnabled()

    val progress = if (animate) {
        val transition = rememberInfiniteTransition(label = "ng_skeleton")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ng_skeleton_sweep"
        ).value
    } else {
        null
    }

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .drawBehind {
                drawRect(color = base)
                if (progress != null) {
                    // A single soft sweep, left to right, over a slightly darker base.
                    val sweep = size.width * 1.6f
                    val startX = -sweep + progress * (size.width + sweep * 2f)
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color.Transparent, highlight, Color.Transparent),
                            start = Offset(startX, 0f),
                            end = Offset(startX + sweep, 0f)
                        )
                    )
                }
            }
    )
}

/**
 * The system animation scale, which carries the "remove animations" accessibility setting.
 * Read once per composition: it changes at most when the user visits system settings.
 */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        }.getOrDefault(true)
    }
}
