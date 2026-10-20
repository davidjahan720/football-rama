package fr.footballrama.core

import fr.footballrama.core.TestAliases.fixture
import fr.footballrama.core.TestAliases.team
import fr.footballrama.core.epg.Programme
import fr.footballrama.core.matching.ChannelNames
import fr.footballrama.core.matching.Matcher
import fr.footballrama.core.matching.Score
import fr.footballrama.core.text.Normalizer
import fr.footballrama.core.xtream.LiveCategory
import fr.footballrama.core.xtream.LiveStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class MatcherTest {

    private val psg = team("Paris Saint-Germain FC", "PSG")
    private val om = team("Olympique de Marseille", "Marseille")
    private val barca = team("FC Barcelona", "Barça")
    private val porto = team("FC Porto", "Porto")
    private val psv = team("PSV", "PSV")
    private val como = team("Como 1907", "Como 1907")
    private val manUtd = team("Manchester United FC", "Man United")
    private val fener = team("Fenerbahçe SK", "Fenerbahçe")
    private val slavia = team("SK Slavia Praha", "Slavia Praha")

    private val psgOm = fixture(1, "FL1", "2026-10-20T19:00:00Z", psg, om)
    private val portoPsv = fixture(2, "CL", "2026-10-20T19:00:00Z", porto, psv)
    private val psgBarca = fixture(3, "CL", "2026-10-20T19:00:00Z", psg, barca)
    private val comoUtd = fixture(4, "CL", "2026-10-21T16:45:00Z", como, manUtd)
    private val fenerSlavia = fixture(5, "CL", "2026-10-20T16:45:00Z", fener, slavia)

    private fun prog(channel: String, start: String, title: String, desc: String = "", stop: String? = null) =
        Programme(channel, Instant.parse(start), stop?.let(Instant::parse), title, Normalizer.normalize("$title $desc"))

    private fun hits(fx: fr.footballrama.core.fixtures.Fixture, vararg p: Programme) =
        Matcher(TestAliases.fixtures(fx)).findHits(p.toList())[fx.id]!!

    @Test
    fun `alias du publisher`() {
        assertEquals(listOf("paris saint germain", "paris saint germain fc", "paris sg", "psg"), psg.aliases)
        assertTrue("como" in como.aliases)
        assertTrue("slavia praag" in slavia.aliases)
        assertTrue(psv.aliases.contains("psv"))
    }

    @Test
    fun `PSG - OM fort`() {
        val h = hits(psgOm, prog("canal", "2026-10-20T18:45:00Z", "Ligue 1 McDonald's : PSG - OM"))
        assertEquals(Score.FORT, h.single().score)
    }

    @Test
    fun `Real Betis x Porto rejete pour Porto - PSV`() {
        val h = hits(
            portoPsv,
            prog("dazn", "2026-10-20T19:00:00Z", "Real Betis x Porto", "UEFA Champions League"),
            prog("rmc", "2026-10-20T19:00:00Z", "LIVE: UCL: Porto v PSV"),
        )
        assertEquals(listOf("rmc"), h.map { it.channelEpgId })
        assertEquals(Score.FORT, h.single().score)
    }

    @Test
    fun `Youth League exclue`() {
        val h = hits(psgBarca, prog("c", "2026-10-20T19:00:00Z", "UEFA Youth League : PSG - Barcelone"))
        assertTrue(h.isEmpty())
    }

    @Test
    fun `Como 1907 trouve sous le nom Como`() {
        val h = hits(comoUtd, prog("c", "2026-10-21T16:45:00Z", "Como - Manchester United"))
        assertEquals(Score.FORT, h.single().score)
    }

    @Test
    fun `Slavia Praag (titre neerlandais)`() {
        val h = hits(fenerSlavia, prog("ziggo", "2026-10-20T16:40:00Z", "Fenerbahçe - Slavia Praag"))
        assertEquals(Score.FORT, h.single().score)
    }

    @Test
    fun `LIVE UEFA Champions League avec equipes en description - moyen`() {
        val h = hits(
            psgBarca,
            prog("bein", "2026-10-20T18:30:00Z", "LIVE: UEFA Champions League", "Paris Saint-Germain v Barcelona, 3e journee"),
        )
        assertEquals(Score.MOYEN, h.single().score)
    }

    @Test
    fun `une equipe + competition = moyen, une equipe seule = rien`() {
        assertEquals(Score.MOYEN, hits(psgBarca, prog("a", "2026-10-20T19:00:00Z", "Champions League", "Avec le PSG")).single().score)
        assertTrue(hits(psgBarca, prog("a", "2026-10-20T19:00:00Z", "Le PSG en direct")).isEmpty())
    }

    @Test
    fun `fenetre temporelle et programme couvrant le coup d envoi`() {
        val k = "2026-10-20T19:00:00Z"
        assertTrue(hits(psgBarca, prog("a", "2026-10-20T17:59:00Z", "PSG - Barcelona")).isEmpty()) // -61 min
        assertTrue(hits(psgBarca, prog("a", "2026-10-20T19:31:00Z", "PSG - Barcelona")).isEmpty()) // +31 min
        assertEquals(1, hits(psgBarca, prog("a", "2026-10-20T18:00:00Z", "PSG - Barcelona")).size)
        assertEquals(1, hits(psgBarca, prog("a", "2026-10-20T19:30:00Z", "PSG - Barcelona")).size)
        // Commence 2 h avant mais couvre le coup d'envoi.
        assertEquals(1, hits(psgBarca, prog("a", "2026-10-20T17:00:00Z", "Multiplex : PSG - Barcelona", stop = "2026-10-20T21:00:00Z")).size)
        assertTrue(hits(psgBarca, prog("a", "2026-10-20T17:00:00Z", "PSG - Barcelona", stop = k)).isEmpty())
    }

    @Test
    fun `regroupement FHD HD et tri fort avant moyen`() {
        val fixtures = TestAliases.fixtures(psgBarca)
        val m = Matcher(fixtures)
        val hits = m.findHits(
            listOf(
                prog("canalplus.fr", "2026-10-20T18:45:00Z", "Ligue des champions : PSG - Barcelone"),
                prog("bein.fr", "2026-10-20T18:30:00Z", "Champions League", "Paris Saint-Germain"),
            ),
        )
        val streams = listOf(
            LiveStream(1, "|FR| CANAL+ FHD", "CanalPlus.fr", "1000"),
            LiveStream(2, "|FR| CANAL+ HD", "canalplus.fr", "1010"),
            LiveStream(3, "|FR| CANAL+ HEVC", "canalplus.fr", "1010"),
            LiveStream(4, "BEIN SPORTS 1 HD", "bein.fr", "1012"),
            LiveStream(5, "Sans EPG", null, "1012"),
        )
        val cats = listOf(LiveCategory("1000", "FRANCE FHD | TV"), LiveCategory("1010", "FRANCE HD | TV"), LiveCategory("1012", "SPORTS HD | TV"))
        val r = m.buildResults(hits, streams, cats).single()
        assertEquals(2, r.channels.size)
        val canal = r.channels[0]
        assertEquals("|FR| CANAL+", canal.name)
        assertEquals("CANAL+", canal.displayName)
        assertEquals("FR", canal.country)
        assertEquals(Score.FORT, canal.score)
        assertEquals(listOf(1L, 2L, 3L), canal.variants.map { it.streamId })
        assertEquals("BEIN SPORTS 1", r.channels[1].name)
        assertEquals("SPORTS HD | TV", r.channels[1].country)
        assertEquals(Score.MOYEN, r.channels[1].score)
    }

    @Test
    fun `nom de chaine - qualite et prefixe pays`() {
        assertEquals("|FR| CANAL+ LIVE 1", ChannelNames.group("|FR| CANAL+ LIVE 1 FHD"))
        assertEquals("|UK| SKY SPORTS MAIN EVENT", ChannelNames.group("|UK| SKY SPORTS MAIN EVENT UHD 4K BACKUP"))
        assertEquals("FR", ChannelNames.countryPrefix("|fr| TF1"))
        assertEquals("FR", ChannelNames.countryPrefix("♣♦♣-----|FR| FRANCE"))
        assertNull(ChannelNames.countryPrefix("CANAL+ HD"))
        assertEquals("TF1", ChannelNames.withoutCountry("|FR| TF1"))
    }
}
