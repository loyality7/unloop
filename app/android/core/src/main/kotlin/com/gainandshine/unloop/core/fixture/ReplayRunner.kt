package com.gainandshine.unloop.core.fixture

import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.Verdict

/** Runs an adapter over a [Fixture] exactly as the engine would: events to onEvent, snapshots to evaluate. */
object ReplayRunner {
    /** Verdicts, one per snapshot. Resets the adapter first so runs are independent. */
    fun run(adapter: SurfaceAdapter, f: Fixture, cfg: DetectionConfig = DetectionConfig(allowSharedItem = f.allowSharedItem)): List<Verdict> {
        adapter.reset()
        val out = ArrayList<Verdict>()
        for (item in f.items) {
            when (item) {
                is FixtureItem.Ev -> adapter.onEvent(item.event)
                is FixtureItem.Snap -> out += adapter.evaluate(item.snapshot, cfg)
            }
        }
        return out
    }

    /** Human-readable oracle violations; empty means the fixture passes. */
    fun mismatches(adapter: SurfaceAdapter, f: Fixture): List<String> {
        val got = run(adapter, f).map { it.toOracle() }
        if (got.size != f.oracle.size) return listOf("oracle has ${f.oracle.size} entries, fixture has ${got.size} snapshots")
        return got.indices.filter { got[it] != f.oracle[it] }.map { "snapshot $it: expected ${f.oracle[it]}, got ${got[it]}" }
    }
}
