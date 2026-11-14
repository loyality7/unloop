package com.gainandshine.unloop.store

import android.content.Context
import com.gainandshine.unloop.core.engine.SurfaceBudgets
import java.time.LocalDate

/**
 * Daily per-surface allowance in SharedPreferences. Allowance is minutes (0 = none); usage is milliseconds for
 * today's local date, so the day rolls over by itself and nothing needs resetting.
 */
class PrefsBudgets(context: Context) : SurfaceBudgets {
    private val p = context.applicationContext.getSharedPreferences("unloop_budgets", Context.MODE_PRIVATE)

    fun allowanceMinutes(surface: String): Int = p.getInt("allow_$surface", 0)
    fun setAllowanceMinutes(surface: String, minutes: Int) = p.edit().putInt("allow_$surface", minutes.coerceIn(0, MAX_MINUTES)).apply()

    override fun allowanceMs(surface: String): Long = allowanceMinutes(surface) * 60_000L
    override fun usedMs(surface: String): Long = p.getLong(usedKey(surface), 0L)
    override fun addUsedMs(surface: String, ms: Long) {
        p.edit().putLong(usedKey(surface), usedMs(surface) + ms).apply()
    }

    fun clear() = p.edit().clear().apply()

    private fun usedKey(surface: String) = "used_${surface}_${LocalDate.now()}"

    companion object {
        const val MAX_MINUTES = 240
    }
}
