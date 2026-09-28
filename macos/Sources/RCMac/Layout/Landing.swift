import SwiftUI

/// `web/src/layout/Landing.tsx`: where an open lands. `docs/DESIGN.md` § "The
/// three screens": Sessions when the account has at least one device and
/// Devices when it has none, because a new account's first job is enrolling a
/// machine and everyone else's is the conversation.
///
/// The choice is made once per open and is not remembered: this place replaces
/// itself with its answer, so someone who then opens Devices on an account
/// with no devices stays there, and a device arriving later moves nobody. The
/// socket's snapshot is what fills the list, so nothing is decided before it
/// has synced, or every account would land on Devices for a round trip.
struct Landing: View {
    @Environment(MacAppModel.self) private var model

    var body: some View {
        BootView()
            .onChange(of: model.connection.hasSnapshot, initial: true) { _, synced in
                guard synced else { return }
                model.router.replace(Self.destination(hasDevices: !model.connection.devices.isEmpty))
            }
    }

    /// The rule itself, with nothing around it.
    static func destination(hasDevices: Bool) -> Route { hasDevices ? .sessions : .devices }
}
