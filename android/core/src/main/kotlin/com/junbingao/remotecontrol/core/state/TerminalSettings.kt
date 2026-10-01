package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentLabel
import com.junbingao.remotecontrol.core.protocol.Session
import java.util.Objects

/**
 * Amendment A17: a setting a terminal chose for a session this app may not retune, ready to be
 * drawn where its control would stand.
 *
 * The device reads the model, the permission mode, the effort and the speed out of the agent's own
 * transcript and publishes them as `meta`, so the values are known even where no request could
 * change them. Showing them beats an empty gap: a person on the phone can otherwise not tell which
 * model the terminal is running.
 */
class TerminalSetting(
    val field: Field,
    /**
     * The agent's own labels for the ids, or the raw ids when its lists do not know them — an
     * `auto` permission mode read from a transcript is shown as `auto` rather than dropped.
     */
    val text: String,
    /**
     * Amendment A21: the tier's label while one is on, drawn as the bolt at the gauge's corner
     * (A44). Null on the standard speed and on every other control.
     */
    val speed: String? = null,
    spokenValue: String? = null,
) {
    /** Which control this stands in for. The raw value is the identifier suffix the checks and the accessibility tree use. */
    enum class Field(val rawValue: String) {
        /** Amendment A21: model, effort and speed are one control, so they are one chip here too. */
        modelCard("modelCard"),
        permissionMode("permissionMode");

        /** What assistive technology calls it. The same word the live control uses, because the chip stands exactly where that control would. */
        val label: String
            get() = when (this) {
                modelCard -> L10n.string("Model")
                permissionMode -> L10n.string("Permissions")
            }

        companion object {
            val allCases: List<Field> get() = entries

            operator fun invoke(rawValue: String): Field? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /**
     * What assistive technology reads as the control's value. Amendment A44: on the phone the
     * control is an icon, so the value says in words what the icon draws — for the model card,
     * [modelCardSpoken].
     */
    val spokenValue: String = spokenValue ?: text

    val id: String get() = this.field.rawValue

    override fun equals(other: Any?): Boolean = other is TerminalSetting && field == other.field &&
        text == other.text && speed == other.speed && spokenValue == other.spokenValue

    override fun hashCode(): Int = Objects.hash(field, text, speed, spokenValue)

    override fun toString(): String = "TerminalSetting(field=$field, text=$text, speed=$speed, spokenValue=$spokenValue)"

    companion object {
        /**
         * Amendment A21: the words for the model card — the model label with the effort word after
         * it, in whichever of the two the device has reported. A terminal-held session's menu shows
         * them as the value it set (A44).
         */
        fun modelCardText(session: Session, agent: AgentInfo?): String =
            listOfNotNull(agent?.modelLabel(session.model) ?: session.model, effortText(session, agent)).joinToString(" ")

        /**
         * Amendment A44: the model card's value in words — "Opus 4.6, effort High" — with the tier's
         * name after it while one is on. The gauge draws the effort and its bolt says "faster tier"
         * to the eye, and neither says anything at all to a screen reader. The live control and the
         * value a terminal set read a session the same way.
         */
        fun modelCardSpoken(session: Session, agent: AgentInfo?): String {
            val model = agent?.modelLabel(session.model) ?: session.model ?: AgentLabel.name(session.agent)
            val spoken = effortText(session, agent)?.let { L10n.string("%@, effort %@", model, it) } ?: model
            val tier = agent?.speedLabel(session.speed) ?: session.speed ?: return spoken
            return "$spoken, $tier"
        }

        /**
         * The chips for a session, in the order the live controls stand in. A value the device has
         * not seen is left out entirely rather than drawn as a placeholder, so an agent that reports
         * no effort shows the model alone.
         */
        fun all(session: Session, agent: AgentInfo?): List<TerminalSetting> {
            val settings = mutableListOf<TerminalSetting>()
            val card = modelCardText(session, agent)
            if (card.isNotEmpty()) {
                settings.add(TerminalSetting(field = Field.modelCard, text = card,
                                             speed = agent?.speedLabel(session.speed) ?: session.speed,
                                             spokenValue = modelCardSpoken(session, agent)))
            }
            permissionText(session, agent)?.let { settings.add(TerminalSetting(field = Field.permissionMode, text = it)) }
            return settings
        }

        /**
         * Amendment A25: an agent that lists no effort levels has no such setting, so the card reads
         * the model alone even where a session carries a value. An agent this app has never met keeps
         * the raw id, as A17 asks.
         */
        fun effortText(session: Session, agent: AgentInfo?): String? {
            if (agent == null) return session.effort
            if (agent.efforts.isEmpty()) return null
            return agent.effortLabel(session.effort) ?: session.effort
        }

        /**
         * Amendment A25: an agent that lists no permission modes has no permission system (pi), so no
         * chip stands in for the picker it never had.
         */
        fun permissionText(session: Session, agent: AgentInfo?): String? {
            if (agent == null) return session.permissionMode
            if (agent.permissionModes.isEmpty()) return null
            return agent.permissionModeLabel(session.permissionMode) ?: session.permissionMode
        }
    }
}
