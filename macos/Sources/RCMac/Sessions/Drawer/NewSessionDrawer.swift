import AppKit
import RCCore
import SwiftUI

/// `NewSessionDrawer.tsx`: the right-hand drawer the Sessions page and the chat
/// sidebar open, with the fields in the order they are decided — device, agent,
/// the agent's options, working directory, git — and one primary button.
/// Starting a session opens its conversation; ⌘↵ starts it from anywhere in
/// the drawer.
struct NewSessionDrawer: ViewModifier {
    @Binding var form: NewSessionForm?
    @Environment(MacAppModel.self) private var model

    func body(content: Content) -> some View {
        let devices = DeviceOrder.online(model.connection.devices)
        let device = form?.device(in: devices)
        content.drawer(isPresented: ListPresence.of($form), title: S.newSession.title,
                       subtitle: device.map { S.newSession.continuingOn($0.name) } ?? S.newSession.pickDevice) {
            if let form { NewSessionFields(form: form) }
        } footer: {
            if let form { NewSessionFooter(form: form, onStarted: open) }
        }
    }

    private func open(_ session: Session) {
        form = nil
        model.router.go(.chat(deviceId: session.deviceID, sessionId: session.sessionID))
    }
}

/// The drawer's foot: why the last start failed, and Start session with its
/// shortcut at the trailing edge.
struct NewSessionFooter: View {
    let form: NewSessionForm
    let onStarted: (Session) -> Void
    @Environment(MacAppModel.self) private var model
    @State private var monitor: Any?

    var body: some View {
        let devices = DeviceOrder.online(model.connection.devices)
        let device = form.device(in: devices)
        let agent = form.agent(of: device)
        VStack(spacing: 0) {
            if let error = form.error {
                FormError(error).padding(.bottom, Space.sp3)
            }
            Button(action: start) {
                HStack(spacing: Space.sp2) {
                    if form.busy { Spinner() }
                    Text(form.busy ? S.newSession.starting : S.newSession.start).css(FontSize.fs15, weight: .medium)
                }
            }
            .buttonStyle(.btn(.primary, size: .block))
            .disabled(!form.canStart(device: device, agent: agent))
            // `.kbd-hint`: the shortcut 16 points in from the button's trailing edge.
            .overlay(alignment: .trailing) {
                Text("⌘↵")
                    .css(FontSize.fs12, mono: true)
                    .foregroundStyle(Palette.inkInverse)
                    .opacity(0.65)
                    .padding(.trailing, Space.sp4)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .onAppear(perform: listen)
        .onDisappear(perform: stopListening)
    }

    private func start() { Self.start(form: form, model: model, onStarted: onStarted) }

    private static func start(form: NewSessionForm, model: MacAppModel, onStarted: @escaping (Session) -> Void) {
        let device = form.device(in: DeviceOrder.online(model.connection.devices))
        Task {
            if let session = await form.start(device: device, agent: form.agent(of: device),
                                              channel: model.connection.channel) {
                onStarted(session)
            }
        }
    }

    /// The web listens on the document for ⌘↵ while the drawer is open, so the
    /// shortcut works whichever field has the focus — the picker over it too.
    private func listen() {
        guard monitor == nil else { return }
        let form = form, model = model, onStarted = onStarted
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            let returnKeys: Set<UInt16> = [36, 76]
            guard returnKeys.contains(event.keyCode),
                  !event.modifierFlags.intersection([.command, .control]).isEmpty else { return event }
            MainActor.assumeIsolated { Self.start(form: form, model: model, onStarted: onStarted) }
            return nil
        }
    }

    private func stopListening() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
    }
}
