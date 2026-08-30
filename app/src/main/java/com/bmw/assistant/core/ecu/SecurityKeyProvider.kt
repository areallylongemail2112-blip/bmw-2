package com.bmw.assistant.core.ecu

/**
 * Turns a SecurityAccess seed into a key. The UDS 0x27 framing is standard; the
 * seed-to-key function is module-specific and is **not** bundled here.
 *
 * Hardware writes that hit NRC 0x33 will call this if one is registered. The
 * default [XorSecurityKeyProvider] is a reversible demo/test algorithm
 * (`key[i] = seed[i] XOR 0xFF`) — it is not a BMW algorithm and is only used
 * by the demo transport and unit tests.
 */
fun interface SecurityKeyProvider {
    fun keyFor(diagAddress: Int, level: Int, seed: ByteArray): ByteArray?
}

/** Demo/test-only seed-to-key. Never used as a default on hardware. */
object XorSecurityKeyProvider : SecurityKeyProvider {
    override fun keyFor(diagAddress: Int, level: Int, seed: ByteArray): ByteArray =
        ByteArray(seed.size) { i -> (seed[i].toInt() xor 0xFF).toByte() }
}
