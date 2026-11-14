package com.gainandshine.unloop.core.fixture

import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.model.WindowSnap

/** One step of a recorded session: a scan or an accessibility event, replayed in file order. */
sealed interface FixtureItem {
    data class Snap(val snapshot: UiSnapshot) : FixtureItem
    data class Ev(val event: EventRecord) : FixtureItem
}

/**
 * A recorded session plus its oracle. [oracle] has exactly one entry per [FixtureItem.Snap], in order:
 * "none" or "block:<surface>". [app] is a free tag ("instagram", "youtube") the test maps to an adapter.
 */
data class Fixture(
    val app: String,
    val oracle: List<String>,
    val items: List<FixtureItem>,
    val allowSharedItem: Boolean = true,
)

/** Oracle string for a verdict. */
fun Verdict.toOracle(): String = when (this) {
    Verdict.None -> "none"
    is Verdict.Block -> "block:$surface"
}

/**
 * JSONL codec. Line 1 is `#` + a JSON header (version, app, allowShared, allowText, oracle); every other line is one
 * snapshot or event. PRIVACY: [encode] drops node text and content descriptions unless `allowText = true`, which is
 * meant for hand-written synthetic fixtures only, never for traces captured from a real user.
 */
object FixtureCodec {
    private const val VERSION = 1L

    fun encode(f: Fixture, allowText: Boolean = false): String {
        val header = linkedMapOf<String, Any?>(
            "v" to VERSION, "app" to f.app, "allowShared" to f.allowSharedItem, "allowText" to allowText, "oracle" to f.oracle,
        )
        val sb = StringBuilder("#").append(Json.write(header)).append('\n')
        for (item in f.items) {
            val m = when (item) {
                is FixtureItem.Snap -> snapToMap(item.snapshot, allowText)
                is FixtureItem.Ev -> eventToMap(item.event)
            }
            sb.append(Json.write(m)).append('\n')
        }
        return sb.toString()
    }

    fun decode(text: String): Fixture {
        val lines = text.lines().filter { it.isNotBlank() }
        require(lines.isNotEmpty() && lines[0].startsWith("#")) { "missing # header" }
        val h = obj(Json.parse(lines[0].substring(1)))
        require(h["v"] == VERSION) { "unsupported fixture version" }
        val items = lines.drop(1).map { line ->
            val m = obj(Json.parse(line))
            when (m["k"]) {
                "snap" -> FixtureItem.Snap(snapFromMap(m))
                "ev" -> FixtureItem.Ev(eventFromMap(m))
                else -> error("unknown record kind ${m["k"]}")
            }
        }
        return Fixture(
            app = h["app"] as String,
            oracle = list(h["oracle"]).map { it as String },
            items = items,
            allowSharedItem = h["allowShared"] as? Boolean ?: true,
        )
    }

    private fun snapToMap(s: UiSnapshot, allowText: Boolean) = linkedMapOf<String, Any?>(
        "k" to "snap", "at" to s.atMs, "pkg" to s.pkg, "screen" to rect(s.screen),
        "w" to s.windows.map { w ->
            linkedMapOf(
                "pkg" to w.pkg, "b" to rect(w.bounds), "act" to w.isActive, "foc" to w.isFocused,
                "app" to w.isApplication, "root" to nodeToMap(w.root, allowText),
            )
        },
    )

    private fun nodeToMap(n: NodeSnap, allowText: Boolean): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        n.id?.let { m["id"] = it }
        n.cls?.let { m["cls"] = it }
        if (allowText) {
            n.text?.let { m["t"] = it }
            n.desc?.let { m["d"] = it }
        }
        m["b"] = rect(n.bounds)
        if (!n.visible) m["vis"] = false
        if (n.selected) m["sel"] = true
        if (n.clickable) m["clk"] = true
        if (!n.enabled) m["en"] = false
        if (n.editable) m["ed"] = true
        if (n.children.isNotEmpty()) m["c"] = n.children.map { nodeToMap(it, allowText) }
        return m
    }

    private fun snapFromMap(m: Map<String, Any?>) = UiSnapshot(
        atMs = long(m["at"]), pkg = m["pkg"] as String, screen = rect(m["screen"]),
        windows = list(m["w"]).map { w ->
            val wm = obj(w)
            WindowSnap(
                pkg = wm["pkg"] as String, bounds = rect(wm["b"]), root = nodeFromMap(obj(wm["root"])),
                isActive = wm["act"] as? Boolean ?: true, isFocused = wm["foc"] as? Boolean ?: true,
                isApplication = wm["app"] as? Boolean ?: true,
            )
        },
    )

    private fun nodeFromMap(m: Map<String, Any?>): NodeSnap = NodeSnap(
        id = m["id"] as String?, cls = m["cls"] as String?, text = m["t"] as String?, desc = m["d"] as String?,
        bounds = rect(m["b"]),
        visible = m["vis"] as? Boolean ?: true, selected = m["sel"] as? Boolean ?: false,
        clickable = m["clk"] as? Boolean ?: false, enabled = m["en"] as? Boolean ?: true,
        editable = m["ed"] as? Boolean ?: false,
        children = if (m["c"] == null) emptyList() else list(m["c"]).map { nodeFromMap(obj(it)) },
    )

    private fun eventToMap(e: EventRecord) = linkedMapOf<String, Any?>(
        "k" to "ev", "type" to e.type.name, "pkg" to e.pkg, "cls" to e.cls, "win" to e.windowId,
        "from" to e.fromIndex, "to" to e.toIndex, "count" to e.itemCount, "dx" to e.scrollDx, "dy" to e.scrollDy,
        "cct" to e.contentChangeTypes, "at" to e.atMs,
    )

    private fun eventFromMap(m: Map<String, Any?>) = EventRecord(
        type = EventType.valueOf(m["type"] as String), pkg = m["pkg"] as String?, cls = m["cls"] as String?,
        windowId = int(m["win"]), fromIndex = int(m["from"]), toIndex = int(m["to"]), itemCount = int(m["count"]),
        scrollDx = int(m["dx"]), scrollDy = int(m["dy"]), contentChangeTypes = int(m["cct"]), atMs = long(m["at"]),
    )

    private fun rect(r: Rect) = listOf(r.left, r.top, r.right, r.bottom)

    private fun rect(v: Any?): Rect {
        val l = list(v).map { int(it) }
        require(l.size == RECT_SIZE) { "rect needs 4 numbers" }
        return Rect(l[0], l[1], l[2], l[3])
    }

    private const val RECT_SIZE = 4

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: error("expected object")

    private fun list(v: Any?): List<Any?> = v as? List<*> ?: error("expected array")

    private fun long(v: Any?): Long = (v as? Long) ?: error("expected integer")

    private fun int(v: Any?): Int = long(v).toInt()
}
