package fr.footballrama.core

import fr.footballrama.core.fixtures.FixturesClient
import fr.footballrama.core.fixtures.FixturesDownloadException
import fr.footballrama.core.xtream.XtreamAccount
import fr.footballrama.core.xtream.XtreamClient
import fr.footballrama.core.xtream.XtreamException
import fr.footballrama.core.xtream.XtreamException.Kind
import fr.footballrama.core.xtream.XtreamLimits
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

class XtreamClientTest {

    private lateinit var server: MockWebServer
    private val client = XtreamClient(limits = XtreamLimits(maxStreamsBytes = 10_000, maxEpgBytes = 50_000))
    private lateinit var account: XtreamAccount

    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        account = XtreamAccount.create(server.url("/").toString(), USER, PASS)!!
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun gzip(text: String): Buffer {
        val out = Buffer()
        GzipSink(out).buffer().use { it.writeUtf8(text) }
        return out
    }

    @Test
    fun `validation de l URL serveur`() {
        assertNotNull(XtreamAccount.create("http://exemple.tv:8080", "u", "p"))
        assertNotNull(XtreamAccount.create("exemple.tv:8080/", "u", "p"))
        assertTrue(XtreamAccount.create("http://exemple.tv", "u", "p")!!.isCleartext)
        assertFalse(XtreamAccount.create("https://exemple.tv", "u", "p")!!.isCleartext)
        assertNull(XtreamAccount.create("ftp://exemple.tv", "u", "p"))
        assertNull(XtreamAccount.create("http://a:b@exemple.tv", "u", "p"))
        assertNull(XtreamAccount.create("http://exemple.tv/?username=x", "u", "p"))
        assertNull(XtreamAccount.create("http://exemple.tv", "", "p"))
        assertEquals("XtreamAccount(<redacted>)", XtreamAccount.create("http://exemple.tv", USER, PASS).toString())
    }

    @Test
    fun `streams parses defensivement`() {
        server.enqueue(
            MockResponse.Builder().body(
                """[{"num":1,"name":"|FR| TF1 FHD","stream_id":12864,"epg_channel_id":"TF1.fr","category_id":"1000"},
                   {"name":"id texte","stream_id":"42","epg_channel_id":null,"category_id":1000},
                   {"name":"sans id"},
                   "pas un objet", 12, null,
                   {"name":{"x":1},"stream_id":7,"epg_channel_id":["bizarre"],"category_id":true}]""",
            ).build(),
        )
        val streams = client.getLiveStreams(account)
        assertEquals(listOf(12864L, 42L, 7L), streams.map { it.streamId })
        assertEquals("tf1.fr", streams[0].epgChannelId!!.lowercase())
        assertNull(streams[1].epgChannelId)
        assertEquals("1000", streams[1].categoryId)
        assertEquals("", streams[2].name)
        assertNull(streams[2].epgChannelId)
        val req = server.takeRequest()
        assertEquals("/player_api.php", req.url.encodedPath)
        assertEquals("get_live_streams", req.url.queryParameter("action"))
        assertEquals(USER, req.url.queryParameter("username"))
    }

    @Test
    fun `categories gzip sans Content-Encoding`() {
        server.enqueue(MockResponse.Builder().body(gzip("""[{"category_id":"1000","category_name":"FRANCE FHD | TV"}]""")).build())
        assertEquals("FRANCE FHD | TV", client.getLiveCategories(account).single().name)
    }

    @Test
    fun `identifiants refuses`() {
        server.enqueue(MockResponse.Builder().body("""{"user_info":{"auth":0}}""").build())
        assertEquals(Kind.AUTH, assertThrows<XtreamException> { client.getLiveStreams(account) }.kind)
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(Kind.AUTH, assertThrows<XtreamException> { client.getLiveStreams(account) }.kind)
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(Kind.SERVER, assertThrows<XtreamException> { client.getLiveStreams(account) }.kind)
    }

    @Test
    fun `authenticate - seul user_info auth 1 et statut actif sont acceptes`() {
        server.enqueue(MockResponse.Builder().body("""{"user_info":{"auth":1,"status":"Active"},"server_info":{}}""").build())
        client.authenticate(account)
        val req = server.takeRequest()
        assertEquals("/player_api.php", req.url.encodedPath)
        assertNull(req.url.queryParameter("action"))
        // Mauvais mot de passe : panels qui repondent [] (200), {} , auth 0, corps vide, compte expire, HTML
        for (body in listOf("[]", "{}", """{"user_info":{"auth":0}}""", "", """{"user_info":{"auth":"1","status":"Expired"}}""", "<html>x</html>")) {
            server.enqueue(MockResponse.Builder().body(body).build())
            assertEquals(Kind.AUTH, assertThrows<XtreamException> { client.authenticate(account) }.kind, body)
        }
        server.enqueue(MockResponse.Builder().body("""{"user_info":{"auth":"1"}}""").build())
        client.authenticate(account) // statut absent : accepte
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(Kind.SERVER, assertThrows<XtreamException> { client.authenticate(account) }.kind)
    }

