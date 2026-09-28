import RCCore
import SwiftUI

/// Git: the branch in mono with whether it is clean and how far ahead it is,
/// or "Not a git repository", on a sunken group — and "Isolate in worktree"
/// where the agent can do it and the directory is a repository.
struct GitField: View {
    let form: NewSessionForm
    let agent: AgentInfo?

    var body: some View {
        let git = form.probe.status(deviceID: form.deviceID, path: form.cwd).git
        VStack(alignment: .leading, spacing: 0) {
            FieldLabel(S.newSession.git)
            HStack(spacing: Space.sp3) {
                if let git, git.isRepo {
                    Text(git.branch ?? "")
                        .css(FontSize.fs14, mono: true)
                    Text(summary(git))
                        .css(FontSize.fs12)
                        .foregroundStyle(Palette.inkSecondary)
                } else {
                    Text(S.newSession.notARepo)
                        .css(FontSize.fs12)
                        .foregroundStyle(Palette.inkSecondary)
                }
                if form.canWorktree(agent), git?.isRepo == true {
                    Spacer(minLength: 0)
                    HStack(spacing: Space.sp3) {
                        Text(S.newSession.isolateWorktree).css(FontSize.fs13)
                        Switch(isOn: form.worktree, label: S.newSession.isolateWorktree) { form.worktree = $0 }
                    }
                }
            }
            .padding(.vertical, Space.sp3)
            .padding(.horizontal, Space.sp4)
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Radius.md, style: .circular).fill(Palette.surfaceMuted))
        }
    }

    private func summary(_ git: GitStatus) -> String {
        let state = git.dirty == true ? S.newSession.gitDirty : S.newSession.gitClean
        guard let ahead = git.ahead else { return state }
        return "\(state) · \(S.newSession.gitAhead(ahead))"
    }
}
