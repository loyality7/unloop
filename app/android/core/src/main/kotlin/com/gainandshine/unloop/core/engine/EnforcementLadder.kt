package com.gainandshine.unloop.core.engine

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.Verdict

/** What the engine should do next for the verdict it just evaluated. */
sealed interface Step {
    data object Idle : Step
    data class Perform(val action: Action, val surface: String, val rung: Int) : Step
    data class Wait(val untilMs: Long) : Step
    data class GiveUp(val surface: String, val reason: String) : Step
}

/** Tunables. Defaults per the design notes; all monotonic ms. */
data class LadderConfig(
    val verifyWindowMs: Long = 700,
    val backoffBaseMs: Long = 2_000,
    val backoffCapMs: Long = 30_000,
    val maxAttempts: Int = 6,
)

enum class Phase { IDLE, ACTED, VERIFYING, GAVE_UP }

/** Observable per-surface state. */
data class SurfaceLadderState(val phase: Phase, val rung: Int, val attempts: Int, val giveUps: Int, val retryAtMs: Long?)

/**
 * Runs ONE rung of a Block verdict's ladder at a time and verifies with the next scan.
 *
 *  IDLE -> ACTED(rung) -> VERIFYING -> (None after the window: success, back to IDLE with counters cleared)
 *                                   -> (still Block after the window: next rung)
 *  last rung exhausted -> GAVE_UP: exponential backoff (base, 2x, ... capped), then restart at rung 0.
 *  [LadderConfig.maxAttempts] rungs issued without a success gives up for good (until [clear]).
 *
 * FAIL DIRECTION: when unsure, do nothing. A scan inside the verify window only yields Wait; a clock that
 * runs backwards re-bases the stored timestamps so waits keep their remaining length instead of freezing
 * or firing early. Attempts count consecutive failures: a success resets them (deviation from a strict
 * per-sitting count so one good day does not exhaust the budget). Single-threaded, pure.
 */
class EnforcementLadder(private val cfg: LadderConfig = LadderConfig()) {
    private class S {
        var phase = Phase.IDLE
        var rung = 0
        var attempts = 0
        var giveUps = 0
        var actedAt = 0L
        var retryAt = 0L
        var lastSeen = Long.MIN_VALUE
    }

    private val states = LinkedHashMap<String, S>()

    fun state(surface: String): SurfaceLadderState? = states[surface]?.let {
        SurfaceLadderState(it.phase, it.rung, it.attempts, it.giveUps, if (it.phase == Phase.GAVE_UP) it.retryAt.takeIf { r -> r != NEVER } else null)
    }

    fun activeSurfaces(): Set<String> = states.keys.toSet()

    private val succeeded = ArrayList<String>()

    /** Surfaces whose last [next] call concluded SUCCESS (verified gone). For diagnostics. */
    val lastSucceeded: List<String> get() = succeeded

    fun next(verdict: Verdict, nowMs: Long): Step {
        succeeded.clear()
        return when (verdict) {
            Verdict.None -> onNone(nowMs)
            is Verdict.Block -> onBlock(verdict, nowMs)
        }
    }

    private fun onNone(now: Long): Step {
        var wait: Long? = null
        for ((name, s) in states.entries.toList()) {
            rebase(s, now)
            if (s.phase != Phase.ACTED && s.phase != Phase.VERIFYING) continue
            val end = s.actedAt + cfg.verifyWindowMs
            if (now >= end) succeed(name, s) else wait = maxOf(wait ?: end, end)
        }
        return wait?.let { Step.Wait(it) } ?: Step.Idle
    }

    private fun onBlock(b: Verdict.Block, now: Long): Step {
        val s = states.getOrPut(b.surface) { S() }
        rebase(s, now)
        return when (s.phase) {
            Phase.IDLE -> perform(b, s, 0, now)
            Phase.ACTED, Phase.VERIFYING -> {
                val end = s.actedAt + cfg.verifyWindowMs
                if (now < end) {
                    s.phase = Phase.VERIFYING
                    Step.Wait(end)
                } else if (s.rung + 1 < b.ladder.size) {
                    perform(b, s, s.rung + 1, now)
                } else {
                    giveUpWithBackoff(b.surface, s, now)
                }
            }
            Phase.GAVE_UP ->
                if (s.retryAt == NEVER) Step.Idle
                else if (now < s.retryAt) Step.Wait(s.retryAt)
                else perform(b, s, 0, now)
        }
    }

    private fun perform(b: Verdict.Block, s: S, rung: Int, now: Long): Step {
        val action = b.ladder.getOrNull(rung)
        if (action == null) {
            s.phase = Phase.GAVE_UP; s.retryAt = NEVER
            return Step.GiveUp(b.surface, REASON_EMPTY_LADDER)
        }
        if (s.attempts >= cfg.maxAttempts) {
            s.phase = Phase.GAVE_UP; s.retryAt = NEVER
            return Step.GiveUp(b.surface, REASON_MAX_ATTEMPTS)
        }
        s.attempts++
        s.rung = rung
        s.phase = Phase.ACTED
        s.actedAt = now
        return Step.Perform(action, b.surface, rung)
    }

    private fun giveUpWithBackoff(surface: String, s: S, now: Long): Step {
        val exp = minOf(s.giveUps, MAX_BACKOFF_SHIFT)
        val backoff = minOf(cfg.backoffCapMs, cfg.backoffBaseMs shl exp)
        s.giveUps++
        s.phase = Phase.GAVE_UP
        s.retryAt = now + backoff
        return Step.GiveUp(surface, REASON_EXHAUSTED)
    }

    private fun succeed(name: String, s: S) {
        succeeded += name
        s.phase = Phase.IDLE; s.rung = 0; s.attempts = 0; s.giveUps = 0
    }

    /**
     * The foreground package changed (or the user went home): anything in ACTED/VERIFYING succeeded
     * because the user left. Returns how many surfaces that was. All state is then cleared.
     */
    fun onForegroundChanged(): Int {
        val n = states.values.count { it.phase == Phase.ACTED || it.phase == Phase.VERIFYING }
        states.clear()
        return n
    }

    /** Sitting ended: forget everything, including backoff and attempts. */
    fun clear() = states.clear()

    private fun rebase(s: S, now: Long) {
        if (s.lastSeen != Long.MIN_VALUE && now < s.lastSeen) {
            val back = now - s.lastSeen // negative
            s.actedAt += back
            if (s.retryAt != NEVER) s.retryAt += back
        }
        s.lastSeen = now
    }

    companion object {
        const val REASON_EXHAUSTED = "ladder exhausted"
        const val REASON_MAX_ATTEMPTS = "max attempts"
        const val REASON_EMPTY_LADDER = "empty ladder"
        private const val NEVER = Long.MAX_VALUE
        private const val MAX_BACKOFF_SHIFT = 20
    }
}
