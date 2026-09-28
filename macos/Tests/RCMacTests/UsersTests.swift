import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/UsersPage.test.tsx` and `stores/users.ts`, on the demo
    /// gateway's accounts: the list, the switch, the row actions and the
    /// dialogs, and a member who is given none of it.
    @Suite("Users", .serialized) @MainActor
    struct UsersTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        private func admin() async -> MacAppModel {
            let model = MacAppModel(options: LaunchOptions(demo: true, ephemeral: true))
            await model.restoreOrPrompt()
            for _ in 0..<200 where !model.connection.hasSnapshot { try? await Task.sleep(for: .milliseconds(25)) }
            return model
        }

        @Test func itListsEveryAccountOnceItHasAsked() async {
            let model = await admin()
            defer { model.discardEphemeralState() }
            let users = UsersModel()
            #expect(!users.loaded && users.users.isEmpty)
            await users.load(on: model.connection)
            #expect(users.loaded && users.error == nil)
            #expect(users.users.map(\.username) == ["admin", "alice", "bob"])
            #expect(users.users.first?.isOperator == true)
            await model.signOut()
        }

        @Test func theRegistrationSwitchAnswersTheClickAndKeepsTheGatewaysWord() async {
            let model = await admin()
            defer { model.discardEphemeralState() }
            let users = UsersModel()
            await users.load(on: model.connection)
            #expect(!users.registrationOpen)
            await users.openRegistration(true, on: model.connection)
            #expect(users.registrationOpen && users.error == nil)
            await users.openRegistration(false, on: model.connection)
            #expect(!users.registrationOpen)
            await model.signOut()
        }

        @Test func disablingAnAccountReReadsTheList() async throws {
            let model = await admin()
            defer { model.discardEphemeralState() }
            let users = UsersModel()
            await users.load(on: model.connection)
            let alice = try #require(users.users.first { $0.username == "alice" })
            await users.toggleState(of: alice, on: model.connection)
            #expect(users.users.first { $0.username == "alice" }?.state == .disabled)
            let bob = try #require(users.users.first { $0.username == "bob" })
            await users.toggleState(of: bob, on: model.connection)
            #expect(users.users.first { $0.username == "bob" }?.state == .active)
            // The operator refuses, and the page says so.
            let operatorRow = try #require(users.users.first { $0.isOperator })
            await users.toggleState(of: operatorRow, on: model.connection)
            #expect(users.error == S.account.notAllowed)
            await model.signOut()
        }

        @Test func addingAnAccountTakesAUsernameAPasswordAndARole() async {
            let model = await admin()
            defer { model.discardEphemeralState() }
            let users = UsersModel()
            await users.load(on: model.connection)
            let form = AddUserForm()
            #expect(form.role == .member && !form.ready)
            form.username = "  carol "
            form.password = "short"
            #expect(!form.ready)
            form.password = "long enough"
            #expect(form.ready)
            #expect(await form.submit(to: users, on: model.connection))
            #expect(users.users.last?.username == "carol")
            let again = AddUserForm()
            again.username = "carol"
            again.password = "long enough"
            #expect(await again.submit(to: users, on: model.connection) == false)
            #expect(again.error == S.account.taken)
            await model.signOut()
        }

        @Test func aResetAndADeleteGoThroughTheirOwnDialogs() async throws {
            let model = await admin()
            defer { model.discardEphemeralState() }
            let users = UsersModel()
            await users.load(on: model.connection)
            let reset = ResetPasswordForm(username: "alice")
            #expect(!reset.ready)
            reset.password = "a new password"
            #expect(await reset.submit(to: users, on: model.connection))
            let alice = try #require(users.users.first { $0.username == "alice" })
            let delete = DeleteUserForm(user: alice)
            #expect(delete.body == S.users.deleteBodyDevices("alice", alice.devices))
            #expect(await delete.submit(to: users, on: model.connection))
            #expect(!users.users.contains { $0.username == "alice" })
            let bob = try #require(users.users.first { $0.username == "bob" })
            #expect(DeleteUserForm(user: bob).body == S.users.deleteBody("bob"))
            await model.signOut()
        }

        @Test func aMemberIsGivenNoAccountsAndASignOutForgetsThem() async {
            let model = await admin()
            defer { model.discardEphemeralState() }
            let users = SettingsFeature.state(of: model).users
            await users.load(on: model.connection)
            #expect(users.loaded && !users.users.isEmpty)
            await model.signOut()
            #expect(!users.loaded && users.users.isEmpty)

            let member = MacAppModel(options: LaunchOptions(demoAccount: true, ephemeral: true))
            defer { member.discardEphemeralState() }
            await member.restoreOrPrompt()
            await member.signIn(origin: "https://demo.remote-control.invalid", username: DemoFixtures.memberUsername,
                                password: "devdevdev")
            let memberUsers = UsersModel()
            #expect(memberUsers.store(on: member.connection) == nil)
            await memberUsers.load(on: member.connection)
            #expect(!memberUsers.loaded)
            member.router.go(.users)
            #expect(member.router.route == .sessions)
            await member.signOut()
        }
    }
}
