package com.tosasitill.az100

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeAudioVolumeCurveTest {
    @Test
    fun quietMediaLevelsAreReducedWithoutLosingMuteOrMaximum() {
        assertEquals(0, LeAudioVolumeCurve.toHeadset(0))
        assertEquals(1, LeAudioVolumeCurve.toHeadset(1))
        assertEquals(10, LeAudioVolumeCurve.toHeadset(51)) // slider 20%
        assertEquals(64, LeAudioVolumeCurve.toHeadset(128)) // slider 50%
        assertEquals(255, LeAudioVolumeCurve.toHeadset(255))
        assertEquals(0, LeAudioVolumeCurve.toLogical(0))
        assertEquals(255, LeAudioVolumeCurve.toLogical(255))
    }

    @Test
    fun allSliderStepsStayMonotonicAndNeverAmplify() {
        var previous = 0
        for (logical in 1..255) {
            val actual = LeAudioVolumeCurve.toHeadset(logical)
            assertTrue(actual in 1..logical)
            assertTrue(actual >= previous)
            previous = actual
        }
    }

    @Test
    fun headsetReportsDoNotApplyTheCurveTwice() {
        for (actual in 0..255) {
            val logical = LeAudioVolumeCurve.toLogical(actual)
            val restored = LeAudioVolumeCurve.toHeadset(logical)
            assertTrue("report=$actual logical=$logical restored=$restored", abs(restored - actual) <= 1)
        }
    }
}
