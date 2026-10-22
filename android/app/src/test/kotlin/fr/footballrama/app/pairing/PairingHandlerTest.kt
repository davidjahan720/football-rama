package fr.footballrama.app.pairing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PairingHandlerTest {

    private val host = "192.168.1.11:8095"
    private val events: MutableList<PairingEvent> = Collections.synchronizedList(ArrayList())
    private val submitted = Collections.synchronizedList(ArrayList<Triple<String, String, String>>())

    private fun setup(submitter: CredentialSubmitter = CredentialSubmitter { s, u, p -> submitted.add(Triple(s, u, p)); SubmitResult.Success }): Pair<PairingSession, PairingHandler> {
        val session = PairingSession(SecureRandom())
        return session to PairingHandler(session, host, submitter, events::add)
    }

    private fun get(path: String, h: String = host, extra: List<Pair<String, String>> = emptyList()) =
        HttpRequest("GET", path, listOf("host" to h) + extra, ByteArray(0))

    private fun form(vararg fields: Pair<String, String>) =
        fields.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }.toByteArray()

    private fun post(
        path: String,
        body: ByteArray,
        h: String = host,
        origin: String? = "http://$host",
        contentType: String = "application/x-www-form-urlencoded",
        extra: List<Pair<String, String>> = emptyList(),
    ) = HttpRequest(
        "POST", path,
        listOfNotNull("host" to h, origin?.let { "origin" to it }, "content-type" to contentType) + extra,
        body,
    )

    private fun HttpResponse.text() = String(body, Charsets.UTF_8)

    private fun validBody(code: String) = form("code" to code, "server" to "http://iptv.example:8080", "username" to "alice", "password" to "s3cret!")

    // ------------------------------------------------------------------ session

    @Test
    fun `jeton 128 bits base64url et code a 4 chiffres`() {
        val tokens = (1..50).map { PairingSession(SecureRandom()) }
        tokens.forEach {
            assertEquals(22, it.token.length)
            assertTrue(it.token.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' })
            assertTrue(it.code.length == 4 && it.code.all(Char::isDigit))
        }
        assertEquals(50, tokens.map { it.token }.toSet().size)
    }

    // ------------------------------------------------------------------ routage

    @Test
    fun `requete sans jeton ou mauvais jeton - 404 sans detail`() {
        val (session, h) = setup()
        listOf("/", "/c/", "/c", "/favicon.ico", "/c/${session.token}x", "/c/${session.token.dropLast(1)}A",
            "/c/${session.token}/", "/c/${session.token}?a=1", "/C/${session.token}").forEach { p ->
            val r = h.handle(get(p))
            if (p == "/c/${session.token.dropLast(1)}A" && session.token.last() == 'A') return@forEach
            assertEquals(404, r.status, p)
            assertEquals(0, r.body.size, p)
        }
        assertEquals(200, h.handle(get("/c/${session.token}")).status)
    }

    @Test
    fun `Host falsifie (DNS rebinding) - 400`() {
        val (session, h) = setup()
        val path = "/c/${session.token}"
        listOf("evil.example", "evil.example:8095", "192.168.1.11", "192.168.1.11:8096", "localhost:8095", "").forEach {
            assertEquals(400, h.handle(get(path, it)).status, it)
        }
        val noHost = HttpRequest("GET", path, emptyList(), ByteArray(0))
        assertEquals(400, h.handle(noHost).status)
        val twoHosts = HttpRequest("GET", path, listOf("host" to host, "host" to host), ByteArray(0))
        assertEquals(400, h.handle(twoHosts).status)
    }

    @Test
    fun `methodes limitees`() {
        val (session, h) = setup()
        val path = "/c/${session.token}"
        listOf("PUT", "DELETE", "OPTIONS", "HEAD", "TRACE").forEach {
            val r = h.handle(HttpRequest(it, path, listOf("host" to host), ByteArray(0)))
            assertEquals(405, r.status, it)
        }
    }

    @Test
    fun `page du formulaire - sans script, francais, avertissement reseau domestique`() {
        val (session, h) = setup()
        val r = h.handle(get("/c/${session.token}"))
        val html = r.text()
        assertEquals(200, r.status)
        assertFalse(html.contains("<script", ignoreCase = true))
        assertTrue(html.contains("lang=\"fr\""))
        assertTrue(html.contains("réseau domestique"))
        assertTrue(html.contains("name=\"code\""))
        assertFalse(html.contains(session.code)) // le code n'est JAMAIS sur la page du telephone
        assertTrue(r.headers().any { it.first == "Content-Security-Policy" && it.second.startsWith("default-src 'none'") })
    }

    // ------------------------------------------------------------------ POST

    @Test
    fun `Origin different, Sec-Fetch-Site cross-site, mauvais Content-Type - refuses`() {
        val (session, h) = setup()
        val path = "/c/${session.token}"
        val body = validBody(session.code)
        assertEquals(403, h.handle(post(path, body, origin = "http://evil.example")).status)
        assertEquals(403, h.handle(post(path, body, origin = "null")).status)
        assertEquals(403, h.handle(post(path, body, extra = listOf("sec-fetch-site" to "cross-site"))).status)
        assertEquals(415, h.handle(post(path, body, contentType = "text/plain")).status)
        assertEquals(415, h.handle(post(path, body, contentType = "multipart/form-data; boundary=x")).status)
        assertEquals(415, h.handle(post(path, body, contentType = "application/x-www-form-urlencoded; charset=latin1")).status)
        assertTrue(submitted.isEmpty())
        // Origin absent (ancien navigateur, curl) : accepte, le jeton et le code restent exiges.
        assertEquals(200, h.handle(post(path, body, origin = null)).status)
    }

    @Test
    fun `6e tentative de code bloquee meme avec le bon code`() {
        val (session, h) = setup()
        val path = "/c/${session.token}"
        val wrong = if (session.code == "0000") "1111" else "0000"
        for (i in 1..4) {
            val r = h.handle(post(path, validBody(wrong)))
            assertEquals(403, r.status)
            assertTrue(r.text().contains("Il vous reste ${5 - i}"), r.text())
        }
        val fifth = h.handle(post(path, validBody("abcd")))
        assertEquals(403, fifth.status)
        assertTrue(fifth.text().contains("Trop d’essais"))
        assertEquals(PairingSession.State.LOCKED, session.state)
        assertTrue(session.isFinished)
        // 6e tentative, bon code : toujours bloquee, aucun identifiant transmis.
        assertEquals(403, h.handle(post(path, validBody(session.code))).status)
        assertTrue(submitted.isEmpty())
        assertEquals(1, events.count { it == PairingEvent.Locked })
        assertEquals(410, h.handle(get(path)).status)
    }

    @Test
    fun `succes unique - double soumission refusee`() {
        val (session, h) = setup()
        val path = "/c/${session.token}"
        val r = h.handle(post(path, validBody(session.code)))
        assertEquals(200, r.status)
        assertTrue(r.text().contains("Connexion réussie"))
        assertEquals(listOf(Triple("http://iptv.example:8080", "alice", "s3cret!")), submitted)
        assertEquals(PairingSession.State.SUCCEEDED, session.state)
        assertEquals(410, h.handle(post(path, validBody(session.code))).status)
        assertEquals(410, h.handle(get(path)).status)
        assertEquals(1, submitted.size)
        assertEquals(listOf(PairingEvent.Validating, PairingEvent.Succeeded), events)
    }

    @Test
    fun `verrou anti-concurrence - 409 pendant une verification`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (session, h) = setup { _, _, _ ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            SubmitResult.Success
        }
        val path = "/c/${session.token}"
        var first: HttpResponse? = null
        val t = thread { first = h.handle(post(path, validBody(session.code))) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val concurrent = h.handle(post(path, validBody(session.code)))
        assertEquals(409, concurrent.status)
        release.countDown()
        t.join(5_000)
        assertEquals(200, first!!.status)
        assertEquals(410, h.handle(post(path, validBody(session.code))).status)
    }

    @Test
    fun `echec de verification - message lisible, mot de passe jamais renvoye, XSS echappe`() {
        val (session, h) = setup { _, _, _ -> SubmitResult.Failure("Identifiants refusés <b>par</b> le serveur.") }
        val path = "/c/${session.token}"
        val xss = "<script>alert(1)</script>"
        val body = form(
            "code" to session.code,
            "server" to "http://iptv.example:8080/get.php?username=u&password=TopSecretM3U&type=m3u",
            "username" to xss,
            "password" to "MyPassw0rd\"><script>",
        )
        val r = h.handle(post(path, body))
        val html = r.text()
        assertEquals(200, r.status)
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("Identifiants refusés &lt;b&gt;par&lt;/b&gt; le serveur."))
        assertFalse(html.contains("MyPassw0rd"))
        assertFalse(html.contains("TopSecretM3U")) // l'adresse reaffichee est la base, sans identifiants
        assertTrue(html.contains("value=\"http://iptv.example:8080\""))
        assertEquals(PairingSession.State.ACTIVE, session.state) // nouvel essai possible
        assertTrue(events.last() is PairingEvent.Failed)
        assertFalse(events.toString().contains("MyPassw0rd"))
    }

    @Test
    fun `nombre de verifications borne par session`() {
        val (session, h) = setup { _, _, _ -> SubmitResult.Failure("non") }
        val path = "/c/${session.token}"
        repeat(PairingSession.MAX_SUBMISSIONS - 1) { assertEquals(200, h.handle(post(path, validBody(session.code))).status) }
        assertEquals(403, h.handle(post(path, validBody(session.code))).status)
        assertEquals(PairingSession.State.LOCKED, session.state)
    }

    @Test
    fun `exception du verificateur - message generique`() {
        val (session, h) = setup { _, _, _ -> throw IllegalStateException("detail interne secret") }
        val r = h.handle(post("/c/${session.token}", validBody(session.code)))
        assertFalse(r.text().contains("secret"))
        assertTrue(r.text().contains("erreur inattendue"))
    }

    @Test
    fun `champs trop longs et formulaire invalide`() {
        val (session, h) = setup()
        val path = "/c/${session.token}"
        assertEquals(400, h.handle(post(path, form("code" to session.code, "server" to "a".repeat(2049)))).status)
        assertEquals(400, h.handle(post(path, "code=1&evil=2".toByteArray())).status)
        assertTrue(submitted.isEmpty())
        assertEquals(PairingSession.State.ACTIVE, session.state)
    }

    @Test
    fun `session fermee - 410`() {
        val (session, h) = setup()
        session.close()
        assertEquals(410, h.handle(get("/c/${session.token}")).status)
        assertEquals(410, h.handle(post("/c/${session.token}", validBody(session.code))).status)
        assertNotEquals(PairingSession.State.ACTIVE, session.state)
    }
}
