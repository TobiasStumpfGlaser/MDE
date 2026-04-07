package com.example.mde

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

object LayoutScaleUtil {

    /**
     * Skaliert die dp-Density dieser Activity (Layout-Größen wie 70dp, 24dp padding, usw.).
     * Muss VOR super.onCreate() / setContentView() ausgeführt werden.
     */
    fun applyLayoutScale(baseContext: Context, scale: Float): Context {
        val clamped = scale.coerceIn(0.85f, 1.30f)

        val res = baseContext.resources
        val config = Configuration(res.configuration)

        val currentDpi = config.densityDpi
        val newDpi = (currentDpi * clamped).roundToInt().coerceAtLeast(120)

        config.densityDpi = newDpi

        return baseContext.createConfigurationContext(config)
    }
}