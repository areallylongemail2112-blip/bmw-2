package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Uds
import java.util.concurrent.ConcurrentHashMap

/**
 * Background TesterPresent (0x3E) pump shared by the hardware transports.
 *
 * A BMW module drops back from the extended diagnostic session to the default session when it
 * hears nothing from the tester for ~5 s (the ISO 14229 S3 timer). The app's coding flow is
 * "read block for backup → let the user confirm → write block", which can easily exceed that,
 * so every module we have talked to in the last [trackWindowMs] receives a TesterPresent every
 * [intervalMs] while the link is idle.
 *
 * [send] is the transport's own (synchronised) transceive, so keep-alives never interleave
 * with a real request.
 */
class TesterPresentKeepAlive(
    private val intervalMs: Long = 2000,
    private val trackWindowMs: Long = 60_000,
    private val send: (diagAddress: Int, request: ByteArray) -> ByteArray
) {
    private val lastSeen = ConcurrentHashMap<Int, Long>()
    @Volatile private var thread: Thread? = null

    /** Record that [diagAddress] was just addressed by a real request. */
    fun touch(diagAddress: Int) {
        lastSeen[diagAddress] = System.currentTimeMillis()
    }

    fun start() {
        if (thread != null) return
        val t = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(intervalMs)
                    val now = System.currentTimeMillis()
                    for ((addr, seen) in lastSeen.entries.toList()) {
                        if (now - seen > trackWindowMs) {
                            lastSeen.remove(addr)
                            continue
                        }
                        // Only ping modules that have been idle for a full interval.
                        if (now - seen < intervalMs) continue
                        try {
                            send(addr, Uds.testerPresent())
                        } catch (_: Exception) {
                            // Link problems surface on the next real request; never crash here.
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // stopped
            }
        }, "uds-tester-present")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        thread?.interrupt()
        thread = null
        lastSeen.clear()
    }
}
