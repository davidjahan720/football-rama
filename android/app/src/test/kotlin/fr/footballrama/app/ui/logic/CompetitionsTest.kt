package fr.footballrama.app.ui.logic

import fr.footballrama.core.matching.ChannelGroup
import fr.footballrama.core.matching.MatchResult
import fr.footballrama.core.matching.Score
import fr.footballrama.core.matching.StreamVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.pow

class CompetitionsTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-24T08:00:00Z")

    private fun ch(country: String) = ChannelGroup("C $country", "C $country", country, "cat", Score.FORT, "t", listOf(StreamVariant(1, "C")))

    private fun match(id: Long, code: String, name: String, kickoff: String, vararg countries: String) =
        MatchResult(id, code, name, Instant.parse(kickoff).toEpochMilli(), "H$id", "A$id", channels = countries.map { ch(it) })

    private val matches = listOf(
        match(1, "BL1", "Bundesliga", "2026-10-24T13:30:00Z", "DE", "FR"),
        match(2, "FL1", "Ligue 1", "2026-10-24T15:00:00Z", "FR"),
        match(3, "PD", "LaLiga", "2026-10-24T14:00:00Z", "ES"),
        match(4, "XYZ", "Coupe du Monde des Clubs", "2026-10-24T18:00:00Z", "UK"),
        match(5, "ELC", "Championship", "2026-10-25T14:00:00Z", "UK"),
        match(6, "CL", "Ligue des champions", "2026-10-25T19:00:00Z", "FR", "UK"),
        match(7, "QQQ", "", "2026-10-25T19:00:00Z"),
    )

    // ------------------------------------------------------------------ libelles

    @Test
    fun `libelles francais des 9 competitions`() {
        val expected = mapOf(
            "FL1" to "Ligue 1", "PL" to "Premier League", "CL" to "Ligue des champions", "BL1" to "Bundesliga",
            "SA" to "Serie A", "PD" to "LaLiga", "DED" to "Eredivisie", "PPL" to "Liga Portugal", "ELC" to "Championship",
        )
        expected.forEach { (code, label) -> assertEquals(label, Competitions.label(code, "ignored")) }
        assertEquals(expected.keys, Competitions.knownCodes)
        assertEquals(Competitions.ORDER.toSet(), Competitions.knownCodes)
    }

    @Test
    fun `code inconnu - nom du calendrier, sinon code, style neutre`() {
        assertEquals("Coupe du Monde des Clubs", Competitions.label("XYZ", "Coupe du Monde des Clubs"))
        assertEquals("QQQ", Competitions.label("QQQ", "  "))
        assertEquals("QQQ", Competitions.label("QQQ", null))
        assertEquals("?", Competitions.label("", null))
        assertSame(Competitions.DEFAULT_STYLE, Competitions.style("XYZ"))
    }

    // ------------------------------------------------------------------ couleurs

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `badges - contraste texte au moins 4,5 et teintes distinctes`() {
        val styles = Competitions.knownCodes.map { Competitions.style(it) } + Competitions.DEFAULT_STYLE
        for (s in styles) {
            assertTrue(contrast(s.background, s.content) >= 4.5, "contraste ${contrast(s.background, s.content)} pour $s")
            assertEquals(0xFF, (s.background shr 24) and 0xFF) // opaque
            assertEquals(0xFF, (s.content shr 24) and 0xFF)
        }
        assertEquals(styles.size, styles.map { it.background }.toSet().size)
    }

    // ------------------------------------------------------------------ filtre competition

    @Test
    fun `competitions presentes - ordre fixe puis inconnues par libelle`() {
        val visible = splitByDay(matches, now, paris).let { it.today + it.tomorrow }
        assertEquals(listOf("FL1", "CL", "BL1", "PD", "ELC", "XYZ", "QQQ"), availableCompetitions(visible))
        assertTrue(availableCompetitions(emptyList()).isEmpty())
    }

    @Test
    fun `filtre competition - tri conserve, null = toutes`() {
        val s = splitByDay(matches, now, paris)
        assertSame(s, filterByCompetition(s, null))
        val bl1 = filterByCompetition(s, "BL1")
        assertEquals(listOf(1L), bl1.today.map { it.fixtureId })
        assertTrue(bl1.tomorrow.isEmpty())
        assertEquals(listOf(1L, 3L, 2L, 4L), s.today.map { it.fixtureId }) // tri par heure inchange
        assertEquals(listOf(6L), filterByCompetition(s, "CL").tomorrow.map { it.fixtureId })
    }

    @Test
    fun `filtre memorise absent des donnees - retour a Toutes`() {
        val available = listOf("FL1", "BL1")
        assertEquals("BL1", effectiveFilter("BL1", available))
        assertNull(effectiveFilter("SA", available))
        assertNull(effectiveFilter(null, available))
    }

    @Test
    fun `combinaison competition puis pays`() {
        val s = splitByDay(matches, now, paris)
        val label: (String) -> String = { it }
        // Toutes competitions : pays de tous les matchs
        assertEquals(setOf("FR", "DE", "ES", "UK"), availableCountries(s.today + s.tomorrow, label).toSet())
        // Bundesliga : seuls les pays de ses matchs ; le filtre pays "ES" memorise retombe sur "Tous"
        val bl1 = filterByCompetition(s, "BL1")
        val countries = availableCountries(bl1.today + bl1.tomorrow, label)
        assertEquals(setOf("FR", "DE"), countries.toSet())
        assertNull(effectiveFilter("ES", countries))
        assertEquals("DE", effectiveFilter("DE", countries))
        // Le filtre pays ne masque pas de match, seulement les chaines
        assertEquals(listOf("DE"), filterChannels(bl1.today.single().channels, "DE").map { it.country })
        assertFalse(filterChannels(bl1.today.single().channels, "UK").isNotEmpty())
    }

    // ------------------------------------------------------------------ textes

    @Test
    fun `textes generaux et accents dans strings xml`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        assertFalse(xml.contains("Ligue 1, Premier League"), "texte limite aux 3 competitions initiales")
        assertTrue(xml.contains("grands championnats européens et de la Ligue des champions"))
        assertTrue(xml.contains("<string name=\"filter_competition_label\">Compétition</string>"))
        assertTrue(xml.contains("<string name=\"filter_all_competitions\">Toutes</string>"))
    }
}
