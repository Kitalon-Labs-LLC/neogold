@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.kitalonlabs.neogold.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kitalonlabs.neogold.core.designsystem.R

/**
 * NeoGold Typography according to doc 6 Section 2.2.
 * Noto Sans and Noto Sans Bengali are bundled (SIL OFL 1.1) so rendering does not depend on the fonts a low-cost
 * phone ships with. Both are variable fonts, so each weight is one file plus its `wght` setting.
 *
 * Line height for body and question is >= 1.5 to provide sufficient vertical space
 * for Bengali vowel signs and conjuncts (doc 6 Section 2.2).
 */
private fun notoFont(resId: Int, weight: Int): Font = Font(
    resId = resId,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

/**
 * Latin first, then Bengali: a Bangla glyph missing from Noto Sans falls through to Noto Sans Bengali, and a Latin
 * glyph in Bangla text falls back the same way, so one family covers both languages.
 */
val NeoGoldFontFamily: FontFamily = FontFamily(
    notoFont(R.font.noto_sans, 400),
    notoFont(R.font.noto_sans, 500),
    notoFont(R.font.noto_sans, 600),
    notoFont(R.font.noto_sans, 700),
    notoFont(R.font.noto_sans_bengali, 400),
    notoFont(R.font.noto_sans_bengali, 500),
    notoFont(R.font.noto_sans_bengali, 600),
    notoFont(R.font.noto_sans_bengali, 700),
)

val NeoGoldTypography = Typography(
    // Result action: 28 sp, Bold, Line height 1.3 (36.4 sp)
    headlineLarge = TextStyle(
        fontFamily = NeoGoldFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = (28 * 1.3).sp
    ),
    // Screen title: 22 sp, SemiBold, Line height 1.35 (29.7 sp)
    headlineMedium = TextStyle(
        fontFamily = NeoGoldFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = (22 * 1.35).sp
    ),
    // Question text: 20 sp, Medium, Line height 1.5 (30.0 sp)
    titleLarge = TextStyle(
        fontFamily = NeoGoldFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = (20 * 1.5).sp
    ),
    // Body: 16 sp (Bangla 17 sp handled by locale or standard 16.5 sp), Regular, Line height 1.5 (24 sp)
    bodyLarge = TextStyle(
        fontFamily = NeoGoldFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = (16 * 1.5).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = NeoGoldFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = (14 * 1.5).sp
    ),
    // Label, hint: 14 sp, Medium, Line height 1.4 (19.6 sp)
    labelLarge = TextStyle(
        fontFamily = NeoGoldFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = (14 * 1.4).sp
    ),
    // Version footer: 12 sp, Regular, Line height 1.4 (16.8 sp)
    labelSmall = TextStyle(
        fontFamily = NeoGoldFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = (12 * 1.4).sp
    )
)
