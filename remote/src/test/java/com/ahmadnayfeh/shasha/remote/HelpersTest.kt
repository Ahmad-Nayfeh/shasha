package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class QrCodesTest {
    private val url = "http://192.168.1.23:8765/?t=Xy9_abcDEF-0123456789ab"

    @Test
    fun matrix_isSquare_withAQuietZoneAndFinderPatterns() {
        val m = QrCodes.matrix(url)
        assertThat(m.size).isGreaterThan(20)
        m.forEach { row -> assertThat(row.size).isEqualTo(m.size) }
        // 2-cell margin, then the 7x7 finder pattern starts with a dark cell in three corners.
        assertThat(m[0].none { it } && m[1].none { it }).isTrue()
        assertThat(m[2][2]).isTrue()
        assertThat(m[2][m.size - 3]).isTrue()
        assertThat(m[m.size - 3][2]).isTrue()
        assertThat(m[m.size - 3][m.size - 3]).isFalse() // no finder in the bottom-right corner
        assertThat((m.size - 4 - 21) % 4).isEqualTo(0) // QR versions grow by 4 modules
    }

    @Test
    fun matrix_decodesBackToTheText() {
        assertThat(decode(QrCodes.matrix(url))).isEqualTo(url)
        val arabic = "شاشة ✓ http://10.0.0.5:8080/?t=abc"
        assertThat(decode(QrCodes.matrix(arabic))).isEqualTo(arabic)
    }

    @Test
    fun sizeHint_scalesUp() {
        val m = QrCodes.matrix(url, sizeHint = 300)
        assertThat(m.size).isAtLeast(300)
        assertThat(m[0].size).isEqualTo(m.size)
    }

    @Test
    fun emptyText_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { QrCodes.matrix("") }
    }

    private fun decode(m: Array<BooleanArray>): String {
        val scale = 4
        val size = m.size * scale
        val pixels = IntArray(size * size) { i -> if (m[(i / size) / scale][(i % size) / scale]) 0xFF000000.toInt() else -1 }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.CHARACTER_SET to "UTF-8")).text
    }
}

class RemoteUrlsTest {
    @Test
    fun pairingUrl_ipv4() {
        val url = RemoteUrls.pairingUrl(InetAddress.getByName("192.168.1.20"), 8765, "abc_DEF-123")
        assertThat(url).isEqualTo("http://192.168.1.20:8765/?t=abc_DEF-123")
    }

    @Test
    fun pairingUrl_encodesTheToken() {
        assertThat(RemoteUrls.pairingUrl(InetAddress.getByName("10.0.0.2"), 80, "a b+c/="))
            .isEqualTo("http://10.0.0.2:80/?t=a+b%2Bc%2F%3D")
    }

    @Test
    fun pairingUrl_ipv6_isBracketed() {
        assertThat(RemoteUrls.pairingUrl(InetAddress.getByName("fe80::1"), 8080, "t"))
            .isEqualTo("http://[fe80:0:0:0:0:0:0:1]:8080/?t=t")
    }

    @Test
    fun pairingUrl_withAGeneratedToken_needsNoEscaping() {
        val token = RemoteTokens.newPairingToken()
        assertThat(RemoteUrls.pairingUrl(InetAddress.getByName("192.168.0.9"), 9000, token)).endsWith("?t=$token")
    }
}

class RemoteTokensTest {
    @Test
    fun newPairingToken_isUrlSafe_24Chars_andRandom() {
        val tokens = (1..200).map { RemoteTokens.newPairingToken() }
        tokens.forEach { assertThat(it).matches("[A-Za-z0-9_-]{24}") }
        assertThat(tokens.toSet()).hasSize(200)
    }

    @Test
    fun matches_isExact() {
        assertThat(RemoteTokens.matches("abc", "abc")).isTrue()
        assertThat(RemoteTokens.matches("abd", "abc")).isFalse()
        assertThat(RemoteTokens.matches("abc ", "abc")).isFalse()
        assertThat(RemoteTokens.matches("ABC", "abc")).isFalse()
        assertThat(RemoteTokens.matches(null, "abc")).isFalse()
        assertThat(RemoteTokens.matches("", "")).isFalse()
        assertThat(RemoteTokens.matches("abc", "")).isFalse()
    }
}

