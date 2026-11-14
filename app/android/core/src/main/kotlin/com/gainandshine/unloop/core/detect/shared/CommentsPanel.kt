package com.gainandshine.unloop.core.detect.shared

import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.WindowSnap

/**
 * A comments panel that is open: a visible node whose resource id mentions "comment" and which is big, like a sheet
 * (at least [MIN_HEIGHT] of the window tall and [MIN_WIDTH] wide). Buttons and one-line previews with "comment" in their
 * id are small and never match, so only an opened panel does. Id-based, so no language text is read.
 *
 * Fails open: if an app renames its ids nothing matches and nothing is closed. Ids differ per app version, so this is
 * a best guess until confirmed on a device (use Learn mode to capture the real ids).
 */
fun commentsPanel(w: WindowSnap): NodeSnap? = w.root.findAll { n ->
    n.visible && !n.bounds.isEmpty && n.id?.contains("comment", ignoreCase = true) == true &&
        n.bounds.height >= w.bounds.height * MIN_HEIGHT && n.bounds.width >= w.bounds.width * MIN_WIDTH
}.firstOrNull()

private const val MIN_HEIGHT = 0.35
private const val MIN_WIDTH = 0.8
