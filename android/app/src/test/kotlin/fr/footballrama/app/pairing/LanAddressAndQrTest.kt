package fr.footballrama.app.pairing

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress

class LanAddressAndQrTest {

    private fun ip(s: String) = InetAddress.getByName(s)
    private fun c(name: String, addr: String, up: Boolean = true, loop: Boolean = false) = LanAddress.Candidate(name, ip(addr), up, loop)

    @Test
    fun `adresse privee IPv4 - Ethernet puis Wi-Fi, jamais VPN, mobile, publique ou IPv6`() {
        assertEquals(ip("192.168.1.11"), LanAddress.choose(listOf(c("wlan0", "192.168.1.12"), c("eth0", "192.168.1.11"))))
        assertEquals(ip("192.168.1.12"), LanAddress.choose(listOf(c("tun0", "10.8.0.2"), c("wlan0", "192.168.1.12"))))
        assertEquals(ip("10.0.0.5"), LanAddress.choose(listOf(c("rmnet0", "10.20.0.1"), c("wlan0", "10.0.0.5"))))
        assertNull(LanAddress.choose(listOf(c("lo", "127.0.0.1", loop = true), c("wlan0", "fe80::1"), c("eth0", "82.64.1.2"))))
        assertNull(LanAddress.choose(listOf(c("eth0", "192.168.1.11", up = false))))
        assertNull(LanAddress.choose(emptyList()))
    }

    @Test
    fun `plages privees RFC 1918`() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.1").forEach {
            assertTrue(LanAddress.isPrivateIpv4(ip(it) as Inet4Address), it)
        }
        listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "8.8.8.8", "100.64.0.1").forEach {
            assertFalse(LanAddress.isPrivateIpv4(ip(it) as Inet4Address), it)
        }
    }

    @Test
    fun `QR code decodable, zone de silence blanche, noir sur blanc`() {
        val url = "http://192.168.1.11:8095/c/AbCdEfGhIjKlMnOpQrStUv"
        val m = QrMatrix.encode(url)
        assertTrue(m.size in 21 + 8..57 + 8)
        for (i in 0 until m.size) { // marge de 4 modules blancs
            assertFalse(m[i, 0]); assertFalse(m[0, i]); assertFalse(m[i, m.size - 1]); assertFalse(m[m.size - 1, i])
        }
        // Agrandi x4 puis relu par le decodeur ZXing.
        val scale = 4
        val w = m.size * scale
        val pixels = IntArray(w * w) { i -> if (m[(i % w) / scale, (i / w) / scale]) QrMatrix.BLACK else QrMatrix.WHITE }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, pixels)))
        assertEquals(url, QRCodeReader().decode(bitmap).text)
        assertEquals(setOf(QrMatrix.BLACK, QrMatrix.WHITE), m.toArgb().toSet())
    }
}
