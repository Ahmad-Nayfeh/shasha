package com.ahmadnayfeh.shasha.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object RemoteTokens {
    private val random = SecureRandom()

    /** A new pairing token for the QR link: 144 random bits, URL-safe, 24 characters. */
    fun newPairingToken(): String = randomToken(18)

    internal fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    /**
     * Constant-time comparison. Both sides are hashed first so neither the content nor the length
     * of the expected secret leaks through timing. An empty [expected] never matches.
     */
    internal fun matches(presented: String?, expected: String): Boolean {
        if (presented.isNullOrEmpty() || expected.isEmpty()) return false
        return MessageDigest.isEqual(sha256(presented), sha256(expected))
    }

    internal fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
}
