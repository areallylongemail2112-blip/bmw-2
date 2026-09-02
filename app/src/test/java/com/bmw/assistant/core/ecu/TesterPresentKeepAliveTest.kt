package com.bmw.assistant.core.ecu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The keep-alive pump holds the transport lock and talks to the car on its own schedule, so its
 * failure modes are a stuck UI or a thread that keeps pinging a link the user already closed.
 */
class TesterPresentKeepAliveTest {

    private fun keepAlive(
        intervalMs: Long = 30,
        trackWindowMs: Long = 5000,
        send: (Int, ByteArray) -> ByteArray
    ) = TesterPresentKeepAlive(intervalMs, trackWindowMs, send)

    @Test
    fun pingsOnlyModulesThatHaveBeenAddressed() {
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        val pump = keepAlive { address, request ->
            seen += address
            assertArrayEquals(byteArrayOf(0x3E, 0x00), request)
            byteArrayOf(0x7E, 0x00)
        }

        pump.touch(0x72)
        pump.start()
        try {
            waitUntil { seen.isNotEmpty() }
        } finally {
            pump.stop()
        }

        assertTrue(seen.all { it == 0x72 })
    }

    @Test
    fun pingsNothingWhenNoModuleHasBeenAddressed() {
        val calls = AtomicInteger()
        val pump = keepAlive { _, _ -> calls.incrementAndGet(); ByteArray(0) }

        pump.start()
        Thread.sleep(200)
        pump.stop()

        assertTrue(calls.get() == 0)
    }

    @Test
    fun stopTerminatesTheThreadEvenWhenSendWrapsAnInterrupt() {
        // This is the zombie-thread case: a transport catches InterruptedException, clears the
        // interrupt flag, and rethrows it wrapped. A pump that trusted the flag would spin on a
        // closed link forever.
        val entered = CountDownLatch(1)
        val pump = keepAlive { _, _ ->
            entered.countDown()
            try {
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                throw EcuException("link error", e)
            }
            ByteArray(0)
        }

        pump.touch(0x72)
        pump.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        pump.stop()

        assertFalse("keep-alive thread outlived stop()", pump.isRunning)
    }

    @Test
    fun givesUpOnAModuleThatKeepsFailing() {
        val attempts = AtomicInteger()
        val pump = keepAlive { _, _ ->
            attempts.incrementAndGet()
            throw EcuException("module asleep")
        }

        pump.touch(0x72)
        pump.start()
        try {
            waitUntil { attempts.get() >= 3 }
            val afterGivingUp = attempts.get()
            Thread.sleep(200)
            assertTrue(
                "pump kept retrying a dead module",
                attempts.get() <= afterGivingUp + 1
            )
        } finally {
            pump.stop()
        }
    }

    @Test
    fun keepsPingingAPinnedModuleBeyondTheTrackingWindow() {
        val calls = AtomicInteger()
        val pump = keepAlive(intervalMs = 30, trackWindowMs = 50) { _, _ ->
            calls.incrementAndGet(); byteArrayOf(0x7E, 0x00)
        }

        pump.pin(FramedTcpTransport.ZGW_ADDRESS)
        pump.start()
        try {
            waitUntil { calls.get() >= 3 }
        } finally {
            pump.stop()
        }

        assertTrue(calls.get() >= 3)
    }

    @Test
    fun startIsIdempotentAndStopIsSafeToRepeat() {
        val pump = keepAlive { _, _ -> ByteArray(0) }
        pump.start()
        pump.start()
        assertTrue(pump.isRunning)
        pump.stop()
        pump.stop()
        assertFalse(pump.isRunning)
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition not met within $timeoutMs ms")
    }
}
