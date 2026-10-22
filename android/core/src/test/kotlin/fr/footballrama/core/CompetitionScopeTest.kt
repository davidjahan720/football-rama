package fr.footballrama.core

import fr.footballrama.core.TestAliases.fixture
import fr.footballrama.core.TestAliases.team
import fr.footballrama.core.epg.Programme
import fr.footballrama.core.fixtures.FixturesVerifier
import fr.footballrama.core.matching.Matcher
import fr.footballrama.core.matching.Score
import fr.footballrama.core.text.Normalizer
import fr.footballrama.core.xtream.LiveStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Extension du perimetre (BL1, SA, PD, DED, PPL, ELC) : tolerance aux codes inconnus et faux positifs. */
class CompetitionScopeTest {

    private val signer = TestSigner()
    private val verifier = FixturesVerifier(
        listOf(signer.publicKeyBase64),
        Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC),
    )

    /** Calendrier avec un nouveau code (BL1, nom fourni) et un code totalement inconnu (XYZ, sans nom ni mots-cles). */
    private val doc = """{"schema":1,"generated_at":"2026-10-20T06:00:00Z","valid_until":"2026-10-21T18:00:00Z",
       "window":{"from":"2026-10-20T00:00:00Z","to":"2026-10-22T03:00:00Z"},
       "competitions":{"BL1":"Bundesliga","CL":"Ligue des champions"},
       "matching":{"competition_keywords":{"BL1":["bundesliga"]},"excluded_title_words":["2 bundesliga"]},
       "matches":[
         {"id":1,"competition":"BL1","kickoff_utc":"2026-10-20T17:30:00Z",
          "home":{"name":"FC Bayern München","short":"Bayern","aliases":["bayern","bayern munich"],"tokens":["bayern"]},
          "away":{"name":"Borussia Dortmund","short":"Dortmund","aliases":["dortmund","bvb"],"tokens":["dortmund"]}},
         {"id":2,"competition":"XYZ","kickoff_utc":"2026-10-20T19:00:00Z",
          "home":{"name":"Club Alpha","aliases":["alpha"]},"away":{"name":"Club Beta","aliases":["beta"]}}]}""".toByteArray()

    @Test
    fun `codes nouveaux et inconnus acceptes - nom du fichier, sinon code`() {
        val f = verifier.verify(doc, signer.sign(doc), null)
        assertEquals(listOf("BL1", "XYZ"), f.matches.map { it.competition })
        val m = Matcher(f)
        val hits = m.findHits(
            listOf(
                prog("sky.de", "2026-10-20T17:15:00Z", "Bundesliga: Bayern München - Borussia Dortmund"),
                prog("sky.de", "2026-10-20T17:20:00Z", "2. Bundesliga: Konferenz", "Bayern II, Dortmund II"),
                prog("x.tv", "2026-10-20T18:50:00Z", "Alpha - Beta"),
            ),
        )
        val results = m.buildResults(
            hits,
            listOf(
                LiveStream(streamId = 1, name = "|DE| SKY SPORT", epgChannelId = "sky.de", categoryId = null),
                LiveStream(streamId = 2, name = "X", epgChannelId = "x.tv", categoryId = null),
            ),
            emptyList(),
        )
        assertEquals("Bundesliga", results[0].competitionName)
        assertEquals(Score.FORT, results[0].channels.single().score)
        assertEquals("XYZ", results[1].competitionName) // pas de nom : le code est affiche, rien n'est rejete
        assertEquals(Score.FORT, results[1].channels.single().score)
    }

    private val sporting = team("Sporting Clube de Portugal", "Sporting CP")
    private val benfica = team("Sport Lisboa e Benfica", "Benfica")
    private val arsenal = team("Arsenal FC", "Arsenal")
    private val k = "2026-10-20T19:00:00Z"

    private fun prog(channel: String, start: String, title: String, desc: String = "") =
        Programme(channel, Instant.parse(start), null, title, Normalizer.normalize("$title $desc"))

    private fun hits(fx: fr.footballrama.core.fixtures.Fixture, title: String, desc: String = "") =
        Matcher(TestAliases.fixtures(fx)).findHits(listOf(prog("c", k, title, desc)))[fx.id]!!

    @Test
    fun `alias publisher lus depuis aliases json`() {
        assertTrue(TestAliases.ALIASES_FILE.isFile)
        assertTrue("sporting" in sporting.aliases && "sporting cp" in sporting.aliases)
        assertTrue(sporting.tokens.isEmpty()) // ni "portugal" ni "clube"
        assertTrue("athletic" !in team("Athletic Club", "Athletic").aliases)
        assertTrue("barcelona" !in team("RCD Espanyol de Barcelona", "Espanyol").tokens)
    }

    @Test
    fun `Sporting Gijon - Real Oviedo ne remonte pas pour un match du Sporting CP`() {
        for (fx in listOf(fixture(1, "PPL", k, sporting, benfica), fixture(2, "CL", k, sporting, arsenal))) {
            assertTrue(hits(fx, "Sporting Gijón - Real Oviedo").isEmpty())
            assertTrue(hits(fx, "LaLiga Hypermotion: Sporting Gijón - Real Oviedo").isEmpty())
            assertTrue(hits(fx, "Fútbol: Sporting de Gijón", "LaLiga Hypermotion, jornada 11").isEmpty())
            assertTrue(hits(fx, "Sporting Braga - FC Porto", "Liga Portugal Betclic").isEmpty())
        }
    }

    @Test
    fun `Sporting CP reconnu dans les titres portugais et francais`() {
        val fx = fixture(1, "PPL", k, sporting, benfica)
        assertEquals(Score.FORT, hits(fx, "Sporting x Benfica").single().score)
        assertEquals(Score.FORT, hits(fx, "Liga Portugal: Sporting Lisbonne - Benfica").single().score)
        assertEquals(Score.MOYEN, hits(fx, "Futebol", "Sporting em direto, Liga Portugal Betclic").single().score)
    }

    @Test
    fun `mots-cles des nouvelles competitions et faux positifs ecartes`() {
        val bayern = team("FC Bayern München", "Bayern")
        val coventry = team("Coventry City FC", "Coventry City")
        val norwich = team("Norwich City FC", "Norwich City")
        assertEquals(Score.MOYEN, hits(fixture(1, "BL1", k, bayern, team("Borussia Dortmund", "Dortmund")), "Live", "Bundesliga mit dem FC Bayern").single().score)
        // "championship" seul n'est pas un mot-cle : le rugby a Coventry ne remonte pas
        assertTrue(hits(fixture(2, "ELC", k, coventry, norwich), "Rugby Union", "RFU Championship: Coventry v Ealing").isEmpty())
        assertEquals(Score.MOYEN, hits(fixture(3, "ELC", k, coventry, norwich), "Football", "Sky Bet Championship: Coventry City").single().score)
    }
}
