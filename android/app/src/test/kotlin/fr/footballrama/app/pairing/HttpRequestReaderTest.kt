package fr.footballrama.app.pairing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException

class HttpRequestReaderTest {

    private fun read(raw: String, limits: HttpLimits = HttpLimits()) =
        HttpRequestReader.read(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)), limits)

    private fun status(raw: String, limits: HttpLimits = HttpLimits()): Int =
        assertThrows(HttpException::class.java) { read(raw, limits) }.status

    @Test
    fun `GET valide`() {
        val r = read("GET /c/abc HTTP/1.1\r\nHost: 10.0.0.2:8090\r\nUser-Agent: test\r\n\r\n")
        assertEquals("GET", r.method)
        assertEquals("/c/abc", r.target)
        assertEquals("10.0.0.2:8090", r.singleHeader("host"))
        assertEquals(0, r.body.size)
    }

    @Test
    fun `POST avec corps lu en plusieurs morceaux`() {
        val body = "code=1234&server=x"
        val raw = "POST /c/t HTTP/1.1\r\nHost: h\r\nContent-Length: ${body.length}\r\n\r\n$body"
        // Flux qui ne rend qu'un octet a la fois.
        val slow = object : InputStream() {
            val src = ByteArrayInputStream(raw.toByteArray())
            override fun read(): Int = src.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else src.read(b, off, 1)
        }
        val r = HttpRequestReader.read(slow)
        assertEquals(body, String(r.body))
    }

    @Test
    fun `en-tetes trop longs - 431`() {
        val big = "X-A: " + "a".repeat(9000)
        assertEquals(431, status("GET / HTTP/1.1\r\nHost: h\r\n$big\r\n\r\n"))
        val many = (1..41).joinToString("") { "X-$it: v\r\n" }
        assertEquals(431, status("GET / HTTP/1.1\r\nHost: h\r\n$many\r\n"))
    }

    @Test
    fun `corps trop gros - 413 sans lire le corps`() {
        assertEquals(413, status("POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 8193\r\n\r\n"))
    }

    @Test
    fun `parsing strict`() {
        assertEquals(400, status("GET / HTTP/1.1\nHost: h\n\n")) // LF seul
        assertEquals(400, status("GET  / HTTP/1.1\r\nHost: h\r\n\r\n")) // double espace
        assertEquals(400, status("get / HTTP/1.1\r\nHost: h\r\n\r\n")) // methode en minuscules
        assertEquals(505, status("GET / HTTP/1.0\r\nHost: h\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: h\r\n folded\r\n\r\n")) // obs-fold
        assertEquals(400, status("GET / HTTP/1.1\r\nBad Header: v\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: h\u0001\r\n\r\n")) // controle
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: hé\r\n\r\n")) // non ASCII
        assertEquals(400, status("GET http://evil/ HTTP/1.1\r\nHost: h\r\n\r\n")) // forme absolue refusee
        assertEquals(414, status("GET /" + "a".repeat(300) + " HTTP/1.1\r\nHost: h\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\na"))
        assertEquals(400, status("POST / HTTP/1.1\r\nHost: h\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(411, status("POST / HTTP/1.1\r\nHost: h\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 2\r\n\r\nabcGET")) // requete en trop
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: h\r\n")) // fin de flux avant la fin des en-tetes
        assertEquals(400, status("POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\nabc")) // corps tronque
    }

    @Test
    fun `date limite depassee - timeout`() {
        val never = object : InputStream() {
            override fun read(): Int = throw AssertionError()
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw AssertionError("ne doit pas lire")
        }
        assertThrows(SocketTimeoutException::class.java) {
            HttpRequestReader.read(never, HttpLimits()) { throw SocketTimeoutException() }
        }
    }

    @Test
    fun `formulaire urlencoded strict`() {
        val f = FormDecoder.decode("code=0042&server=http%3A%2F%2Fa.b%3A80&username=J%C3%A9r%C3%B4me+D".toByteArray(), PairingHandler.FIELDS)
        assertEquals("0042", f["code"])
        assertEquals("http://a.b:80", f["server"])
        assertEquals("Jérôme D", f["username"])
        val bad = listOf(
            "code=1&code=2", // doublon
            "evil=1", // champ inconnu
            "code=%zz", // hexa invalide
            "code=%C3", // UTF-8 tronque
            "code=%0A", // retour a la ligne
            "code", // sans '='
            "code=1&server=2&username=3&password=4&code=5", // trop de champs
        )
        bad.forEach { b -> assertThrows(HttpException::class.java, { FormDecoder.decode(b.toByteArray(), PairingHandler.FIELDS) }, b) }
    }

    @Test
    fun `reponse - en-tetes de securite et pas de CORS`() {
        val out = ByteArrayOutputStream()
        HttpResponse.html(200, "<p>é</p>").writeTo(out)
        val text = out.toString("UTF-8")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        listOf(
            "Cache-Control: no-store", "X-Content-Type-Options: nosniff", "Referrer-Policy: same-origin",
            "Connection: close", "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'",
            "Content-Length: 9", "Content-Type: text/html; charset=utf-8",
        ).forEach { assertTrue(text.contains(it), it) }
        assertFalse(text.contains("Access-Control", ignoreCase = true))
        assertFalse(text.contains("Server:", ignoreCase = true))
    }

    @Test
    fun `echappement HTML`() {
        assertEquals("&lt;script&gt;alert(&quot;x&quot;&amp;&#39;y&#39;)&lt;/script&gt;", escapeHtml("<script>alert(\"x\"&'y')</script>"))
    }
}
