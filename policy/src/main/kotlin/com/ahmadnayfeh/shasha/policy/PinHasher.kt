package com.ahmadnayfeh.shasha.policy

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashes the parent PIN as `pbkdf2$<iterations>$<saltBase64>$<hashBase64>` (PBKDF2WithHmacSHA256,
 * 16-byte random salt, 256-bit key). The iteration count travels with the hash, so it can be raised
 * later without breaking stored PINs.
 */
class PinHasher(
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = ITERATIONS,
) {
    init {
        require(iterations > 0) { "iterations must be > 0" }
    }

    fun hash(pin: String): String {
        require(validatePinFormat(pin)) { "PIN must be $MIN_LENGTH to $MAX_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(pin, salt, iterations)
        return listOf(PREFIX, iterations.toString(), b64(salt), b64(hash)).joinToString(SEPARATOR)
    }

    /** False for a wrong PIN and for any malformed [encoded] value; never throws. */
    fun verify(pin: String, encoded: String): Boolean {
        if (!validatePinFormat(pin)) return false
        val parsed = parse(encoded) ?: return false
        val actual = derive(pin, parsed.salt, parsed.iterations)
        return MessageDigest.isEqual(actual, parsed.hash) // constant-time compare
    }

    /** True when [encoded] was made with fewer iterations than this hasher uses (or is unreadable). */
    fun needsRehash(encoded: String): Boolean {
        val parsed = parse(encoded) ?: return true
        return parsed.iterations < iterations
    }

    private class Parsed(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(encoded: String): Parsed? {
        val parts = encoded.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..MAX_ITERATIONS } ?: return null
        return try {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(parts[2])
            val hash = decoder.decode(parts[3])
            if (salt.isEmpty() || hash.isEmpty()) null else Parsed(iterations, salt, hash)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val ITERATIONS = 60_000
        const val SALT_BYTES = 16
        const val KEY_BITS = 256
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 8
        private const val PREFIX = "pbkdf2"
        private const val SEPARATOR = "$"

        // Guards against a tampered backup forcing a practically endless derivation.
        private const val MAX_ITERATIONS = 10_000_000

        /** 4 to 8 ASCII digits (0-9 only; the app uses Western digits everywhere). */
        fun validatePinFormat(pin: String): Boolean =
            pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
    }
}
