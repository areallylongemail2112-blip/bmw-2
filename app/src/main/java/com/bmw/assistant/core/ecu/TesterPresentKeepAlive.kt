package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Uds
import java.util.concurrent.ConcurrentHashMap

/**
 * Background TesterPresent (0x3E) pump shared by the hardware transports.
 *
 * A BMW module drops back from the extended diagnostic session to the default session when it
 * hears nothing from the tester for ~5 s (the ISO 14229 S3 timer), and the central gateway
 * closes an idle TCP session outright. The app's coding flow is "read block for backup → let the
 * user confirm → write block", which easily exceeds both, so every module addressed in the last
 * [trackWindowMs] receives a TesterPresent every [intervalMs] while the link is idle.
 *
 * [send] is the transport's own (synchronised) transceive, so keep-alives never interleave with
 * a real request.
 *
 * Two lifecycle details matter:
 *  - The loop runs on a [running] flag, not the thread's interrupt status. A transport's
 *    blocking read may swallow an `InterruptedException` (clearing the flag) and wrap it in an
 *    [EcuException]; relying on `isInterrupted` would leave a zombie thread pinging a closed
 *    link forever. [stop] also joins the thread, so no ping is in flight when the socket closes.
 *  - A module that fails [MAX_CONSECUTIVE_FAILURES] pings in a row is dropped, so one asleep
 *    module cannot hold the transport lock through a full timeout every interval.
 */
class TesterPresentKeepAlive(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val trackWindowMs: Long = DEFAULT_TRACK_WINDOW_MS,
    private val send: (diagAddress: Int, request: ByteArray) -> ByteArray
) {
    private val lastSeen = ConcurrentHashMap<Int, Long>()
    private val failures = ConcurrentHashMap<Int, Int>()
    /** Modules that must be pinged for the whole session regardless of [trackWindowMs]. */
    private val pinned = ConcurrentHashMap.newKeySet<Int>()

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    val isRunning: Boolean get() = running && thread?.isAlive == true

    /** Record that [diagAddress] was just addressed by a real request. */
    fun touch(diagAddress: Int) {
        lastSeen[diagAddress] = System.currentTimeMillis()
        failures.remove(diagAddress)
    }

    /** Keeps [diagAddress] in the ping set for the whole session (used for the gateway). */
    fun pin(diagAddress: Int) {
        pinned.add(diagAddress)
        touch(diagAddress)
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        running = true
        val t = Thread({ pump() }, "uds-tester-present")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Stops the pump and waits (bounded) for the thread to exit. Safe to call repeatedly. */
    @Synchronized
    fun stop() {
        running = false
        val t = thread ?: return
        thread = null
        t.interrupt()
        if (Thread.currentThread() !== t) runCatching { t.join(JOIN_TIMEOUT_MS) }
        lastSeen.clear()
        failures.clear()
        pinned.clear()
    }

    private fun pump() {
        while (running) {
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            val now = System.currentTimeMillis()
            for ((address, seen) in lastSeen.entries.toList()) {
                if (!running) return
                if (now - seen > trackWindowMs && address !in pinned) {
                    lastSeen.remove(address)
                    failures.remove(address)
                    continue
                }
                // Only ping modules that have been idle for a full interval.
                if (now - seen < intervalMs) continue
                try {
                    send(address, Uds.testerPresent())
                    lastSeen[address] = System.currentTimeMillis()
                    failures.remove(address)
                } catch (e: Exception) {
                    // A wrapped interrupt means stop() was called — leave immediately.
                    if (e is InterruptedException || e.cause is InterruptedException) return
                    // Otherwise: link problems surface on the next real request. Give up on a
                    // module that keeps failing so it cannot stall every interval.
                    val count = (failures[address] ?: 0) + 1
                    if (count >= MAX_CONSECUTIVE_FAILURES && address !in pinned) {
                        lastSeen.remove(address)
                        failures.remove(address)
                    } else {
                        failures[address] = count
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 2000L
        const val DEFAULT_TRACK_WINDOW_MS = 60_000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val JOIN_TIMEOUT_MS = 6000L
    }
}
