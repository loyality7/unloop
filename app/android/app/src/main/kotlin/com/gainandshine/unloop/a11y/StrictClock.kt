package com.gainandshine.unloop.a11y

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object StrictClock {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm")
    fun hm(epochMs: Long): String = fmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
