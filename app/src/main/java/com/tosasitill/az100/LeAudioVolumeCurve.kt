package com.tosasitill.az100

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Logical slider and headset VCS values are separate domains (both 0..255). */
internal object LeAudioVolumeCurve {
    fun toHeadset(logical: Int): Int {
        val value = logical.coerceIn(0, 255)
        if (value == 0) return 0
        return (value * value / 255.0).roundToInt().coerceAtLeast(1)
    }

    fun toLogical(headset: Int): Int {
        val value = headset.coerceIn(0, 255)
        return sqrt(value * 255.0).roundToInt()
    }
}
