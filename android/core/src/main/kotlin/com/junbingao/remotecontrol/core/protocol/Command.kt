package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * One slash command a session offers (protocol 4.11, amendment A27).
 *
 * `name` is what the user types after the slash and what goes back on the wire unchanged.
 * `argument` is a placeholder rather than a value: it names what may follow the command, and is
 * absent when the command takes nothing. `group` says where the command came from — `Built-in`,
 * `Skills`, `Prompts`, `Extensions` — and is absent when the agent draws no distinction.
 *
 * Nothing here is translated. The words are the agent's own, in the language the terminal would
 * print them in.
 */
@Serializable(with = Command.Serializer::class)
data class Command(
    val name: String,
    val description: String,
    val argument: String? = null,
    val group: String? = null,
) {
    val id: String get() = name

    /** What the panel draws and what the field is written with. */
    val slash: String get() = "/$name"

    val takesArgument: Boolean get() = argument?.isEmpty() == false

    /** The text the transcript shows for running this command, which is exactly what the device echoes back as the message (6.3). */
    fun line(argument: String?): String {
        if (argument.isNullOrEmpty()) return slash
        return "$slash $argument"
    }

    @Serializable
    private class Wire(
        val name: String,
        val description: String = "",
        val argument: String? = null,
        val group: String? = null,
    )

    object Serializer : KSerializer<Command> {
        private val wire = Wire.serializer()
        override val descriptor = wire.descriptor

        // An empty string is a device saying nothing, not a placeholder or a section with no
        // name; either would draw a gap the reader cannot read.
        override fun deserialize(decoder: Decoder): Command {
            val command = decoder.decodeSerializableValue(wire)
            return Command(name = command.name, description = command.description,
                           argument = command.argument?.ifEmpty { null },
                           group = command.group?.ifEmpty { null })
        }

        override fun serialize(encoder: Encoder, value: Command) =
            encoder.encodeSerializableValue(wire, Wire(value.name, value.description, value.argument, value.group))
    }
}