class LanAddressTest {
    private fun c(name: String, vararg ips: String, up: Boolean = true, loopback: Boolean = false, p2p: Boolean = false) =
        LanAddress.Candidate(name, up, loopback, isVirtual = false, isPointToPoint = p2p, addresses = ips.map { InetAddress.getByName(it) })

    @Test
    fun picksTheSiteLocalIpv4OfWifi() {
        val chosen = LanAddress.choose(listOf(
            c("lo", "127.0.0.1", loopback = true),
            c("dummy0", "10.9.9.9"),
            c("wlan0", "fe80::1", "192.168.1.23"),
        ))
        assertThat(chosen?.hostAddress).isEqualTo("192.168.1.23")
    }

    @Test
    fun prefersWlanAndEth_overOtherInterfaces() {
        val chosen = LanAddress.choose(listOf(c("ap0", "192.168.43.1"), c("eth0", "10.0.0.7")))
        assertThat(chosen?.hostAddress).isEqualTo("10.0.0.7")
    }

    @Test
    fun skipsDownVpnMobileAndWifiDirect() {
        val chosen = LanAddress.choose(listOf(
            c("wlan0", "192.168.1.5", up = false),
            c("tun0", "10.8.0.2", p2p = true),
            c("rmnet_data0", "10.100.1.1"),
            c("p2p-wlan0-0", "192.168.49.1"),
            c("eth0", "172.16.5.4"),
        ))
        assertThat(chosen?.hostAddress).isEqualTo("172.16.5.4")
    }

    @Test
    fun nullWhenThereIsNoPrivateIpv4() {
        assertThat(LanAddress.choose(listOf(c("wlan0", "8.8.8.8", "fe80::2"), c("lo", "127.0.0.1", loopback = true)))).isNull()
        assertThat(LanAddress.choose(emptyList())).isNull()
    }

    @Test
    fun find_onThisMachine_neverReturnsLoopbackOrPublic() {
        val found = LanAddress.find() ?: return
        assertThat(found.isLoopbackAddress).isFalse()
        assertThat(found.isSiteLocalAddress).isTrue()
    }
}

class ParentSessionsTest {
    private var now = 1_000L
    private val sessions = ParentSessions({ now }, ttlMillis = 100, maxSessions = 3)

    @Test
    fun createTouchRemove() {
        val a = sessions.create()
        assertThat(sessions.touch(a)).isTrue()
        assertThat(sessions.touch(a + "x")).isFalse()
        sessions.remove(a)
        assertThat(sessions.touch(a)).isFalse()
    }

    @Test
    fun expiresAfterIdleTtl_touchExtends() {
        val a = sessions.create()
        now += 99
        assertThat(sessions.touch(a)).isTrue()
        now += 99
        assertThat(sessions.touch(a)).isTrue()
        now += 100
        assertThat(sessions.touch(a)).isFalse()
    }

    @Test
    fun evictsTheLeastRecentlyUsed() {
        val a = sessions.create()
        val b = sessions.create()
        val c = sessions.create()
        sessions.touch(a) // b is now the least recently used
        val d = sessions.create()
        assertThat(sessions.touch(b)).isFalse()
        assertThat(listOf(a, c, d).all(sessions::touch)).isTrue()
        assertThat(sessions.size()).isEqualTo(3)
    }

    @Test
    fun clear_dropsEverything() {
        val a = sessions.create()
        sessions.clear()
        assertThat(sessions.touch(a)).isFalse()
    }
}

class ServerMessagesTest {
    @Test
    fun arabicPlurals() {
        assertThat(ServerMessages.wrongPin(1)).endsWith("بقيت محاولة واحدة.")
        assertThat(ServerMessages.wrongPin(2)).endsWith("بقيت محاولتان.")
        assertThat(ServerMessages.wrongPin(4)).endsWith("بقيت 4 محاولات.")
        assertThat(ServerMessages.wrongPin(0)).isEqualTo("رمز PIN غير صحيح.")
        assertThat(ServerMessages.lockedOut(30)).endsWith("بعد دقيقة واحدة.")
        assertThat(ServerMessages.lockedOut(120)).endsWith("بعد دقيقتين.")
        assertThat(ServerMessages.lockedOut(300)).endsWith("بعد 5 دقائق.")
        assertThat(ServerMessages.lockedOut(900)).endsWith("بعد 15 دقيقة.")
    }
}
