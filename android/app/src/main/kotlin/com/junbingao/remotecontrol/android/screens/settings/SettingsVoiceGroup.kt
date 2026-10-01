package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.GroupedListScope
import com.junbingao.remotecontrol.android.system.Switch
import com.junbingao.remotecontrol.core.state.DictationLanguage
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.VoiceBackend
import com.junbingao.remotecontrol.core.transport.PolishModel
import com.junbingao.remotecontrol.core.transport.PolishStrength
import kotlinx.coroutines.CancellationException

/**
 * The Voice group: where the words come from, in which language, and what happens to them
 * afterwards (A29).
 *
 * `docs/DESIGN.md` § "The Settings screen": the backend's own sentence is the Transcribe row's, and
 * a gateway with no transcription service or no polish model says so in the row it belongs to
 * rather than under the group.
 *
 * Amendment A44: the Dictation language row stands only while the phone is the one listening
 * (`AppModel.voiceBackendInEffect`). The gateway's provider detects the language itself, so gateway
 * transcription has nothing to pick.
 *
 * The polish rows are written here rather than in a view of their own, so the group is one body and
 * one file.
 */
fun GroupedListScope.SettingsVoiceGroup(
    /** Held so a change of interface language rebuilds the sentences where they stand (`SettingsLabel`). */
    language: InterfaceLanguage,
) {
    SettingsGroup("Voice") { VoiceRows(language) }
}

@Composable
private fun VoiceRows(language: InterfaceLanguage) {
    val model = LocalAppModel.current
    val settings = model.settings
    // The provider's models, as this gateway lists them (A29). Kept while the group scrolls out of
    // the list and back, as the iPhone keeps a view's state while its cell is reused.
    var models by rememberSaveable(stateSaver = PolishModelsSaver) { mutableStateOf(emptyList<PolishModel>()) }
    var listFailed by rememberSaveable { mutableStateOf(false) }
    // The gateway has a polish model, so the switch can take effect. It is the one thing the gateway
    // has a say in here; everything else is the person's own preference, off by default.
    val canPolish = model.connection.polish.enabled

    SettingsMenuRow(
        "Transcribe",
        sentence = transcribeSentence(model),
        selection = settings.voiceBackend,
        onSelect = { settings.voiceBackend = it },
        chosen = L10n.platform(settings.voiceBackend.title),
        options = VoiceBackend.allCases.map { SettingsChoice(it, L10n.platform(it.title)) },
        enabled = !(!model.connection.stt.enabled && settings.voiceBackend == VoiceBackend.onDevice),
        tag = "settings.voiceBackend",
    )
    // Named for what it is: the interface language is the Reading group's setting, and two rows
    // called "Language" on one screen is a riddle rather than a preference.
    if (model.voiceBackendInEffect == VoiceBackend.onDevice) {
        SettingsMenuRow(
            "Dictation language",
            sentence = L10n.string("The language you speak. The recogniser on this iPhone listens for one at a time."),
            // The language the recogniser listens for — a legacy `auto` reads as Chinese — written
            // only when one is picked (A44).
            selection = settings.dictationLanguage,
            onSelect = { settings.voiceLanguage = it },
            chosen = languageName(settings.dictationLanguage, language),
            options = DictationLanguage.codes.map { SettingsChoice(it, languageName(it, language)) },
            tag = "settings.voiceLanguage",
        )
    }
    // Amendment A29: dictation is the setting above; what happens to the words afterwards is the
    // setting below it.
    SettingsRow("Polish dictation with AI", sentence = polishSentence(canPolish)) {
        Switch(
            isOn = settings.polishEnabled,
            onChange = { settings.polishEnabled = it },
            enabled = canPolish,
            tag = "settings.polish",
        )
    }
    // The models are asked for when the switch appears, and again if the gateway's answer about
    // polishing changes under it.
    LaunchedEffect(canPolish) {
        val answer = loadModels(model, canPolish) ?: return@LaunchedEffect
        answer.onSuccess {
            models = it
            listFailed = false
        }.onFailure { listFailed = true }
    }

    if (canPolish && settings.polishEnabled) {
        val modelName = modelName(settings.polishModel, models)
        // A model that is not on the list — none chosen yet, or one the provider has since dropped
        // — still needs a row, or the picker would show someone else's choice.
        val missing = if (models.all { it.id != settings.polishModel }) listOf(SettingsChoice(settings.polishModel, modelName)) else emptyList()
        SettingsMenuRow(
            "Model",
            sentence = L10n.string(if (listFailed) "The model list could not be loaded." else "From the list this gateway serves."),
            selection = settings.polishModel,
            onSelect = { settings.polishModel = it },
            chosen = modelName,
            options = missing + models.map { SettingsChoice(it.id, it.label) },
            tag = "settings.polishModel",
        )
        SettingsMenuRow(
            "Strength",
            sentence = L10n.string("Moderate cleans up. Strong also restructures and resolves references."),
            selection = settings.polishStrength,
            onSelect = { settings.polishStrength = it },
            chosen = settings.polishStrength.title,
            options = PolishStrength.allCases.map { SettingsChoice(it, it.title) },
            tag = "settings.polishStrength",
        )
    }
}

/**
 * The chosen backend's own sentence, or the reason the choice is not really one: a gateway with no
 * transcription service leaves the phone's own recogniser as the only way to dictate.
 */
private fun transcribeSentence(model: AppModel): String {
    if (model.settings.voiceBackend == VoiceBackend.gateway && !model.connection.stt.enabled) {
        return L10n.string("This gateway has no transcription service configured, so dictation falls back to on-device recognition.")
    }
    return L10n.platform(model.settings.voiceBackend.explanation)
}

/** What polishing sends, and when. A gateway with no polish model says so instead, under a switch that cannot be turned on. */
private fun polishSentence(canPolish: Boolean): String {
    if (!canPolish) return L10n.string("This gateway has no polish model configured")
    return L10n.string("Sends what you dictated and the last few messages to this gateway's model. Nothing is sent while it is off.")
}

/**
 * The word at the trailing edge of the Model row: the label the gateway gave the chosen model, the
 * bare id while the list has not arrived, or an invitation while nothing is chosen.
 */
private fun modelName(chosen: String, models: List<PolishModel>): String {
    models.firstOrNull { it.id == chosen }?.let { return it.label }
    return if (chosen.isEmpty()) L10n.string("Choose a model") else chosen
}

/** In the app's own language, which the group holds and is rebuilt with. */
private fun languageName(code: String, language: InterfaceLanguage): String = DictationLanguage.name(of = code, language = language)

/**
 * The models the gateway offers, or the failure to list them; nothing at all when there is nothing
 * to ask or the screen has gone. A failure is said in the Model row and leaves the picker alone: the
 * model already chosen still works, and a gateway that answers next time fills the list. Nothing is
 * polished without a model, so the first one the gateway offers is taken rather than leaving the
 * switch inert.
 */
private suspend fun loadModels(model: AppModel, canPolish: Boolean): Result<List<PolishModel>>? {
    val api = model.connection.api
    if (!canPolish || api == null) return null
    return try {
        val answer = api.polishModels()
        if (model.settings.polishModel.isEmpty()) answer.models.firstOrNull()?.let { model.settings.polishModel = it.id }
        Result.success(answer.models)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

/** The list a group keeps across its cell leaving the screen: each model as its id and its label. */
private val PolishModelsSaver = listSaver<List<PolishModel>, String>(
    save = { list -> list.flatMap { listOf(it.id, it.label) } },
    restore = { flat -> flat.chunked(2).map { (id, label) -> PolishModel(id = id, label = label) } },
)
