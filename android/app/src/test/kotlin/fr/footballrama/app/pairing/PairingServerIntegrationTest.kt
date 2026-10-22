package fr.footballrama.app.pairing

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Le vrai serveur, sur 127.0.0.1, avec de vraies connexions TCP. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PairingServerIntegrationTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val events: MutableList<PairingEvent> = Collections.synchronizedList(ArrayList())
    private var server: PairingServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop()
    }

    private fun start(
        config: PairingServer.Config = PairingServer.Config(readTimeoutMs = 1_000, stopGraceMs = 500),
        submitter: CredentialSubmitter = CredentialSubmitter { _, _, _ -> SubmitResult.Success },
    ): PairingServer {
        val s = PairingServer(loopback, PairingSession(SecureRandom()), submitter, events::add, config)
        s.start()
        server = s
        return s
    }

    private fun connect(s: PairingServer) = Socket().apply {
        connect(InetSocketAddress(loopback, s.port), 2_000)
        soTimeout = 5_000
    }

    /** Envoie [raw] et lit toute la reponse (le serveur ferme la connexion). */
    private fun exchange(s: PairingServer, raw: ByteArray): String = connect(s).use { sock ->
        sock.getOutputStream().write(raw)
        sock.getOutputStream().flush()
        readAll(sock)
    }

    private fun readAll(sock: Socket): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (true) {
                val n = sock.getInputStream().read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (_: SocketException) { // RST apres fermeture : la reponse a deja ete lue
        }
        return out.toString("UTF-8")
    }

    private fun get(s: PairingServer, path: String, host: String = s.hostHeader) =
        exchange(s, "GET $path HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())

    private fun post(s: PairingServer, path: String, body: String, host: String = s.hostHeader) = exchange(
        s,
        ("POST $path HTTP/1.1\r\nHost: $host\r\nOrigin: http://${s.hostHeader}\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray(),
    )

    private fun statusOf(response: String) = response.substringBefore("\r\n")
    private fun path(s: PairingServer) = "/c/${s.session.token}"
    private fun body(code: String) = "code=$code&server=" + URLEncoder.encode("http://iptv.example:8080", "UTF-8") + "&username=alice&password=pw"

    @Test
    fun `ecoute sur l'adresse demandee, port de la plage, URL du QR code`() {
        val s = start()
        assertTrue(s.port in 8090..8099 || s.port > 1024)
        assertEquals("http://127.0.0.1:${s.port}/c/${s.session.token}", s.url)
        assertTrue(s.isRunning)
    }

    @Test
    fun `sans jeton 404, mauvais Host 400, page 200 avec en-tetes de securite`() {
        val s = start()
        val notFound = get(s, "/")
        assertEquals("HTTP/1.1 404 Not Found", statusOf(notFound))
        assertTrue(notFound.endsWith("\r\n\r\n")) // aucun corps
        assertEquals("HTTP/1.1 400 Bad Request", statusOf(get(s, path(s), host = "attacker.example:${s.port}")))
        val ok = get(s, path(s))
        assertEquals("HTTP/1.1 200 OK", statusOf(ok))
        assertTrue(ok.contains("Content-Security-Policy: default-src 'none'"))
        assertTrue(ok.contains("Cache-Control: no-store"))
        assertTrue(ok.contains("Connection: close"))
        assertFalse(ok.contains("Access-Control-Allow-Origin"))
    }

    @Test
    fun `corps et en-tetes trop gros refuses`() {
        val s = start()
        val big = post(s, path(s), "code=" + "1".repeat(9000))
        assertEquals("HTTP/1.1 413 Content Too Large", statusOf(big))
        val longHeaders = exchange(
            s,
            "GET ${path(s)} HTTP/1.1\r\nHost: ${s.hostHeader}\r\nX-Pad: ${"a".repeat(9000)}\r\n\r\n".toByteArray(),
        )
        assertEquals("HTTP/1.1 431 Request Header Fields Too Large", statusOf(longHeaders))
    }

    @Test
    fun `client lent (slowloris) coupe apres le delai de lecture`() {
        val s = start()
        connect(s).use { sock ->
            sock.getOutputStream().write("GET ${path(s)} HTTP/1.1\r\nHost: ".toByteArray())
            sock.getOutputStream().flush()
            val t0 = System.nanoTime()
            val response = readAll(sock) // le serveur repond 408 puis ferme
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            assertTrue(response.startsWith("HTTP/1.1 408") || response.isEmpty(), response)
            assertTrue(elapsedMs in 500..4_000, "elapsed=$elapsedMs")
        }
    }

    @Test
    fun `connexions simultanees limitees a 4`() {
        val s = start(PairingServer.Config(readTimeoutMs = 2_000, stopGraceMs = 500))
        val idle = (1..4).map { connect(s) }
        Thread.sleep(200)
        val fifth = connect(s)
        // La 5e connexion est fermee immediatement sans reponse.
        assertEquals("", readAll(fifth))
        fifth.close()
        idle.forEach { it.close() }
        Thread.sleep(300)
        assertEquals("HTTP/1.1 200 OK", statusOf(get(s, path(s))))
    }

    @Test
    fun `succes - reponse au telephone puis arret immediat du serveur`() {
        val s = start()
        val r = post(s, path(s), body(s.session.code))
        assertEquals("HTTP/1.1 200 OK", statusOf(r))
        assertTrue(r.contains("Connexion réussie"))
        Thread.sleep(300)
        assertFalse(s.isRunning)
        assertConnectionRefused(s)
        assertTrue(events.contains(PairingEvent.Succeeded))
    }

    @Test
    fun `5 mauvais codes - session invalidee et serveur arrete`() {
        val s = start()
        val wrong = if (s.session.code == "0000") "1111" else "0000"
        repeat(4) { assertEquals("HTTP/1.1 403 Forbidden", statusOf(post(s, path(s), body(wrong)))) }
        val fifth = post(s, path(s), body(wrong))
        assertTrue(fifth.contains("Trop d’essais"))
        Thread.sleep(300)
        assertFalse(s.isRunning)
        assertConnectionRefused(s)
        assertTrue(events.contains(PairingEvent.Locked))
    }

    @Test
    fun `arret automatique a expiration`() {
        val s = start(PairingServer.Config(lifetimeMs = 400, readTimeoutMs = 1_000, stopGraceMs = 200))
        Thread.sleep(1_500)
        assertFalse(s.isRunning)
        assertConnectionRefused(s)
        assertTrue(events.contains(PairingEvent.Expired))
    }

    @Test
    fun `stop explicite (sortie d'ecran) - plus aucune connexion`() {
        val s = start()
        assertEquals("HTTP/1.1 200 OK", statusOf(get(s, path(s))))
        s.stop()
        s.stop() // idempotent
        assertConnectionRefused(s)
        assertEquals(PairingSession.State.CLOSED, s.session.state)
    }

    @Test
    fun `pairs hors reseau prive refuses`() {
        assertTrue(PairingServer.isAllowedPeer(InetAddress.getByName("192.168.1.20")))
        assertTrue(PairingServer.isAllowedPeer(InetAddress.getByName("10.1.2.3")))
        assertTrue(PairingServer.isAllowedPeer(InetAddress.getByName("172.20.0.5")))
        assertTrue(PairingServer.isAllowedPeer(InetAddress.getByName("127.0.0.1")))
        assertFalse(PairingServer.isAllowedPeer(InetAddress.getByName("8.8.8.8")))
        assertFalse(PairingServer.isAllowedPeer(InetAddress.getByName("172.32.0.1")))
        assertFalse(PairingServer.isAllowedPeer(null))
    }

    private fun assertConnectionRefused(s: PairingServer) {
        val refused = try {
            Socket().use { it.connect(InetSocketAddress(loopback, s.port), 1_000) }
            false
        } catch (_: ConnectException) {
            true
        } catch (_: IOException) {
            true
        }
        assertTrue(refused, "le port ${s.port} accepte encore des connexions")
    }
}
