package com.gainandshine.unloop.core

import com.gainandshine.unloop.core.model.Rect
import kotlin.test.Test
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun rectBasics() {
        assertTrue(Rect(0, 0, 100, 100).intersects(Rect(50, 50, 150, 150)))
    }
}
