package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class PinHasherTest {

    /** Fills every salt with 00 01 02 … 0f so hashes are reproducible. */
    private class CountingRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = i.toByte()
        }
    }

    private val hasher = PinHasher()

    @Test
    fun encodedFormat() {
        val encoded = hasher.hash("1234")
        val parts = encoded.split("$")
        assertThat(parts).hasSize(4)
        assertThat(parts[0]).isEqualTo("pbkdf2")
        assertThat(parts[1]).isEqualTo("60000")
        assertThat(Base64.getDecoder().decode(parts[2])).hasLength(16)
        assertThat(Base64.getDecoder().decode(parts[3])).hasLength(32)
        assertThat(PinHasher.ITERATIONS).isEqualTo(60_000)
        assertThat(PinHasher.ALGORITHM).isEqualTo("PBKDF2WithHmacSHA256")
    }

    @Test
    fun knownAnswer_matchesIndependentPbkdf2() {
        // Python: hashlib.pbkdf2_hmac('sha256', b'2468', bytes(range(16)), 60000, 32)
        val encoded = PinHasher(CountingRandom()).hash("2468")
        assertThat(encoded).isEqualTo("pbkdf2\$60000\$AAECAwQFBgcICQoLDA0ODw==\$CH5RVjbfFlGxiEFI1vj+Rp0cs/edr8m0NvaqwHEc20w=")
        assertThat(hasher.verify("2468", encoded)).isTrue()
    }

    // The fallback for Android 7.1 and older (Fire OS 6), whose provider has no PBKDF2WithHmacSHA256: it must give the
    // very same hashes, or a PIN set on one TV would not open a backup restored on another.

    @Test
    fun fallback_knownAnswers() {
        // Same vector as above.
        val pin = PinHasher.pbkdf2HmacSha256("2468".toByteArray(), ByteArray(16) { it.toByte() }, 60_000, 32)
        assertThat(Base64.getEncoder().encodeToString(pin)).isEqualTo("CH5RVjbfFlGxiEFI1vj+Rp0cs/edr8m0NvaqwHEc20w=")
        // RFC 7914, section 11 (two blocks of output).
        val rfc = PinHasher.pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertThat(rfc.hex()).isEqualTo(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
        )
        // A length that is not a whole block. Python: hashlib.pbkdf2_hmac('sha256', b'1234', b'NaCl', 3, 40)
        val partial = PinHasher.pbkdf2HmacSha256("1234".toByteArray(), "NaCl".toByteArray(), 3, 40)
        assertThat(partial.hex()).isEqualTo("382727dd65cde94673ca26a5431131c2ecc0b2d2b0ae9d6676f6aad6ea798bc72c54c9366f660d9b")
    }

    @Test
    fun fallback_matchesThePlatformDerivation() {
        val random = java.util.Random(7)
        repeat(20) {
            val pin = (1..(4 + random.nextInt(5))).joinToString("") { random.nextInt(10).toString() }
            val salt = ByteArray(16).also(random::nextBytes)
            val iterations = 1 + random.nextInt(300)
            val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            val platform = javax.crypto.SecretKeyFactory.getInstance(PinHasher.ALGORITHM).generateSecret(spec).encoded
            assertThat(PinHasher.pbkdf2HmacSha256(pin.toByteArray(), salt, iterations, 32)).isEqualTo(platform)
        }
    }

    @Test
    fun fallback_verifiesAStoredHash() {
        val encoded = PinHasher(iterations = 1_000).hash("90210")
        val parts = encoded.split("$")
        val derived = PinHasher.pbkdf2HmacSha256("90210".toByteArray(), Base64.getDecoder().decode(parts[2]), 1_000, 32)
        assertThat(Base64.getEncoder().encodeToString(derived)).isEqualTo(parts[3])
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun verify_rightAndWrongPin() {
        val encoded = hasher.hash("90210")
        assertThat(hasher.verify("90210", encoded)).isTrue()
        assertThat(hasher.verify("90211", encoded)).isFalse()
        assertThat(hasher.verify("0902", encoded)).isFalse()
    }

    @Test
    fun saltIsRandom() {
        val a = hasher.hash("1234")
        val b = hasher.hash("1234")
        assertThat(a).isNotEqualTo(b)
        assertThat(hasher.verify("1234", a)).isTrue()
        assertThat(hasher.verify("1234", b)).isTrue()
    }

    @Test
    fun verify_neverThrowsOnGarbage() {
        val good = hasher.hash("1234")
        val salt = good.split("$")[2]
        val hash = good.split("$")[3]
        val garbage = listOf(
            "",
            "1234",
            "pbkdf2",
            "pbkdf2\$60000\$$salt",
            "pbkdf2\$60000\$$salt\$$hash\$extra",
            "sha1\$60000\$$salt\$$hash",
            "pbkdf2\$abc\$$salt\$$hash",
            "pbkdf2\$0\$$salt\$$hash",
            "pbkdf2\$-5\$$salt\$$hash",
            "pbkdf2\$999999999\$$salt\$$hash",
            "pbkdf2\$60000\$!!notbase64!!\$$hash",
            "pbkdf2\$60000\$$salt\$!!notbase64!!",
            "pbkdf2\$60000\$\$$hash",
            "pbkdf2\$60000\$$salt\$",
        )
        for (value in garbage) {
            assertThat(hasher.verify("1234", value)).isFalse()
        }
    }

    @Test
    fun verify_tamperedHash_fails() {
        val parts = hasher.hash("1234").split("$").toMutableList()
        val bytes = Base64.getDecoder().decode(parts[3])
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        parts[3] = Base64.getEncoder().encodeToString(bytes)
        assertThat(hasher.verify("1234", parts.joinToString("$"))).isFalse()
    }

    @Test
    fun verify_rejectsBadlyFormattedPins() {
        val encoded = hasher.hash("1234")
        assertThat(hasher.verify("", encoded)).isFalse()
        assertThat(hasher.verify("12a4", encoded)).isFalse()
        assertThat(hasher.verify("١٢٣٤", encoded)).isFalse()
    }

    @Test
    fun iterationsTravelWithTheHash() {
        val old = PinHasher(iterations = 1000).hash("4321")
        assertThat(old.split("$")[1]).isEqualTo("1000")
        assertThat(hasher.verify("4321", old)).isTrue()
        assertThat(hasher.needsRehash(old)).isTrue()
        assertThat(hasher.needsRehash(hasher.hash("4321"))).isFalse()
        assertThat(hasher.needsRehash("garbage")).isTrue()
    }

    @Test
    fun hash_rejectsInvalidPins() {
        for (pin in listOf("", "123", "123456789", "12a4", " 1234", "١٢٣٤", "12 34")) {
            assertThrows(IllegalArgumentException::class.java) { hasher.hash(pin) }
        }
    }

    @Test
    fun iterationsMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { PinHasher(iterations = 0) }
    }

    @Test
    fun validatePinFormat() {
        assertThat(PinHasher.validatePinFormat("1234")).isTrue()
        assertThat(PinHasher.validatePinFormat("0000")).isTrue()
        assertThat(PinHasher.validatePinFormat("12345678")).isTrue()
        assertThat(PinHasher.validatePinFormat("123")).isFalse()
        assertThat(PinHasher.validatePinFormat("123456789")).isFalse()
        assertThat(PinHasher.validatePinFormat("12a4")).isFalse()
        assertThat(PinHasher.validatePinFormat("١٢٣٤")).isFalse() // Arabic-Indic digits
        assertThat(PinHasher.validatePinFormat(" 1234")).isFalse()
        assertThat(PinHasher.validatePinFormat("")).isFalse()
    }
}
