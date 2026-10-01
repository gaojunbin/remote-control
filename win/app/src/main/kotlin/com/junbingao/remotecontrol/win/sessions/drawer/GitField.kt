package com.junbingao.remotecontrol.win.sessions.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.GitStatus
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Switch
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.devices.ExactFrame
import com.junbingao.remotecontrol.win.strings.S

/**
 * Git: the branch in mono with whether it is clean and how far ahead it is, or "Not a git
 * repository", on a sunken group — and "Isolate in worktree" where the agent can do it and the
 * directory is a repository.
 */
@Composable
internal fun GitField(form: NewSessionForm, agent: AgentInfo?) {
    val git = form.probe.status(form.deviceID, form.cwd).git
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        FieldLabel(S.newSession.git)
        ExactFrame(Modifier.fillMaxWidth().background(Palette.surfaceMuted, RoundedCornerShape(Radius.md)), minHeight = 52.dp) {
            HStack(Modifier.padding(vertical = Space.sp3, horizontal = Space.sp4), spacing = Space.sp3) {
                if (git != null && git.isRepo) {
                    Text(git.branch ?: "", css(FontSize.fs14, mono = true))
                    Text(summary(git), css(FontSize.fs12), color = Palette.inkSecondary)
                } else {
                    Text(S.newSession.notARepo, css(FontSize.fs12), color = Palette.inkSecondary)
                }
                if (form.canWorktree(agent) && git?.isRepo == true) {
                    Spacer(Modifier.weight(1f))
                    HStack(spacing = Space.sp3) {
                        Text(S.newSession.isolateWorktree, css(FontSize.fs13))
                        Switch(isOn = form.worktree, label = S.newSession.isolateWorktree) { form.worktree = it }
                    }
                }
            }
        }
    }
}

private fun summary(git: GitStatus): String {
    val state = if (git.dirty == true) S.newSession.gitDirty else S.newSession.gitClean
    val ahead = git.ahead ?: return state
    return "$state · ${S.newSession.gitAhead(ahead)}"
}
