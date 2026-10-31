package com.kitalonlabs.neogold.feature.research.ui

import androidx.annotation.StringRes

/** One site of the research capture protocol (FR-27): where the card goes and what to say about it. */
data class ProtocolStep(
    val siteCode: String,
    @StringRes val siteNameRes: Int,
    @StringRes val guidanceRes: Int,
    val isFlash: Boolean
)
