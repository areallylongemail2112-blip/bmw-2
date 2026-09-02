package com.bmw.assistant.core.ecu.obd

import com.bmw.assistant.core.ecu.EcuException
import com.bmw.assistant.core.ecu.EcuTransport
import com.bmw.assistant.core.ecu.Hex
import com.bmw.assistant.core.ecu.TesterPresentKeepAlive
import com.bmw.assistant.core.ecu.uds.Uds

/**
 * UDS over an ELM327 / STN11xx / STN22xx OBD adapter (Bluetooth Classic, BLE or WiFi), using
 * BMW's extended-addressed ISO-TP on the D-CAN bus at 500 kbit/s — the same path BimmerCode and
 * BimmerLink use with a vLinker / OBDLink / UniCarScan adapter on an F-series.
 *
 * Adapter setup: protocol 6 (ISO 15765-4, 11-bit, 500 kbit/s) as the physical layer, headers on,
 * automatic formatting and flow control **off** (`ATCAF0` / `ATCFC0`) so this class owns ISO-TP
 * segmentation, the extended-address byte, flow control and padding. Transmit header is 0x6F1;
 * the receive filter is set per module to 0x600 + its diagnostic address.
 *
 * Details that decide whether this works on a real car:
 *  - **Every frame is padded to 8 bytes** ([IsoTp.pad]); F-series modules drop short frames.
 *  - The response window (`ATST`) is deliberately short so the prompt comes back quickly after
 *    a First Frame and our flow control still lands inside the module's N_Bs timer (~1 s).
 *  - Flow-control **block size and WAIT are honoured** when transmitting a segmented request; a
 *    module that asks for 8-frame blocks would otherwise receive a truncated coding write.
 *  - `ATCSM0` leaves CAN acknowledgement on during monitor mode. The OBD D-CAN link is a
 *    two-node bus, so a silent monitor would leave the gateway's frames unacknowledged and drive
 *    its controller error-passive.
 *  - After any timeout the adapter is resynchronised and pending output discarded, so a late
 *    answer can never be handed to the next request.
 *
 * Clone adapters are slow (a 150-byte coding block is ~25 CAN frames each way) and some drop
 * frames under load, which is why every coding write is verified by reading the block back.
 */
