package fr.footballrama.core

import fr.footballrama.core.xtream.ShortEpgParser
import fr.footballrama.core.xtream.XtreamAccount
import fr.footballrama.core.xtream.XtreamClient
import fr.footballrama.core.xtream.XtreamException
import fr.footballrama.core.xtream.XtreamException.Kind
import fr.footballrama.core.xtream.XtreamLimits
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Base64

class ShortEpgTest {

    private fun b64(s: String, charset: java.nio.charset.Charset = Charsets.UTF_8) =
        Base64.getEncoder().encodeToString(s.toByteArray(charset))

    private fun sample(): String =
        javaClass.getResourceAsStream("/short_epg_sample.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    // ------------------------------------------------------------------ parseur

    @Test
    fun `echantillon reel du fournisseur`() {
        val listings = ShortEpgParser.parse(sample())
        assertEquals(4, listings.size)
        val first = listings[0]
        assertEquals("Bayern Munich / Arsenal", first.title)
        assertEquals("CanalPlusFoot.fr", first.channelId)
        // start_timestamp (UTC) utilise, pas "start" (heure locale du serveur, ici UTC+2).
        assertEquals(Instant.parse("2026-10-22T11:15:00Z"), first.start)
        assertEquals(Instant.parse("2026-10-22T13:00:00Z"), first.stop)
        assertTrue(first.description.contains("compétition"), first.description)
        assertEquals("Détective Mathoux", listings[2].title)
        // L'echantillon couvre bien l'apres-match (programmes futurs).
        assertTrue(listings.last().start.isAfter(Instant.parse("2026-10-22T13:00:00Z")))
    }

    @Test
    fun `base64 UTF-8 strict puis repli Windows-1252`() {
        assertEquals("Détective — Mathoux", ShortEpgParser.decodeText(b64("Détective — Mathoux")))
        assertEquals("Équipe de France : Ligue des champions", ShortEpgParser.decodeText(b64("Équipe de France : Ligue des champions", charset("windows-1252"))))
        assertEquals("Köln – München", ShortEpgParser.decodeText(b64("Köln – München", charset("windows-1252"))))
        assertEquals("", ShortEpgParser.decodeText(null))
        assertEquals("", ShortEpgParser.decodeText("   "))
        // Pas du base64 : texte garde tel quel ; caracteres de controle neutralises.
        assertEquals("Match en direct !", ShortEpgParser.decodeText("Match en direct !"))
        assertEquals("a b", ShortEpgParser.decodeText(b64("a\u0000b")))
    }

    @Test
    fun `timestamps texte, nombre, millisecondes ou invalides`() {
        val json = """{"epg_listings":[
            {"title":"${b64("texte")}","start_timestamp":"1792667700","stop_timestamp":"1792674000"},
            {"title":"${b64("nombre")}","start_timestamp":1792667700,"stop_timestamp":1792674000.0},
            {"title":"${b64("ms")}","start_timestamp":1792667700000},
            {"title":"${b64("sans debut")}","start":"2026-10-22 13:15:00"},
            {"title":"${b64("absurde")}","start_timestamp":"12"},
            {"title":"${b64("fin avant debut")}","start_timestamp":"1792667700","stop_timestamp":"1792667000"}
        ]}"""
        val l = ShortEpgParser.parse(json)
        assertEquals(listOf("texte", "nombre", "ms", "fin avant debut"), l.map { it.title })
        l.take(3).forEach { assertEquals(Instant.ofEpochSecond(1792667700), it.start) }
        assertEquals(Instant.ofEpochSecond(1792674000), l[1].stop)
        assertNull(l[2].stop)
        assertNull(l[3].stop)
    }

    @Test
    fun `champs manquants et types inattendus`() {
        val json = """{"epg_listings":[
            {"start_timestamp":"1792667700"},
            {"title":null,"description":12,"channel_id":["x"],"start_timestamp":"1792667700"},
            "pas un objet", 3, null
        ]}"""
        val l = ShortEpgParser.parse(json)
        assertEquals(2, l.size)
        l.forEach { assertEquals("", it.title); assertEquals("", it.channelId) }
    }

    @Test
    fun `reponses vides acceptees`() {
        listOf("", "\n", "[]", "{}", """{"epg_listings":[]}""", """{"epg_listings":{}}""", """{"epg_listings":null}""", "﻿[]")
            .forEach { assertEquals(emptyList<Any>(), ShortEpgParser.parse(it), it) }
        // Variante objet indexe.
        val obj = """{"epg_listings":{"0":{"title":"${b64("A")}","start_timestamp":"1792667700"}}}"""
        assertEquals("A", ShortEpgParser.parse(obj).single().title)
    }

    @Test
    fun `identifiants refuses et reponse non JSON`() {
        assertEquals(Kind.AUTH, assertThrows<XtreamException> { ShortEpgParser.parse("""{"user_info":{"auth":0}}""") }.kind)
        assertEquals(Kind.INVALID_RESPONSE, assertThrows<XtreamException> { ShortEpgParser.parse("<html>erreur</html>") }.kind)
    }

    @Test
    fun `titres et descriptions bornes`() {
        val json = """{"epg_listings":[{"title":"${b64("T".repeat(5000))}","description":"${b64("D".repeat(5000))}","start_timestamp":"1792667700"}]}"""
        val l = ShortEpgParser.parse(json).single()
        assertEquals(ShortEpgParser.MAX_TITLE, l.title.length)
        assertEquals(ShortEpgParser.MAX_DESCRIPTION, l.description.length)
    }

    // ------------------------------------------------------------------ client HTTP

    private lateinit var server: MockWebServer
    private lateinit var account: XtreamAccount

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        account = XtreamAccount.create(server.url("/").toString(), USER, PASS)!!
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `get_short_epg - requete et parsing`() {
        server.enqueue(MockResponse.Builder().body(sample()).build())
        val l = XtreamClient().getShortEpg(account, 12864, 40)
        assertEquals(4, l.size)
        val url = server.takeRequest().url
        assertEquals("/player_api.php", url.encodedPath)
        assertEquals("get_short_epg", url.queryParameter("action"))
        assertEquals("12864", url.queryParameter("stream_id"))
        assertEquals("40", url.queryParameter("limit"))
    }

    @Test
    fun `get_simple_data_table - meme structure`() {
        server.enqueue(MockResponse.Builder().body(sample()).build())
        assertEquals(4, XtreamClient().getSimpleDataTable(account, 7).size)
        assertEquals("get_simple_data_table", server.takeRequest().url.queryParameter("action"))
    }

    @Test
    fun `get_short_epg - erreurs sans identifiants`() {
        val client = XtreamClient(limits = XtreamLimits(maxShortEpgBytes = 1_000))
        server.enqueue(MockResponse.Builder().body("[" + "{\"a\":1},".repeat(500) + "{}]").build())
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(403).build())
        val errors = listOf(
            assertThrows<XtreamException> { client.getShortEpg(account, 1, 40) },
            assertThrows<XtreamException> { client.getShortEpg(account, 1, 40) },
            assertThrows<XtreamException> { client.getShortEpg(account, 1, 40) },
            assertThrows<XtreamException> { client.getShortEpg(account, 1, 40) },
        )
        assertEquals(listOf(Kind.TOO_LARGE, Kind.HTTP, Kind.SERVER, Kind.AUTH), errors.map { it.kind })
        assertEquals(429, errors[1].httpCode)
        errors.forEach { e ->
            assertFalse(e.message!!.contains(USER) || e.message!!.contains(PASS))
            assertNull(e.cause)
        }
    }

    private companion object {
        const val USER = "utilisateur-secret"
        const val PASS = "motdepasse-secret"
    }
}
