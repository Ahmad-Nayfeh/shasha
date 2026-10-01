package com.ahmadnayfeh.shasha.ui.components

import androidx.annotation.DrawableRes
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * The brand lockup (the icon and the «شاشة» wordmark) for the theme on screen: the wordmark is light on the dark
 * theme and dark on the light one. Read while composing, so a theme switch repaints it.
 */
@DrawableRes
fun brandLockup(): Int = if (ShashaColors.isLight) R.drawable.brand_lockup_light else R.drawable.splash_lockup
