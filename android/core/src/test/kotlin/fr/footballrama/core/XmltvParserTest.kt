package fr.footballrama.core

import fr.footballrama.core.epg.EpgParseException
import fr.footballrama.core.epg.ProgrammeFilter
import fr.footballrama.core.epg.XmltvParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.Charset
import java.time.Instant

class XmltvParserTest {

    private val parser = XmltvParser(kxmlFactory)
    private val filter = ProgrammeFilter(
        Instant.parse("2026-10-20T00:00:00Z"), Instant.parse("2026-10-22T03:00:00Z"),
        listOf("psg", "marseille", "paris saint germain", "barcelona"),
    )

    private fun parse(xml: String) = parser.parse(xml.byteInputStream(), filter)

    @Test
    fun `parse en flux, fenetre et prefiltre`() {
        val xml = """<?xml version="1.0" encoding="utf-8" ?><!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv generator-info-name="x">
              <channel id="CanalPlus.fr"><display-name>|FR| CANAL+ FHD</display-name><icon src="http://x/y.png"/></channel>
              <programme start="20261020203000 +0200" stop="20261020230000 +0200" channel="CanalPlus.fr">
                <title lang="fr">Ligue 1 : PSG - Marseille &amp; co</title><desc lang="fr">Choc</desc><category>Sport</category>
              </programme>
              <programme start="20261020203000 +0200" stop="20261020230000 +0200" channel="CanalPlus.fr">
                <title>Le journal</title><desc>Rien a voir</desc>
              </programme>
              <programme start="20261101203000 +0000" channel="CanalPlus.fr"><title>PSG - OM</title></programme>
              <programme start="bad" channel="CanalPlus.fr"><title>PSG - OM</title></programme>
              <programme start="20261020120000 +0000" channel="Foot.fr"><title><![CDATA[Football]]></title><desc>Paris Saint-Germain contre Barcelona</desc></programme>
            </tv>"""
        val data = parse(xml)
        assertEquals(5, data.stats.programmesTotal)
        assertEquals(3, data.stats.programmesInWindow)
        assertEquals(2, data.programmes.size)
        val p = data.programmes[0]
        assertEquals("canalplus.fr", p.channelId)
        assertEquals(Instant.parse("2026-10-20T18:30:00Z"), p.start)
        assertEquals("Ligue 1 : PSG - Marseille & co", p.title)
        assertEquals("ligue 1 psg marseille co choc", p.text)
        assertEquals("|FR| CANAL+ FHD", data.channels["canalplus.fr"])
        assertEquals("football paris saint germain contre barcelona", data.programmes[1].text)
    }

