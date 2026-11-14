package com.gainandshine.unloop.core.engine

/**
 * Daily allowance per blocked surface ("Reels: 20 minutes a day, then block"). The shell supplies the numbers and
 * the persistence; the day rollover is the shell's job (usedMs is "used today").
 */
interface SurfaceBudgets {
    /** 0 = no allowance: the surface is always blocked. */
    fun allowanceMs(surface: String): Long
    fun usedMs(surface: String): Long
    fun addUsedMs(surface: String, ms: Long)

    object None : SurfaceBudgets {
        override fun allowanceMs(surface: String) = 0L
        override fun usedMs(surface: String) = 0L
        override fun addUsedMs(surface: String, ms: Long) = Unit
    }
}

/**
 * Charges time while a budgeted surface is on screen. Fed once per scan with the snapshot time. A gap longer than
 * [MAX_GAP_MS] (screen off, app switched, a late scan) is not charged: the user was not provably watching.
 */
class BudgetMeter(private val budgets: SurfaceBudgets) {
    private var lastSurface: String? = null
    private var lastAtMs = 0L

    fun remainingMs(surface: String): Long = (budgets.allowanceMs(surface) - budgets.usedMs(surface)).coerceAtLeast(0L)

    /** True when [surface] may be watched right now. Charges the time since the previous call. */
    fun tryAllow(surface: String, nowMs: Long): Boolean {
        if (budgets.allowanceMs(surface) <= 0 || remainingMs(surface) <= 0) {
            idle()
            return false
        }
        if (lastSurface == surface) {
            val d = nowMs - lastAtMs
            if (d in 1..MAX_GAP_MS) budgets.addUsedMs(surface, d)
        }
        lastSurface = surface
        lastAtMs = nowMs
        return true // the allowance may hit zero now; the next call then answers false
    }

    fun idle() {
        lastSurface = null
    }

    companion object {
        const val MAX_GAP_MS = 2_000L

        /** How often the shell must scan while a budgeted surface is being watched. */
        const val POLL_MS = 1_000L
    }
}
