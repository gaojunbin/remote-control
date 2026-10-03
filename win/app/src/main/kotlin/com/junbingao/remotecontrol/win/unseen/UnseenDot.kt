package com.junbingao.remotecontrol.win.unseen

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.junbingao.remotecontrol.win.design.DotSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.strings.S

/**
 * Amendment A47: the red dot of a session that stopped working and waits for the person
 * (`docs/DESIGN.md` § "A red dot for a session that stopped and waits for you") — `--unseen-dot` of
 * the Danger red, drawn beside the title it marks, centred in the row's leading gutter and on the
 * title's line, so it moves nothing when it comes or goes. The row says it in words; the dot itself
 * is not read out.
 *
 * [reach] is from the row's leading edge to the title's, and [gutter] the room at that edge the dot
 * is centred in.
 */
fun Modifier.unseenTitle(unseen: Boolean, reach: Dp, gutter: Dp): Modifier =
    if (!unseen) {
        this
    } else {
        drawBehind {
            drawCircle(Palette.danger, radius = DotSize.unseenDot.toPx() / 2,
                       center = Offset((gutter / 2 - reach).toPx(), size.height / 2))
        }
    }

/** What a screen reader hears on a row that carries the dot, after the row's own name: the Mac's `accessibilityValue`. */
fun Modifier.unseenValue(unseen: Boolean): Modifier =
    if (!unseen) this else semantics { stateDescription = S.sessions.unseen }