    @Test
    fun `XXE refuse (entite externe)`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///c:/windows/win.ini">]>
            <tv><programme start="20261020190000 +0000" channel="a"><title>PSG &xxe;</title></programme></tv>"""
        val e = assertThrows<EpgParseException> { parse(xml) }
        assertEquals(EpgParseException.Reason.UNSAFE, e.reason)
    }

    @Test
    fun `billion laughs refuse`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;">]>
            <tv><programme start="20261020190000 +0000" channel="a"><title>&lol2;</title></programme></tv>"""
        assertEquals(EpgParseException.Reason.UNSAFE, assertThrows<EpgParseException> { parse(xml) }.reason)
    }

    // --- Audit securite : contournement du filtre "<!ENTITY" par l'encodage -------------------------------
    // Le filtre travaille sur les octets ; le KXmlParser d'Android detecte UTF-16/32 et applique l'encodage declare.

    private val entityDoc = """<?xml version="1.0"?><!DOCTYPE tv [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;">]>
            <tv><programme start="20261020190000 +0000" channel="a"><title>PSG &lol2;</title></programme></tv>"""

    @Test
    fun `entite en UTF-16 (avec ou sans BOM) refusee avant le parseur`() {
        for (cs in listOf(Charsets.UTF_16, Charsets.UTF_16LE, Charsets.UTF_16BE, Charset.forName("UTF-32"))) {
            val e = assertThrows<EpgParseException>(cs.name()) { parser.parse(entityDoc.toByteArray(cs).inputStream(), filter) }
            assertEquals(EpgParseException.Reason.UNSAFE, e.reason, cs.name())
        }
    }

    @Test
    fun `encodage declare non compatible ASCII refuse`() {
        for (enc in listOf("UTF-16", "UTF-7", "IBM037", "x-IBM1047", "Shift_JIS", "UTF-32")) {
            val xml = """<?xml version="1.0" encoding="$enc"?><tv></tv>"""
            assertEquals(EpgParseException.Reason.UNSAFE, assertThrows<EpgParseException>(enc) { parse(xml) }.reason, enc)
        }
        // Lecture permissive comme Android : attribut mal forme mais encodage quand meme lu.
        assertEquals(EpgParseException.Reason.UNSAFE, assertThrows<EpgParseException> { parse("""<?xml version="1.0" encodingX='IBM037'?><tv></tv>""") }.reason)
    }

    @Test
    fun `encodages compatibles ASCII acceptes`() {
        for (enc in listOf("UTF-8", "utf-8", "ISO-8859-1", "ISO-8859-15", "windows-1252", "US-ASCII")) {
            val xml = """<?xml version="1.0" encoding="$enc"?><tv><programme start="20261020190000 +0000" channel="a"><title>PSG - OM</title></programme></tv>"""
            assertEquals(1, parse(xml).programmes.size, enc)
        }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            """<?xml version="1.0" encoding="UTF-8"?><tv></tv>""".toByteArray()
        assertEquals(0, parser.parse(bom.inputStream(), filter).programmes.size)
    }

    @Test
    fun `budget memoire des programmes retenus`() {
        // Chaque programme garde ~9 000 caracteres : au-dela de MAX_RETAINED_CHARS, refus propre (pas d'OOM).
        val desc = "psg " + "x".repeat(7_990)
        val n = (XmltvParser.MAX_RETAINED_CHARS / 8_000 + 10).toInt()
        val input = object : java.io.SequenceInputStream(
            java.util.Collections.enumeration(
                listOf("<tv>".byteInputStream()) +
                    List(n) { """<programme start="20261020190000 +0000" channel="a"><title>PSG</title><desc>$desc</desc></programme>""".byteInputStream() } +
                    listOf("</tv>".byteInputStream()),
            ),
        ) {}
        assertEquals(EpgParseException.Reason.TOO_LARGE, assertThrows<EpgParseException> { parser.parse(input, filter) }.reason)
    }

    @Test
    fun `entite non declaree refusee`() {
        val xml = """<tv><programme start="20261020190000 +0000" channel="a"><title>PSG &xxe;</title></programme></tv>"""
        assertEquals(EpgParseException.Reason.MALFORMED, assertThrows<EpgParseException> { parse(xml) }.reason)
    }

    @Test
    fun `texte demesure refuse`() {
        val big = "a".repeat(1_100_000)
        val xml = """<tv><programme start="20261020190000 +0000" channel="a"><title>PSG $big</title></programme></tv>"""
        assertEquals(EpgParseException.Reason.UNSAFE, assertThrows<EpgParseException> { parse(xml) }.reason)
    }

    @Test
    fun `XML malforme`() {
        assertEquals(EpgParseException.Reason.MALFORMED, assertThrows<EpgParseException> { parse("<tv><programme") }.reason)
        assertEquals(EpgParseException.Reason.MALFORMED, assertThrows<EpgParseException> { parse("{\"json\":1}") }.reason)
    }

    @Test
    fun `prefiltre par n-grammes`() {
        assertTrue(filter.matchesTerms("live paris saint germain fc"))
        assertTrue(filter.matchesTerms("psg"))
        assertFalse(filter.matchesTerms("paris saint"))
        assertFalse(filter.matchesTerms("psgx"))
        assertFalse(filter.matchesTerms(""))
    }
}
