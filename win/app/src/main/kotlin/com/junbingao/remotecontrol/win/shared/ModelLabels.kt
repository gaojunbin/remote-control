package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.protocol.AgentOption

/**
 * `web/src/features/chat/modelLabels.ts`: one model-and-effort pair the model chip or the card's
 * name row can end up holding. Either half is null when the agent lists none of that kind.
 */
data class LabelPair(val model: String?, val effort: String?) {
    /** A key no label can collide with, whatever the agent calls its models. */
    val key: String get() = "${model ?: ""} ${effort ?: ""}"

    companion object {
        /**
         * Every combination of the agent's models and effort levels. An agent that lists only one
         * of the two is measured on what it has, and one that lists neither is measured on
         * whatever it is currently drawing.
         */
        fun pairs(models: List<AgentOption>, efforts: List<AgentOption>): List<LabelPair> {
            if (models.isEmpty() && efforts.isEmpty()) return emptyList()
            if (efforts.isEmpty()) return models.map { LabelPair(model = it.label, effort = null) }
            if (models.isEmpty()) return efforts.map { LabelPair(model = null, effort = it.label) }
            return models.flatMap { model -> efforts.map { LabelPair(model = model.label, effort = it.label) } }
        }
    }
}
