package com.bmw.assistant.core.ecu.obd

import com.bmw.assistant.core.ecu.EcuException
import com.bmw.assistant.core.ecu.EcuTransport
import com.bmw.assistant.core.ecu.TesterPresentKeepAlive
import com.bmw.assistant.core.ecu.uds.Uds

/**
 * UDS over an ELM327 / STN11xx / STN22xx OBD adapter (Bluetooth Classic, BLE or WiFi), using
 * BMW's extended-addressed ISO-TP on the D-CAN bus at 500 kbit/s — the same path BimmerCode
 * and BimmerLink use with a vLinker / OBDLink / UniCarScan adapter on an F-series.
 *
 * Adapter setup (from the ELM327 developer notes for BMW): raw CAN mode (`ATCAF0`, headers on),
 * user protocol B = 11-bit 500 kbit/s (`ATPBC101` + `ATSPB`), transmit header 0x6F1, receive
 * filter 0x600 + module address. ISO-TP segmentation, flow control and the extended-address
 * byte are handled here in software, so the transport works on genuine ELM327 v1.4+ chips,
 * STN chips and the common v1.5 clones alike.
 *
 * Limits versus ENET:
 *  - Clone adapters are slow: a 150-byte coding block is ~25 CAN frames each way.
 *  - "Response pending" (0x78) is handled by switching the adapter to monitor mode until the
 *    module's final answer arrives (up to [pendingTimeoutMs]).
 *  - Some very cheap clones drop frames under load — the app verifies every coding write by
 *    reading the block back, so a corrupted transfer is reported rather than silently kept.
 */
