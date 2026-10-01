package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.PreferencePatch
import com.junbingao.remotecontrol.core.protocol.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Amendment A41: the Settings screen's preferences belong to the account, so the gateway keeps them
 * and this phone only caches them.
 *
 * `docs/DESIGN.md` § "Settings are the account's, not the device's": the interface language, the
 * dictation language, polish with its model and its strength, and the timeline detail read the same
 * in the browser and on the phone, the latest write to reach the gateway winning everywhere.
 * Screens keep reading [SettingsStore], which is where a preference has always been read; this is
 * the layer that keeps it equal to the account's:
 *
 * - `hello.preferences` and every `preferences.updated` frame are applied to the store, so a change
 *   made elsewhere moves the control in place;
 * - a change made here is written up with `PATCH /api/preferences`, one request carrying the fields
 *   that differ, and the reply is applied like a frame;
 * - a field the account has not set is offered this phone's own value once per sign-in, so nothing
 *   changes for the person on the day the gateway learns the rule;
 * - a value equal to the one already held writes nothing, which is what keeps an arriving frame
 *   from being echoed back.
 *
 * A gateway older than A35 sends no preferences at all and this layer then does nothing: the store
 * keeps behaving as the phone's own, which is what it was. Nothing on the screen says "syncing" —
 * the value simply reads the same.
 *
 * The writes run in [tasks], the scope the owner passes in.
 */
class PreferenceSync(private val settings: SettingsStore, private val tasks: CoroutineScope) {
    private var api: GatewayAPI? = null

    /**
     * What the gateway holds, as far as this app has been told. Null before `hello`, and on a gateway
     * that carries no preferences at all — either way nothing is written, because there is nothing to
     * compare against.
     */
    private var account: Preferences? = null

    /**
     * Whether this phone has already offered its own values for the fields the account had none for.
     * Once per sign-in: a gateway older than the amendment stores none of them however often it is
     * asked, and a write per reconnection would be a write per flap.
     */
    private var hasOfferedOwnValues = false

    /**
     * True while the account's object is being written into the store. The store reports every field
     * as it lands, and none of those is a change to write back — least of all one measured against a
     * field this pass has not reached yet.
     */
    private var isApplying = false

    /**
     * The last write scheduled. Each one waits for the one before it, so two changes made a moment
     * apart reach the gateway in the order they were made: the order they arrive in is the order that
     * wins, everywhere.
     */
    private var writing: Job? = null

    /** How many writes are still to run. Nothing is scheduled when there is nothing to send and nothing whose reply could change that. */
    private var scheduled = 0

    init {
        settings.onAccountPreferenceChange = { write() }
    }

    /**
     * Bind to the connection's HTTP client. Called on every sign-in, and with null on sign-out, which
     * forgets the previous account's values so the next person's `hello` is the only thing that fills
     * them.
     */
    fun attach(api: GatewayAPI?) {
        this.api = api
        if (api == null) {
            account = null
            hasOfferedOwnValues = false
            return
        }
        // `hello` can land before the sign-in has handed the client over, so the offer below is made
        // by whichever of the two happens second.
        offerOwnValues()
    }

    /**
     * The frames that carry the account's copy. `hello` seeds it and `preferences.updated` replaces
     * it, which is how a switch turned on in the browser moves on the phone.
     */
    fun receive(frame: AppFrame) {
        when (frame) {
            is AppFrame.Hello -> adopt(frame.hello.preferences)
            is AppFrame.PreferencesUpdated -> adopt(frame.preferences)
            else -> Unit
        }
    }

    /** Take the account's object as the truth, then offer this phone's own value for whatever the account has never been told. */
    private fun adopt(preferences: Preferences?) {
        if (preferences == null) return
        // The account's copy is taken before the store is written, so the change the store reports
        // back is found equal and nothing is echoed.
        account = preferences
        apply(preferences)
        offerOwnValues()
    }

    /** The fields the account carries, into the store. A field it does not carry is left alone, and so is one already reading the arriving value. */
    private fun apply(preferences: Preferences) {
        isApplying = true
        try {
            preferences.language?.let { if (settings.language != it) settings.language = it }
            preferences.sttLanguage?.let { if (settings.voiceLanguage != it) settings.voiceLanguage = it }
            preferences.polishEnabled?.let { if (settings.polishEnabled != it) settings.polishEnabled = it }
            preferences.polishModel?.let { if (settings.polishModel != it) settings.polishModel = it }
            preferences.polishStrength?.let { if (settings.polishStrength != it) settings.polishStrength = it }
            preferences.timelineDetail?.let { if (settings.timelineDetail != it) settings.timelineDetail = it }
        } finally {
            isApplying = false
        }
    }

    /**
     * The first connection of a sign-in offers what this phone already had for every field the
     * account has none of, which is the whole of the upgrade day: the person's own settings become the
     * account's.
     */
    private fun offerOwnValues() {
        if (hasOfferedOwnValues || api == null || account == null) return
        hasOfferedOwnValues = true
        write()
    }

    /**
     * Schedule a write of everything this phone holds that the account does not. An absent field
     * counts as different, which is what makes the offer above the same operation as a change made on
     * the screen.
     *
     * The write waits for the one before it rather than racing it: two changes a moment apart must
     * reach the gateway in the order they were made, or the older one would be the one that wins
     * everywhere.
     */
    private fun write() {
        val account = account
        if (isApplying || api == null || account == null) return
        if (scheduled == 0 && changes(against = account) == null) return
        scheduled += 1
        val previous = writing
        writing = tasks.launch {
            try {
                previous?.join()
                send()
            } finally {
                scheduled -= 1
            }
        }
    }

    /** One request, with whatever differs by the time it is this write's turn: the reply to the write before it may already have settled the field. */
    private suspend fun send() {
        val api = api ?: return
        val account = account ?: return
        val changes = changes(against = account) ?: return
        val answer = attempt { api.patchPreferences(changes) } ?: return
        // The reply is the account's whole object, so it is taken exactly as a frame carrying it
        // would be.
        adopt(answer.preferences)
    }

    /** What this phone holds that the account does not, or nothing. */
    private fun changes(against: Preferences): PreferencePatch? {
        val patch = PreferencePatch(
            language = settings.language.takeIf { it != against.language },
            // Amendment A44: only a language the recogniser listens for is written. An `auto` from
            // before the amendment reads as Chinese where it stands, and offering it would put back a
            // word the contract has retired.
            sttLanguage = settings.voiceLanguage.takeIf { it != against.sttLanguage && it in DictationLanguage.codes },
            polishEnabled = settings.polishEnabled.takeIf { it != against.polishEnabled },
            polishModel = settings.polishModel.takeIf { it != against.polishModel },
            polishStrength = settings.polishStrength.takeIf { it != against.polishStrength },
            timelineDetail = settings.timelineDetail.takeIf { it != against.timelineDetail },
        )
        return if (patch.isEmpty) null else patch
    }

    /**
     * Wait for the writes in flight, for a check or a test that has to see the round trip through
     * rather than sleep through it. A reply can start one more write, which is waited for as well.
     */
    suspend fun settle() {
        while (scheduled > 0) {
            // A write whose scope ended before it ran never counts itself down, and nothing is left
            // to wait for.
            val task = writing?.takeUnless { it.isCompleted } ?: return
            task.join()
        }
    }
}