    @Test
    fun `reponses invalides et trop grosses`() {
        server.enqueue(MockResponse.Builder().body("<html>erreur</html>").build())
        assertEquals(Kind.INVALID_RESPONSE, assertThrows<XtreamException> { client.getLiveStreams(account) }.kind)
        server.enqueue(MockResponse.Builder().body("[{\"stream_id\":1,").build())
        assertEquals(Kind.INVALID_RESPONSE, assertThrows<XtreamException> { client.getLiveStreams(account) }.kind)
        server.enqueue(MockResponse.Builder().body("[" + "{\"stream_id\":1},".repeat(2000) + "{}]").build())
        assertEquals(Kind.TOO_LARGE, assertThrows<XtreamException> { client.getLiveStreams(account) }.kind)
        server.enqueue(MockResponse.Builder().body("").build())
        assertTrue(client.getLiveStreams(account).isEmpty())
    }

    @Test
    fun `EPG telecharge en flux, gzip et bombe gzip`() {
        val xml = "<tv>" + "<channel id=\"a\"><display-name>A</display-name></channel>".repeat(100) + "</tv>"
        server.enqueue(MockResponse.Builder().body(gzip(xml)).build())
        val dest = File(tmp, "epg.xml")
        var progress = 0L
        val n = client.downloadEpg(account, dest) { read, _ -> progress = read }
        assertEquals(xml.length.toLong(), n)
        assertEquals(xml, dest.readText())
        assertTrue(progress > 0)
        assertEquals("/xmltv.php", server.takeRequest().url.encodedPath)

        // 10 Mo de zeros compresses en quelques Ko : refuse a 50 000 octets decompresses.
        server.enqueue(MockResponse.Builder().body(gzip("0".repeat(10_000_000))).build())
        assertEquals(Kind.TOO_LARGE, assertThrows<XtreamException> { client.downloadEpg(account, File(tmp, "bomb.xml")) }.kind)
    }

    @Test
    fun `timeout et serveur injoignable`() {
        val shortClient = XtreamClient(readTimeoutSeconds = 1)
        server.enqueue(MockResponse.Builder().headersDelay(3, TimeUnit.SECONDS).body("[]").build())
        assertEquals(Kind.TIMEOUT, assertThrows<XtreamException> { shortClient.getLiveStreams(account) }.kind)
        val dead = XtreamAccount.create("http://127.0.0.1:1", USER, PASS)!!
        assertEquals(Kind.NETWORK, assertThrows<XtreamException> { client.getLiveStreams(dead) }.kind)
    }

    @Test
    fun `aucune fuite d identifiants dans les erreurs`() {
        val errors = mutableListOf<Throwable>()
        server.enqueue(MockResponse.Builder().code(404).body("not found $USER").build())
        errors += assertThrows<XtreamException> { client.getLiveStreams(account) }
        server.enqueue(MockResponse.Builder().body("{\"user_info\":{\"auth\":0}}").build())
        errors += assertThrows<XtreamException> { client.getLiveCategories(account) }
        errors += assertThrows<XtreamException> { client.getLiveStreams(XtreamAccount.create("http://127.0.0.1:1", USER, PASS)!!) }
        server.enqueue(MockResponse.Builder().body("<<<").build())
        errors += assertThrows<XtreamException> { client.getLiveStreams(account) }
        for (e in errors) {
            val dump = e.stackTraceToString() + e.toString()
            assertFalse(USER in dump, "user dans: $dump")
            assertFalse(PASS in dump, "password dans: $dump")
            assertFalse("player_api" in dump)
            assertNull(e.cause)
        }
    }

    @Test
    fun `fixtures - HTTPS impose et taille bornee`() {
        val c = FixturesClient("http://example.org/fixtures/")
        assertFalse(c.isConfigured)
        assertEquals(FixturesDownloadException.Kind.NOT_HTTPS, assertThrows<FixturesDownloadException> { c.download() }.kind)

        val local = FixturesClient(server.url("/pub").toString(), httpCacheDir = File(tmp, "http"), allowCleartextForTests = true)
        server.enqueue(MockResponse.Builder().body("{}").addHeader("ETag", "\"v1\"").build())
        server.enqueue(MockResponse.Builder().body("c2ln").addHeader("ETag", "\"s1\"").build())
        val d = local.download()
        assertEquals("{}", String(d.json))
        assertEquals("/pub/fixtures.json", server.takeRequest().url.encodedPath)
        assertEquals("/pub/fixtures.json.sig", server.takeRequest().url.encodedPath)
        // Requete conditionnelle : If-None-Match envoye, 304 -> corps servi depuis le cache.
        server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "\"v1\"").build())
        server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "\"s1\"").build())
        val again = local.download()
        assertEquals("{}", String(again.json))
        assertEquals("c2ln", String(again.signature))
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])

        server.enqueue(MockResponse.Builder().body("x".repeat(3 * 1024 * 1024)).build())
        assertEquals(FixturesDownloadException.Kind.TOO_LARGE, assertThrows<FixturesDownloadException> { local.download() }.kind)
        local.close()
    }

    private companion object {
        const val USER = "utilisateurTest"
        const val PASS = "motDePasseTest"
    }
}
