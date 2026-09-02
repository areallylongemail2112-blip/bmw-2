package com.bmw.assistant.core.ecu.obd

import java.util.ArrayDeque

/**
 * A scripted stand-in for an ELM327 adapter. It splits whatever the transport writes on `\r`,
 * records each command, and queues the scripted reply followed by the `>` prompt — exactly the
 * shape [Elm327Transport] parses.
 *
 * [respond] receives the command with no terminator; return the reply body without the prompt.
 * Returning null means "say nothing", which is how a silent module or a timeout is simulated.
 */
class FakeSerialLink(
    override val label: String = "Fake adapter",
    private val respond: (command: String) -> String?
) : SerialLink {

    /** Every command the transport has written, in order. */
    val commands = mutableListOf<String>()

    private val outgoing = ArrayDeque<Byte>()
    private val partial = StringBuilder()
    private var open = false

    override val isOpen: Boolean get() = open

    override fun open() {
        open = true
    }

    override fun close() {
        open = false
        outgoing.clear()
        partial.setLength(0)
    }

    override fun write(bytes: ByteArray) {
        partial.append(String(bytes, Charsets.ISO_8859_1))
        while (true) {
            val end = partial.indexOf("\r")
            if (end < 0) break
            val command = partial.substring(0, end)
            partial.delete(0, end + 1)
            commands += command
            val reply = respond(command) ?: ""
            queue(if (reply.isEmpty()) "\r>" else "\r$reply\r\r>")
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (outgoing.isEmpty()) return 0
        var count = 0
        while (count < buffer.size && outgoing.isNotEmpty()) {
            buffer[count++] = outgoing.poll()
        }
        return count
    }

    private fun queue(text: String) {
        text.toByteArray(Charsets.ISO_8859_1).forEach { outgoing.add(it) }
    }
}