class Elm327Transport(
    private val link: SerialLink,
    private val commandTimeoutMs: Int = 2500,
    private val pendingTimeoutMs: Int = 30_000
) : EcuTransport {

    private val lock = Any()
    private val keepAlive = TesterPresentKeepAlive { addr, req -> rawTransceive(addr, req) }
    private var currentEcu = -1
    private var adapterId: String = ""
    @Volatile private var ready = false

    override val isConnected: Boolean get() = ready && link.isOpen
    override val supportsCoding: Boolean get() = true
    override val supportsDiagnostics: Boolean get() = true
    override val description: String get() = link.label + (if (adapterId.isNotEmpty()) " ($adapterId)" else "")
    override val maxRequestLength: Int get() = 4095

    /** Adapter firmware banner returned by ATZ / ATI (e.g. `ELM327 v1.5`, `STN1170 v4.x`). */
    val adapterIdentity: String get() = adapterId

    override fun connect() {
        try {
            link.open()
            // Wake the adapter and drop any half-typed command.
            link.write("\r".toByteArray())
            drain(300)
            adapterId = command("ATZ", 4000).lines().map { it.trim() }.lastOrNull { it.isNotBlank() } ?: ""
            command("ATE0")           // echo off
            command("ATL0")           // no line feeds
            command("ATS0")           // no spaces
            command("ATH1")           // headers on (we need the CAN ID to attribute frames)
            command("ATAL")           // allow long (8-byte) messages
            command("ATCAF0")         // raw CAN: we do ISO-TP ourselves
            command("ATCFC0")         // no automatic flow control
            command("ATAT0")          // fixed timeout
            command("ATST64")         // 0x64 * 4 ms = 400 ms response window
            // User protocol B: 11-bit IDs, 8-byte frames, 500 kbit/s. Then bypass OBD init.
            command("ATPBC101")
            command("ATSPB")
            command("ATBI")
            command("ATSH6F1")        // tester CAN ID
            currentEcu = -1
            ready = true
            // Prove the bus is alive: the central gateway answers TesterPresent.
            val resp = rawTransceive(0x10, Uds.testerPresent())
            if (!Uds.isPositive(resp, Uds.SID_TESTER_PRESENT) && Uds.negativeResponseCode(resp) == null) {
                throw EcuException("Gateway did not answer over the OBD adapter (" + resp.joinToString(" ") { "%02X".format(it) } + ")")
            }
            keepAlive.start()
        } catch (e: Exception) {
            ready = false
            runCatching { link.close() }
            if (e is EcuException) throw e
            throw EcuException("OBD adapter setup failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    override fun disconnect() {
        keepAlive.stop()
        ready = false
        runCatching { command("ATPC", 500) } // protocol close
        runCatching { link.close() }
    }

    override fun transceive(diagAddress: Int, request: ByteArray): ByteArray {
        keepAlive.touch(diagAddress)
        return rawTransceive(diagAddress, request)
    }

    private fun rawTransceive(diagAddress: Int, request: ByteArray): ByteArray = synchronized(lock) {
        transceiveLocked(diagAddress, request)
    }

    private fun transceiveLocked(diagAddress: Int, request: ByteArray): ByteArray {
        if (!isConnected) throw EcuException("OBD adapter not connected")
        try {
            selectEcu(diagAddress)
            val frames = IsoTp.buildFrames(diagAddress, request)
            val ecuId = IsoTp.ecuCanId(diagAddress)
            val received = ArrayList<ByteArray>()

            if (frames.size == 1) {
                received += sendAndCollect(frames[0], ecuId)
            } else {
                // First frame → wait for the module's flow control, then stream consecutive frames.
                val fc = sendAndCollect(frames[0], ecuId).firstOrNull { IsoTp.pciType(it) == IsoTp.PCI_FLOW_CONTROL }
                    ?: throw EcuException("Module 0x%02X sent no flow control for a %d-byte request".format(diagAddress, request.size))
                val fs = fc.getOrNull(1)?.toInt()?.and(0x0F) ?: 0
                if (fs == 2) throw EcuException("Module 0x%02X rejected the request size (flow status overflow)".format(diagAddress))
                val stMin = fc.getOrNull(3)?.toInt()?.and(0xFF) ?: 0
                command("ATR0") // don't wait for replies while streaming
                for (i in 1 until frames.size - 1) {
                    command(hex(frames[i]), 500)
                    if (stMin in 1..0x7F) Thread.sleep(stMin.toLong())
                }
                command("ATR1")
                received += sendAndCollect(frames.last(), ecuId)
            }

            return reassemble(diagAddress, ecuId, received)
        } catch (e: EcuException) {
            throw e
        } catch (e: Exception) {
            throw EcuException("OBD adapter error: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** Collects the frames the module sends after [frame]; drives flow control and 0x78 waits. */
    private fun reassemble(diagAddress: Int, ecuId: Int, initial: List<ByteArray>): ByteArray {
        var pending = initial.toMutableList()
        val deadline = System.currentTimeMillis() + pendingTimeoutMs
        while (true) {
            val asm = IsoTp.Reassembler()
            var sawAny = false
            var i = 0
            while (i < pending.size) {
                val data = pending[i]
                i++
                if (data.isEmpty() || (data[0].toInt() and 0xFF) != IsoTp.TESTER_ADDRESS) continue
                if (IsoTp.pciType(data) == IsoTp.PCI_FLOW_CONTROL) continue
                sawAny = true
                val needFc = asm.feed(data)
                if (needFc && !asm.isComplete) {
                    // Ask for the rest of the segmented answer.
                    pending.addAll(sendAndCollect(IsoTp.flowControl(diagAddress), ecuId))
                }
                if (asm.isComplete) break
            }
            if (!sawAny) throw EcuException("No response from module 0x%02X".format(diagAddress))
            if (!asm.isComplete) throw EcuException("Incomplete ISO-TP response from module 0x%02X".format(diagAddress))
            val uds = asm.payload
            if (Uds.negativeResponseCode(uds) != 0x78) return uds
            // Response pending: monitor the bus until the module's final answer shows up.
            if (System.currentTimeMillis() > deadline) throw EcuException("Module 0x%02X stayed busy for too long".format(diagAddress))
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceIn(1000, pendingTimeoutMs)
            pending = monitorForFrame(ecuId, remaining).toMutableList()
        }
    }

    /** Sends one raw CAN data field and returns every frame from [ecuId] printed in reply. */
    private fun sendAndCollect(frame: ByteArray, ecuId: Int): List<ByteArray> {
        val text = command(hex(frame))
        if (text.contains("CAN ERROR")) throw EcuException("CAN bus error — is the ignition on and the adapter seated?")
        if (text.contains("BUFFER FULL")) throw EcuException("Adapter buffer overflow — try a faster adapter (STN chip)")
        if (text.contains("UNABLE TO CONNECT")) throw EcuException("Adapter could not join the CAN bus")
        return text.lines().mapNotNull { IsoTp.parseElmLine(it) }.filter { it.first == ecuId }.map { it.second }
    }

    /**
     * `ATMA` monitor mode: prints every frame passing the receive filter until we send a
     * character. Used only after a "response pending" so the adapter keeps listening beyond its
     * normal response window.
     */
    private fun monitorForFrame(ecuId: Int, timeoutMs: Int): List<ByteArray> {
        link.write("ATMA\r".toByteArray())
        val collected = ArrayList<ByteArray>()
        val sb = StringBuilder()
        val buf = ByteArray(512)
        val deadline = System.currentTimeMillis() + timeoutMs
        var done = false
        while (!done && System.currentTimeMillis() < deadline) {
            val n = link.read(buf, 250)
            if (n <= 0) continue
            sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
            var nl = sb.indexOf("\r")
            while (nl >= 0) {
                val line = sb.substring(0, nl)
                sb.delete(0, nl + 1)
                IsoTp.parseElmLine(line)?.let { (id, data) ->
                    if (id == ecuId && data.isNotEmpty() && (data[0].toInt() and 0xFF) == IsoTp.TESTER_ADDRESS) {
                        val type = IsoTp.pciType(data)
                        val isPending = type == IsoTp.PCI_SINGLE && data.size >= 5 &&
                            (data[2].toInt() and 0xFF) == 0x7F && (data[4].toInt() and 0xFF) == 0x78
                        if (!isPending) {
                            collected += data
                            // A single frame is the whole answer; a first frame needs flow control next.
                            done = true
                        }
                    }
                }
                nl = sb.indexOf("\r")
            }
        }
        // Any character stops monitoring; wait for the prompt to come back.
        link.write("\r".toByteArray())
        readUntilPrompt(1500)
        if (collected.isEmpty()) throw EcuException("Module stayed busy (no final answer within ${timeoutMs / 1000}s)")
        return collected
    }

    /** Reconfigures the receive filter for a different module (cheap, so done lazily). */
    private fun selectEcu(diagAddress: Int) {
        if (diagAddress == currentEcu) return
        command("ATCRA%03X".format(IsoTp.ecuCanId(diagAddress)))
        currentEcu = diagAddress
    }

    // --- low-level command plumbing ---

    private fun command(cmd: String, timeoutMs: Int = commandTimeoutMs): String {
        link.write((cmd + "\r").toByteArray(Charsets.ISO_8859_1))
        val reply = readUntilPrompt(timeoutMs)
        if (reply.trim() == "?") throw EcuException("Adapter rejected command $cmd (unsupported firmware?)")
        return reply
    }

    private fun readUntilPrompt(timeoutMs: Int): String {
        val sb = StringBuilder()
        val buf = ByteArray(512)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = link.read(buf, minOf(250, (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)))
            if (n > 0) {
                sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                if (sb.indexOf(">") >= 0) break
            }
        }
        // Strip the echoed command line (if echo is still on) and the prompt.
        return sb.toString().replace(">", "").replace("\u0000", "").trim()
    }

    private fun drain(ms: Int) {
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (link.read(buf, 50) <= 0) break
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
}
