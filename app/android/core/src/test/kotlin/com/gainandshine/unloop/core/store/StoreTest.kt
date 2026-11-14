package com.gainandshine.unloop.core.store

import com.gainandshine.unloop.core.limits.Extension
import com.gainandshine.unloop.core.limits.LimitRule
import com.gainandshine.unloop.core.time.Schedule
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private val sch = InMemorySchedulesRepository()
    private val lim = InMemoryLimitsRepository()
    private val set = InMemorySettingsRepository()

    private fun seed() {
        sch.upsert(Schedule("night", true, setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), LocalTime.of(22, 0), LocalTime.of(7, 0)))
        sch.upsert(Schedule("off", false, setOf(DayOfWeek.MONDAY), LocalTime.of(9, 0), LocalTime.of(17, 30)))
        lim.upsert(LimitRule("app.a", 3600, listOf(50, 80)))
        lim.upsert(LimitRule("group:social", 0, emptyList()))
        set.put("strict \"mode\"", "yes\nline2 \u00e9 \\")
        set.put("dayStart", "04:00")
    }

    @Test
    fun roundTripExportsEverything() {
        seed()
        val json = BackupCodec.export(sch, lim, set)
        val s2 = InMemorySchedulesRepository(); val l2 = InMemoryLimitsRepository(); val t2 = InMemorySettingsRepository()
        val r = BackupCodec.import(json, s2, l2, t2)
        assertTrue(r.ok)
        assertEquals(Triple(2, 2, 2), Triple(r.schedules, r.limits, r.settings))
        assertTrue(r.skipped.isEmpty())
        assertEquals(sch.all(), s2.all())
        assertEquals(lim.all(), l2.all())
        assertEquals(set.all(), t2.all())
        assertFalse(s2.all().first { it.id == "off" }.enabled) // disabled entries are exported
        assertTrue(json.startsWith("{\"version\":1,"))
    }

    @Test
    fun unknownKeysIgnoredAndDefaultsApplied() {
        val json = """{"version":1,"future":{"x":[1,2]},"schedules":[{"id":"a","enabled":true,"days":["MONDAY"],
            "start":"08:00","end":"09:00","color":"red"}],"limits":[{"target":"p","dailySeconds":60,"extra":null}],
            "settings":{"k":"v","n":5,"b":true}}"""
        val r = BackupCodec.import(json, sch, lim, set)
        assertTrue(r.ok)
        assertEquals(1, sch.all().size)
        assertEquals(listOf(80), lim.all().single().warnPercents)
        assertEquals(mapOf("k" to "v", "n" to "5", "b" to "true"), set.all())
    }

    @Test
    fun oneCorruptEntryDoesNotLoseTheRest() {
        val json = """{"version":1,"schedules":[
            {"id":"ok1","enabled":true,"days":["MONDAY"],"start":"08:00","end":"09:00"},
            {"id":"bad-time","enabled":true,"days":["MONDAY"],"start":"25:99","end":"09:00"},
            {"id":"bad-day","enabled":true,"days":["FUNDAY"],"start":"08:00","end":"09:00"},
            {"id":"same","enabled":true,"days":["MONDAY"],"start":"08:00","end":"08:00"},
            {"enabled":true},
            "garbage",
            {"id":"ok2","enabled":false,"days":[],"start":"10:00","end":"11:00"}],
            "limits":[{"target":"a","dailySeconds":"x"},{"target":"b","dailySeconds":10}],
            "settings":{"good":"1","bad":[1]}}"""
        val r = BackupCodec.import(json, sch, lim, set)
        assertTrue(r.ok)
        assertEquals(listOf("ok1", "ok2"), sch.all().map { it.id })
        assertEquals(listOf("b"), lim.all().map { it.target })
        assertEquals(mapOf("good" to "1"), set.all())
        assertEquals(7, r.skipped.size)
        assertTrue(r.skipped.any { it.startsWith("schedules[1]") })
    }

    @Test
    fun versionAndEnvelopeRules() {
        for (bad in listOf("""{"version":0}""", """{"schedules":[]}""", "[]", "{", "nonsense", """{"version":"1"}""", """{"version":1} x""")) {
            seed()
            val before = Triple(sch.all(), lim.all(), set.all())
            val r = BackupCodec.import(bad, sch, lim, set)
            assertFalse(r.ok, bad)
            assertTrue(r.error != null, bad)
            assertEquals(before, Triple(sch.all(), lim.all(), set.all()), bad)
        }
        assertTrue(BackupCodec.import("""{"version":2,"limits":[{"target":"n","dailySeconds":5}]}""", sch, lim, set).ok)
        assertEquals(1, lim.all().count { it.target == "n" })
    }

    @Test
    fun jsonBasics() {
        val v = mapOf("a" to listOf(1L, 2.5, null, true, "x\"\\\n\t\u0001"), "b" to emptyMap<String, Any?>())
        assertEquals(v, Json.parse(Json.write(v)))
        assertEquals("\u00e9A", Json.parse("\"\\u00e9\\u0041\""))
        assertEquals(-12L, Json.parse(" -12 "))
        assertEquals(1.5e3, Json.parse("1.5e3"))
        for (bad in listOf("", "{\"a\":}", "[1,]", "\"abc", "tru", "{'a':1}", "[1 2]", "\"\\q\"")) {
            assertFailsWith<JsonException>(bad) { Json.parse(bad) }
        }
    }

    @Test
    fun inMemoryFakes() {
        val ex = InMemoryExtensionsRepository()
        val t0 = Instant.parse("2026-01-05T08:00:00Z")
        ex.add(Extension("1", "a", t0, 60, t0.plusSeconds(100)))
        ex.add(Extension("2", "a", t0, 60, t0.plusSeconds(300)))
        ex.add(Extension("2", "a", t0, 90, t0.plusSeconds(300))) // same id replaces
        assertEquals(2, ex.all().size)
        assertEquals(2, ex.activeFor("a", t0.plusSeconds(99)).size)
        assertEquals(1, ex.activeFor("a", t0.plusSeconds(100)).size)
        assertTrue(ex.activeFor("b", t0).isEmpty())
        assertEquals(1, ex.purgeExpired(t0.plusSeconds(100)))
        assertEquals(90L, ex.all().single().addedSeconds)
        lim.upsert(LimitRule("x", 1)); lim.upsert(LimitRule("x", 2))
        assertEquals(2L, lim.all().single().dailySeconds)
        lim.remove("x"); assertTrue(lim.all().isEmpty())
        set.put("k", "v"); assertEquals("v", set.get("k")); set.remove("k"); assertNull(set.get("k"))
    }

    @Test
    fun coreMainNeverUsesCalendarOrSimpleDateFormat() {
        val root = listOf(File("src/main/kotlin"), File("core/src/main/kotlin")).first { it.isDirectory }
        val forbidden = Regex("""\b(Calendar|SimpleDateFormat)\b""")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { forbidden.containsMatchIn(it) } }.map { it.path }.toList()
        assertEquals(emptyList(), offenders)
    }
}
