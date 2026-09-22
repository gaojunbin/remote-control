import Foundation

/// Amendment A41: the Settings screen's preferences belong to the account, so
/// the gateway keeps them and this phone only caches them.
///
/// `docs/DESIGN.md` § "Settings are the account's, not the device's": the
/// interface language, the dictation language, polish with its model and its
/// strength, and the timeline detail read the same in the browser and on the
/// phone, the latest write to reach the gateway winning everywhere. Screens
/// keep reading `SettingsStore`, which is where a preference has always been
/// read; this is the layer that keeps it equal to the account's:
///
/// - `hello.preferences` and every `preferences.updated` frame are applied to
///   the store, so a change made elsewhere moves the control in place;
/// - a change made here is written up with `PATCH /api/preferences`, one
///   request carrying the fields that differ, and the reply is applied like a
///   frame;
/// - a field the account has not set is offered this phone's own value once
///   per sign-in, so nothing changes for the person on the day the gateway
///   learns the rule;
/// - a value equal to the one already held writes nothing, which is what keeps
///   an arriving frame from being echoed back.
///
/// A gateway older than A35 sends no preferences at all and this layer then
/// does nothing: the store keeps behaving as the phone's own, which is what it
/// was. Nothing on the screen says "syncing" — the value simply reads the same.
@MainActor
public final class PreferenceSync {
    private let settings: SettingsStore
    private var api: (any GatewayAPI)?
    /// What the gateway holds, as far as this app has been told. Nil before
    /// `hello`, and on a gateway that carries no preferences at all — either
    /// way nothing is written, because there is nothing to compare against.
    private var account: Preferences?
    /// Whether this phone has already offered its own values for the fields the
    /// account had none for. Once per sign-in: a gateway older than the
    /// amendment stores none of them however often it is asked, and a write per
    /// reconnection would be a write per flap.
    private var hasOfferedOwnValues = false
    /// True while the account's object is being written into the store. The
    /// store reports every field as it lands, and none of those is a change to
    /// write back — least of all one measured against a field this pass has
    /// not reached yet.
    private var isApplying = false
    /// The last write scheduled. Each one waits for the one before it, so two
    /// changes made a moment apart reach the gateway in the order they were
    /// made: the order they arrive in is the order that wins, everywhere.
    private var writing: Task<Void, Never>?
    /// How many writes are still to run. Nothing is scheduled when there is
    /// nothing to send and nothing whose reply could change that.
    private var scheduled = 0

    public init(settings: SettingsStore) {
        self.settings = settings
        settings.onAccountPreferenceChange = { [weak self] in self?.write() }
    }

    /// Bind to the connection's HTTP client. Called on every sign-in, and with
    /// nil on sign-out, which forgets the previous account's values so the next
    /// person's `hello` is the only thing that fills them.
    public func attach(api: (any GatewayAPI)?) {
        self.api = api
        guard api != nil else {
            account = nil
            hasOfferedOwnValues = false
            return
        }
        // `hello` can land before the sign-in has handed the client over, so
        // the offer below is made by whichever of the two happens second.
        offerOwnValues()
    }

    /// The frames that carry the account's copy. `hello` seeds it and
    /// `preferences.updated` replaces it, which is how a switch turned on in
    /// the browser moves on the phone.
    public func receive(_ frame: AppFrame) {
        switch frame {
        case .hello(let hello):
            adopt(hello.preferences)
        case .preferencesUpdated(let value):
            adopt(value)
        default:
            break
        }
    }

    /// Take the account's object as the truth, then offer this phone's own
    /// value for whatever the account has never been told.
    private func adopt(_ preferences: Preferences?) {
        guard let preferences else { return }
        // The account's copy is taken before the store is written, so the
        // change the store reports back is found equal and nothing is echoed.
        account = preferences
        apply(preferences)
        offerOwnValues()
    }

    /// The fields the account carries, into the store. A field it does not
    /// carry is left alone, and so is one already reading the arriving value.
    private func apply(_ preferences: Preferences) {
        isApplying = true
        defer { isApplying = false }
        if let value = preferences.language, settings.language != value {
            settings.language = value
        }
        if let value = preferences.sttLanguage, settings.voiceLanguage != value {
            settings.voiceLanguage = value
        }
        if let value = preferences.polishEnabled, settings.polishEnabled != value {
            settings.polishEnabled = value
        }
        if let value = preferences.polishModel, settings.polishModel != value {
            settings.polishModel = value
        }
        if let value = preferences.polishStrength, settings.polishStrength != value {
            settings.polishStrength = value
        }
        if let value = preferences.timelineDetail, settings.timelineDetail != value {
            settings.timelineDetail = value
        }
    }

    /// The first connection of a sign-in offers what this phone already had for
    /// every field the account has none of, which is the whole of the upgrade
    /// day: the person's own settings become the account's.
    private func offerOwnValues() {
        guard !hasOfferedOwnValues, api != nil, account != nil else { return }
        hasOfferedOwnValues = true
        write()
    }

    /// Schedule a write of everything this phone holds that the account does
    /// not. An absent field counts as different, which is what makes the offer
    /// above the same operation as a change made on the screen.
    ///
    /// The write waits for the one before it rather than racing it: two
    /// changes a moment apart must reach the gateway in the order they were
    /// made, or the older one would be the one that wins everywhere.
    private func write() {
        guard !isApplying, api != nil, let account else { return }
        guard scheduled > 0 || changes(against: account) != nil else { return }
        scheduled += 1
        let previous = writing
        writing = Task { [weak self] in
            await previous?.value
            await self?.send()
            self?.scheduled -= 1
        }
    }

    /// One request, with whatever differs by the time it is this write's turn:
    /// the reply to the write before it may already have settled the field.
    private func send() async {
        guard let api, let account, let changes = changes(against: account) else { return }
        guard let answer = try? await api.patchPreferences(changes) else { return }
        // The reply is the account's whole object, so it is taken exactly as a
        // frame carrying it would be.
        adopt(answer.preferences)
    }

    /// What this phone holds that the account does not, or nothing.
    private func changes(against account: Preferences) -> PreferencePatch? {
        var patch = PreferencePatch()
        if account.language != settings.language { patch.language = settings.language }
        if account.sttLanguage != settings.voiceLanguage {
            patch.sttLanguage = settings.voiceLanguage
        }
        if account.polishEnabled != settings.polishEnabled {
            patch.polishEnabled = settings.polishEnabled
        }
        if account.polishModel != settings.polishModel { patch.polishModel = settings.polishModel }
        if account.polishStrength != settings.polishStrength {
            patch.polishStrength = settings.polishStrength
        }
        if account.timelineDetail != settings.timelineDetail {
            patch.timelineDetail = settings.timelineDetail
        }
        return patch.isEmpty ? nil : patch
    }

    /// Wait for the writes in flight, for a check or a test that has to see
    /// the round trip through rather than sleep through it. A reply can start
    /// one more write, which is waited for as well.
    public func settle() async {
        while scheduled > 0, let task = writing { await task.value }
    }
}