class Elm327Transport(
    private val link: SerialLink,
    private val commandTimeoutMs: Int = DEFAULT_COMMAND_TIMEOUT_MS,
    private val pendingTimeoutMs: Int = DEFAULT_PENDING_TIMEOUT_MS
) : EcuTransport {

    private val lock = Any()
    private val keepAlive = TesterPresentKeepAlive { address, request -> rawTransceive(address, request) }

    @Volatile private var currentEcu = -1
    @Volatile private var adapterId: String = ""
    @Volatile private var ready = false
    /** Set when a command times out: the adapter may still print the previous answer. */
    @Volatile private var dirty = false

    override val isConnected: Boolean get() = ready && link.isOpen
    override val supportsCoding: Boolean get() = true
    override val supportsDiagnostics: Boolean get() = true
    override val description: String
        get() = link.label + (if (adapterId.isNotEmpty()) " ($adapterId)" else "")
    override val maxRequestLength: Int get() = IsoTp.MAX_PAYLOAD

    /** Adapter firmware banner returned by ATZ (e.g. `ELM327 v1.5`, `STN1170 v4.x`). */
    val adapterIdentity: String get() = adapterId

    override fun connect() {
        try {
            link.open()
            // Wake the adapter and drop any half-typed command.
            link.write("\r".toByteArray())
            drain(WAKE_DRAIN_MS)

            adapterId = command("ATZ", RESET_TIMEOUT_MS)
                .lines().map { it.trim() }.lastOrNull { it.isNotBlank() } ?: ""
            command("ATE0")            // echo off
            command("ATL0")            // no line feeds
            command("ATS0")            // no spaces in responses
            command("ATH1")            // headers on: we need the CAN id to attribute frames
            command("ATAL")            // allow long (8-byte) messages
            command("ATCAF0")          // raw CAN — ISO-TP is done here, not by the adapter
            command("ATCFC0")          // no automatic flow control
            command("ATAT0")           // fixed timing
            command("ATST%02X".format(RESPONSE_WINDOW_UNITS))
            command("ATSP6")           // ISO 15765-4, 11-bit, 500 kbit/s
            optionalCommand("ATCSM0")  // acknowledge CAN frames while monitoring (2-node bus)
            command("ATSH%03X".format(IsoTp.TESTER_CAN_ID))
            currentEcu = -1
            dirty = false
            ready = true

            // Prove the bus is alive: the central gateway answers TesterPresent.
            val response = rawTransceive(ZGW_ADDRESS, Uds.testerPresent())
            if (!Uds.isPositive(response, Uds.SID_TESTER_PRESENT) &&
                Uds.negativeResponseCode(response) == null
            ) {
                throw EcuException(
                    "The gateway did not answer over this adapter (" + Hex.encode(response) + "). " +
                        "Check the ignition is on and the adapter is fully seated."
                )
            }
            keepAlive.pin(ZGW_ADDRESS)
            keepAlive.start()
        } catch (e: Exception) {
            ready = false
            keepAlive.stop()
            runCatching { link.close() }
            if (e is EcuException) throw e
            throw EcuException("OBD adapter setup failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    override fun disconnect() {
        keepAlive.stop()
        synchronized(lock) {
            ready = false
            runCatching { command("ATPC", CLOSE_TIMEOUT_MS) } // protocol close
            runCatching { link.close() }
        }
    }

    override fun transceive(diagAddress: Int, request: ByteArray): ByteArray {
        keepAlive.touch(diagAddress)
        return rawTransceive(diagAddress, request)
    }

    private fun rawTransceive(diagAddress: Int, request: ByteArray): ByteArray = synchronized(lock) {
        try {
            transceiveLocked(diagAddress, request)
        } catch (e: EcuException) {
            throw e
        } catch (e: Exception) {
            throw EcuException("OBD adapter error: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun transceiveLocked(diagAddress: Int, request: ByteArray): ByteArray {
        if (!isConnected) throw EcuException("OBD adapter not connected")
        if (request.isEmpty()) throw EcuException("Empty UDS request")
        if (request.size > maxRequestLength) {
            throw EcuException("Request of ${request.size} bytes exceeds the ISO-TP limit of $maxRequestLength")
        }
        if (dirty) resynchronise()

        selectEcu(diagAddress)
        val ecuId = IsoTp.ecuCanId(diagAddress)
        val frames = IsoTp.buildFrames(diagAddress, request)
        val received = if (frames.size == 1) {
            sendAndCollect(frames[0], ecuId)
        } else {
            sendSegmentedRequest(diagAddress, ecuId, frames)
        }
        return awaitResponse(diagAddress, ecuId, received)
    }

    /**
     * Streams a multi-frame request, honouring the module's flow control. Consecutive frames are
     * sent without waiting for a reply (`ATR0`) except where the block size says a new flow
     * control is due, or for the final frame whose reply is the module's answer.
     */
    private fun sendSegmentedRequest(
        diagAddress: Int,
        ecuId: Int,
        frames: List<ByteArray>
    ): List<ByteArray> {
        var flowControl = awaitFlowControl(diagAddress, ecuId, sendAndCollect(frames[0], ecuId))
        var creditsLeft = IsoTp.flowControlBlockSize(flowControl).let { if (it == 0) Int.MAX_VALUE else it }
        var separationMs = IsoTp.flowControlStMinMs(flowControl)

        var index = 1
        var streaming = false
        try {
            while (index < frames.size) {
                val isFinal = index == frames.size - 1
                val blockEnds = creditsLeft <= 1 && !isFinal
                if (isFinal || blockEnds) {
                    if (streaming) {
                        command("ATR1")
                        streaming = false
                    }
                    val replies = sendAndCollect(frames[index], ecuId)
                    index++
                    if (isFinal) return replies
                    flowControl = awaitFlowControl(diagAddress, ecuId, replies)
                    creditsLeft = IsoTp.flowControlBlockSize(flowControl).let { if (it == 0) Int.MAX_VALUE else it }
                    separationMs = IsoTp.flowControlStMinMs(flowControl)
                } else {
                    if (!streaming) {
                        command("ATR0") // don't wait for replies while streaming a block
                        streaming = true
                    }
                    command(hex(frames[index]), STREAM_TIMEOUT_MS)
                    index++
                    if (creditsLeft != Int.MAX_VALUE) creditsLeft--
                    if (separationMs > 0) Thread.sleep(separationMs)
                }
            }
        } finally {
            // Never leave the adapter in "no replies" mode: every later request would look
            // like a silent module.
            if (streaming) runCatching { command("ATR1") }
        }
        throw EcuException("Module 0x%02X did not answer the segmented request".format(diagAddress))
    }

    /** Extracts the module's flow control from [replies], waiting for a WAIT to clear. */
    private fun awaitFlowControl(diagAddress: Int, ecuId: Int, replies: List<ByteArray>): ByteArray {
        var frames = replies
        var waits = 0
        while (true) {
            val fc = frames.firstOrNull { IsoTp.pciType(it) == IsoTp.PCI_FLOW_CONTROL }
                ?: throw EcuException(
                    "Module 0x%02X sent no flow control — it may not accept segmented requests".format(diagAddress)
                )
            when (IsoTp.flowStatus(fc)) {
                IsoTp.FS_CONTINUE -> return fc
                IsoTp.FS_OVERFLOW ->
                    throw EcuException("Module 0x%02X rejected the request size (flow control overflow)".format(diagAddress))
                else -> {
                    if (++waits > MAX_FLOW_CONTROL_WAITS) {
                        throw EcuException("Module 0x%02X kept asking us to wait".format(diagAddress))
                    }
                    frames = monitorFrames(ecuId, FLOW_CONTROL_WAIT_MS) { true }
                }
            }
        }
    }

    /**
     * Assembles the module's answer, driving flow control for a segmented response and waiting
     * out any "response pending" (0x78).
     */
    private fun awaitResponse(diagAddress: Int, ecuId: Int, initial: List<ByteArray>): ByteArray {
        var pending = initial.toMutableList()
        val deadline = System.currentTimeMillis() + pendingTimeoutMs
        while (true) {
            val assembler = IsoTp.Reassembler()
            var sawAny = false
            var index = 0
            while (index < pending.size) {
                val data = pending[index]
                index++
                if (data.isEmpty() || (data[0].toInt() and 0xFF) != IsoTp.TESTER_ADDRESS) continue
                if (IsoTp.pciType(data) == IsoTp.PCI_FLOW_CONTROL) continue
                sawAny = true
                val needsFlowControl = assembler.feed(data)
                if (needsFlowControl && !assembler.isComplete) {
                    pending.addAll(sendAndCollect(IsoTp.flowControl(diagAddress), ecuId))
                }
                if (assembler.isComplete) break
            }
            if (!sawAny) throw EcuException("No response from module 0x%02X".format(diagAddress))
            if (!assembler.isComplete) {
                throw EcuException("Incomplete answer from module 0x%02X — try a faster adapter".format(diagAddress))
            }
            val uds = assembler.payload
            if (Uds.negativeResponseCode(uds) != NRC_RESPONSE_PENDING) return uds

            // "Response pending": the module is busy. Anything already received after the 0x78
            // may be the real answer — keep those frames before falling back to monitoring.
            val leftover = pending.drop(index).toMutableList()
            if (leftover.any { it.isNotEmpty() && (it[0].toInt() and 0xFF) == IsoTp.TESTER_ADDRESS && !IsoTp.isResponsePending(it) }) {
                pending = leftover
                continue
            }
            if (System.currentTimeMillis() > deadline) {
                throw EcuException("Module 0x%02X stayed busy for too long".format(diagAddress))
            }
            val remaining = (deadline - System.currentTimeMillis()).toInt()
                .coerceIn(MIN_MONITOR_MS, pendingTimeoutMs)
            pending = monitorFrames(ecuId, remaining) { !IsoTp.isResponsePending(it) }.toMutableList()
            if (pending.isEmpty()) {
                throw EcuException("Module 0x%02X never finished the request".format(diagAddress))
            }
        }
    }

    /** Sends one raw CAN data field and returns every frame from [ecuId] printed in reply. */
    private fun sendAndCollect(frame: ByteArray, ecuId: Int): List<ByteArray> {
        val text = command(hex(frame))
        checkAdapterErrors(text)
        return text.lines().mapNotNull { IsoTp.parseElmLine(it) }
            .filter { it.id == ecuId }
            .map { it.data }
    }

    /**
     * `ATMA` monitor mode: prints every frame passing the receive filter until we send a
     * character. Used when a module needs longer than the adapter's response window, which the
     * normal request/response cycle cannot wait out.
     */
    private fun monitorFrames(ecuId: Int, timeoutMs: Int, accept: (ByteArray) -> Boolean): List<ByteArray> {
        link.write("ATMA\r".toByteArray())
        val collected = ArrayList<ByteArray>()
        val text = StringBuilder()
        val buffer = ByteArray(READ_BUFFER)
        val deadline = System.currentTimeMillis() + timeoutMs
        var done = false
        try {
            while (!done && System.currentTimeMillis() < deadline) {
                val read = link.read(buffer, MONITOR_POLL_MS)
                if (read <= 0) continue
                text.append(String(buffer, 0, read, Charsets.ISO_8859_1))
                if (text.contains("BUFFER FULL") || text.contains("CAN ERROR") || text.contains("STOPPED")) break
                var newline = text.indexOf("\r")
                while (newline >= 0) {
                    val line = text.substring(0, newline)
                    text.delete(0, newline + 1)
                    val frame = IsoTp.parseElmLine(line)
                    if (frame != null && frame.id == ecuId &&
                        frame.data.isNotEmpty() &&
                        (frame.data[0].toInt() and 0xFF) == IsoTp.TESTER_ADDRESS &&
                        accept(frame.data)
                    ) {
                        collected += frame.data
                        // A single frame is the whole answer; a first frame needs flow control,
                        // which the caller sends as soon as we are out of monitor mode.
                        done = true
                    }
                    newline = text.indexOf("\r")
                }
            }
        } finally {
            // Any character stops monitoring; wait for the prompt to come back.
            runCatching {
                link.write("\r".toByteArray())
                readUntilPrompt(MONITOR_EXIT_TIMEOUT_MS)
            }
        }
        return collected
    }

    /** Reconfigures the receive filter for a different module (cheap, so done lazily). */
    private fun selectEcu(diagAddress: Int) {
        if (diagAddress == currentEcu) return
        command("ATCRA%03X".format(IsoTp.ecuCanId(diagAddress)))
        currentEcu = diagAddress
    }

    private fun checkAdapterErrors(text: String) {
        when {
            text.contains("CAN ERROR") ->
                throw EcuException("CAN bus error — is the ignition on and the adapter seated?")
            text.contains("BUFFER FULL") ->
                throw EcuException("Adapter buffer overflow — this dongle is too slow; use an STN-based adapter")
            text.contains("UNABLE TO CONNECT") ->
                throw EcuException("The adapter could not join the CAN bus")
            text.contains("BUS INIT") && text.contains("ERROR") ->
                throw EcuException("The adapter could not initialise the bus")
        }
    }

    // --- low-level command plumbing ---

    private fun command(cmd: String, timeoutMs: Int = commandTimeoutMs): String {
        link.write((cmd + "\r").toByteArray(Charsets.ISO_8859_1))
        val reply = readUntilPrompt(timeoutMs)
        if (reply.trim() == "?") throw EcuException("The adapter rejected $cmd (unsupported firmware?)")
        return reply
    }

    /** A setup command that older clones may not implement; a rejection is not fatal. */
    private fun optionalCommand(cmd: String) {
        runCatching { command(cmd) }
    }

    /**
     * Reads until the `>` prompt. On timeout the adapter is marked out of sync so the next
     * request drains whatever arrives late instead of attributing it to that request.
     */
    private fun readUntilPrompt(timeoutMs: Int): String {
        val text = StringBuilder()
        val buffer = ByteArray(READ_BUFFER)
        val deadline = System.currentTimeMillis() + timeoutMs
        var sawPrompt = false
        while (System.currentTimeMillis() < deadline) {
            val slice = (deadline - System.currentTimeMillis()).coerceIn(1, READ_POLL_MS.toLong()).toInt()
            val read = link.read(buffer, slice)
            if (read > 0) {
                text.append(String(buffer, 0, read, Charsets.ISO_8859_1))
                if (text.indexOf(">") >= 0) {
                    sawPrompt = true
                    break
                }
            }
        }
        if (!sawPrompt) dirty = true
        return text.toString().replace(">", "").replace(" ", "").trim()
    }

    /** Recovers the command channel after a timeout: stop anything running, drain, re-prompt. */
    private fun resynchronise() {
        dirty = false
        runCatching {
            link.write("\r".toByteArray())
            drain(RESYNC_DRAIN_MS)
            link.write("\r".toByteArray())
            readUntilPrompt(RESYNC_PROMPT_TIMEOUT_MS)
        }
        // A resync loses the filter state the adapter had; force it to be set again.
        currentEcu = -1
        dirty = false
    }

    private fun drain(ms: Int) {
        val buffer = ByteArray(READ_BUFFER)
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (link.read(buffer, DRAIN_POLL_MS) <= 0) break
        }
    }

    private fun hex(bytes: ByteArray): String = Hex.encodeCompact(bytes)

    companion object {
        /** Central gateway (ZGW) diagnostic address on F-series cars. */
        const val ZGW_ADDRESS = 0x10
        const val NRC_RESPONSE_PENDING = 0x78

        const val DEFAULT_COMMAND_TIMEOUT_MS = 2500
        const val DEFAULT_PENDING_TIMEOUT_MS = 30_000

        /**
         * `ATST` units of 4 ms. 200 ms is long enough for a module to answer a request and short
         * enough that the prompt returns quickly after a First Frame, so our flow control still
         * reaches the module inside its ~1 s N_Bs timer.
         */
        private const val RESPONSE_WINDOW_UNITS = 0x32

        private const val RESET_TIMEOUT_MS = 4000
        private const val CLOSE_TIMEOUT_MS = 500
        private const val STREAM_TIMEOUT_MS = 500
        private const val WAKE_DRAIN_MS = 300
        private const val RESYNC_DRAIN_MS = 600
        private const val RESYNC_PROMPT_TIMEOUT_MS = 1500
        private const val MONITOR_EXIT_TIMEOUT_MS = 1500
        private const val MONITOR_POLL_MS = 250
        private const val FLOW_CONTROL_WAIT_MS = 1000
        private const val MAX_FLOW_CONTROL_WAITS = 8
        private const val MIN_MONITOR_MS = 1000
        private const val READ_BUFFER = 512
        private const val READ_POLL_MS = 250
        private const val DRAIN_POLL_MS = 50
    }
}
