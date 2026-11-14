package com.gainandshine.unloop.core.engine

/** Kinds of "the rules did not work" we count. */
enum class RuleMiss { ADAPTER_ERROR, NO_PRIMARY_WINDOW }

/**
 * Local counters only. Keys are stable surface ids or enum names: no text, no package names of other
 * apps beyond surface ids, no PII.
 */
interface DiagnosticsSink {
    fun block(surface: String)
    fun success(surface: String)
    fun giveUp(surface: String)
    fun ruleMiss(kind: RuleMiss)

    object None : DiagnosticsSink {
        override fun block(surface: String) = Unit
        override fun success(surface: String) = Unit
        override fun giveUp(surface: String) = Unit
        override fun ruleMiss(kind: RuleMiss) = Unit
    }
}

class InMemoryDiagnostics : DiagnosticsSink {
    private val blocks = HashMap<String, Int>()
    private val successes = HashMap<String, Int>()
    private val giveUps = HashMap<String, Int>()
    private val misses = HashMap<RuleMiss, Int>()

    override fun block(surface: String) { blocks.merge(surface, 1, Int::plus) }
    override fun success(surface: String) { successes.merge(surface, 1, Int::plus) }
    override fun giveUp(surface: String) { giveUps.merge(surface, 1, Int::plus) }
    override fun ruleMiss(kind: RuleMiss) { misses.merge(kind, 1, Int::plus) }

    fun blocks(surface: String) = blocks[surface] ?: 0
    fun successes(surface: String) = successes[surface] ?: 0
    fun giveUps(surface: String) = giveUps[surface] ?: 0
    fun ruleMisses(kind: RuleMiss) = misses[kind] ?: 0
}
