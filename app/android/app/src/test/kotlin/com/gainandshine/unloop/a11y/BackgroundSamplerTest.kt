package com.gainandshine.unloop.a11y

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackgroundSamplerTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val grey = 0xFF888888.toInt()

    @Test fun aPlainBackgroundIsAccepted() {
        assertEquals(white, BackgroundSampler.agreed(List(8) { white }))
        assertEquals(white, BackgroundSampler.agreed(List(6) { white } + listOf(black, grey)), "a few stray pixels do not matter")
        assertEquals(white, BackgroundSampler.agreed(List(5) { white } + List(3) { black }))
    }

    @Test fun pointsThatDisagreeMeanContentSoNothingIsAnswered() {
        assertNull(BackgroundSampler.agreed(List(4) { white } + List(4) { black }))
        assertNull(BackgroundSampler.agreed(listOf(white, black, grey, white, black, grey, white, black)))
        assertNull(BackgroundSampler.agreed(emptyList()))
    }

    @Test fun fewerThanFivePointsCanNeverBeEnough() {
        assertNull(BackgroundSampler.agreed(List(4) { white }), "a button near the screen edge with too few readable points")
    }
}
