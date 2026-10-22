package fr.footballrama.app.ui.logic

import fr.footballrama.core.matching.ChannelGroup
import fr.footballrama.core.matching.MatchResult
import fr.footballrama.core.matching.Score
import fr.footballrama.core.matching.StreamVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class MatchPresentationTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val labels = mapOf(
        "FR" to "France", "UK" to "Royaume-Uni", "BE" to "Belgique", "CH" to "Suisse",
        "DE" to "Allemagne", "ES" to "Espagne", "" to "Autres",
    )
    private val label: (String) -> String = { labels[it] ?: it }

    private fun ch(name: String, country: String, score: Score = Score.FORT, variants: List<String> = listOf(name)) =
        ChannelGroup(name, name, country, "cat", score, "Titre EPG", variants.mapIndexed { i, v -> StreamVariant(i.toLong(), v) })

    private fun match(id: Long, kickoffUtc: String, channels: List<ChannelGroup> = emptyList()) =
        MatchResult(id, "FL1", "Ligue 1", Instant.parse(kickoffUtc).toEpochMilli(), "Domicile $id", "Exterieur $id", channels = channels)

    // ------------------------------------------------------------------ Aujourd'hui / Demain

    @Test
    fun `decoupage en heure locale - 22h30 UTC est le lendemain a Paris`() {
        val now = Instant.parse("2026-10-20T10:00:00Z") // 12:00 a Paris, le 20
        val matches = listOf(
            match(1, "2026-10-20T19:00:00Z"), // 21:00 le 20 -> aujourd'hui
            match(2, "2026-10-20T22:30:00Z"), // 00:30 le 21 -> demain
            match(3, "2026-10-21T19:00:00Z"), // demain
            match(4, "2026-10-19T21:00:00Z"), // 23:00 le 19 -> hier, ignore
            match(5, "2026-10-21T22:30:00Z"), // 00:30 le 22 -> apres-demain, ignore
        )
        val s = splitByDay(matches, now, paris)
        assertEquals(listOf(1L), s.today.map { it.fixtureId })
        assertEquals(listOf(2L, 3L), s.tomorrow.map { it.fixtureId })
    }

    @Test
    fun `decoupage depend du fuseau de l'appareil`() {
        val now = Instant.parse("2026-10-20T10:00:00Z")
        val m = listOf(match(1, "2026-10-21T02:00:00Z"))
        assertEquals(1, splitByDay(m, now, ZoneId.of("America/Montreal")).today.size) // 22:00 le 20
        assertEquals(1, splitByDay(m, now, paris).tomorrow.size) // 04:00 le 21
    }

    @Test
    fun `tri par heure puis identifiant et liste vide`() {
        val now = Instant.parse("2026-10-20T08:00:00Z")
        val s = splitByDay(
            listOf(match(9, "2026-10-20T19:00:00Z"), match(3, "2026-10-20T19:00:00Z"), match(1, "2026-10-20T15:00:00Z")),
            now,
            paris,
        )
        assertEquals(listOf(1L, 3L, 9L), s.today.map { it.fixtureId })
        assertTrue(splitByDay(emptyList(), now, paris).isEmpty)
    }

    @Test
    fun `passage a minuit - demain devient aujourd'hui`() {
        val m = listOf(match(1, "2026-10-21T19:00:00Z"))
        assertEquals(1, splitByDay(m, Instant.parse("2026-10-20T21:59:00Z"), paris).tomorrow.size)
        assertEquals(1, splitByDay(m, Instant.parse("2026-10-20T22:01:00Z"), paris).today.size)
    }

    // ------------------------------------------------------------------ pays

    @Test
    fun `cles pays normalisees`() {
        assertEquals("FR", Countries.key("fr"))
        assertEquals("UK", Countries.key("GB"))
        assertEquals("US", Countries.key("usa"))
        assertEquals("", Countries.key("  "))
        assertEquals("FR", Countries.key(" SPORTS FRANCE "))
    }

    @Test
    fun `prefixes reels de la liste IPTV - libelles francais`() {
        val expected = mapOf(
            "FR" to "France", "UK" to "Royaume-Uni", "DE" to "Allemagne", "BE" to "Belgique", "CH" to "Suisse",
            "NL" to "Pays-Bas", "IT" to "Italie", "ES" to "Espagne", "PT" to "Portugal", "PL" to "Pologne",
            "CZ" to "Tchéquie", "RO" to "Roumanie", "DK" to "Danemark", "AR" to "Chaînes arabes", "ARG" to "Argentine",
            "BR" to "Brésil", "LAT" to "Amérique latine", "IN" to "Inde", "EX-YU" to "Balkans",
        )
        expected.forEach { (prefix, label) ->
            assertEquals(label, Countries.label(Countries.key(prefix)), prefix)
            assertEquals(label, Countries.label(Countries.key("|$prefix| SPORTS")), "|$prefix| SPORTS")
        }
        assertEquals("Autres", Countries.label(Countries.key("")))
    }

    @Test
    fun `categories sans prefixe - suffixe TV retire et synonymes regroupes`() {
        assertEquals("EX-YU", Countries.key("BALKANS | TV"))
        assertEquals("EX-YU", Countries.key("ex-yu"))
        assertEquals("AR", Countries.key("SPORTS ARABIC | TV"))
        assertEquals("CH", Countries.key("SWITZERLAND | TV"))
        assertEquals("UK", Countries.key("GB"))
        assertEquals("KURDISTAN", Countries.key("KURDISTAN | TV"))
        assertEquals("Kurdistan", Countries.label("KURDISTAN"))
        assertEquals("KIDS", Countries.key("KIDS | TV")) // 4 lettres + " | " : categorie, pas un code
        assertEquals("TV", Countries.key("| TV |"))
    }

    @Test
    fun `filtres - libelles identiques regroupes, ordre France, Royaume-Uni, alphabetique, Autres en dernier`() {
        val m = listOf(
            match(1, "2026-10-20T19:00:00Z", listOf(
                ch("A", "BALKANS | TV"), ch("B", "EX-YU"), ch("C", "SWITZERLAND | TV"), ch("D", "CH"), ch("E", ""),
                ch("F", "AR"), ch("G", "SPORTS ARABIC | TV"), ch("H", "UK"), ch("I", "GB"), ch("J", "FR"), ch("K", "ARG"),
            )),
        )
        val keys = availableCountries(m, Countries::label)
        assertEquals(listOf("France", "Royaume-Uni", "Argentine", "Balkans", "Chaînes arabes", "Suisse", "Autres"), keys.map(Countries::label))
        assertEquals(listOf("C", "D"), filterChannels(m[0].channels, "CH").map { it.name }.sorted())
    }

    // ------------------------------------------------------------------ matchs termines, noms courts

    @Test
    fun `match masque 2 h 30 apres le coup d'envoi`() {
        val kickoff = "2026-10-20T19:00:00Z"
        val m = match(1, kickoff)
        val k = Instant.parse(kickoff)
        assertFalse(isFinished(m, k.minusSeconds(60)))
        assertFalse(isFinished(m, k.plus(Duration.ofMinutes(149))))
        assertTrue(isFinished(m, k.plus(Duration.ofMinutes(150))))
        assertEquals(listOf(2L), visibleMatches(listOf(m, match(2, "2026-10-20T21:00:00Z")), k.plus(Duration.ofMinutes(151))).map { it.fixtureId })
        // splitByDay applique le masquage
        val s = splitByDay(listOf(m, match(2, "2026-10-20T20:00:00Z")), Instant.parse("2026-10-20T21:31:00Z"), paris)
        assertEquals(listOf(2L), s.today.map { it.fixtureId })
    }

    @Test
    fun `nom court sur les cartes, nom complet sinon`() {
        assertEquals("Dortmund", cardTeamName("Borussia Dortmund", "Dortmund"))
        assertEquals("Slavia Praha", cardTeamName("SK Slavia Praha", " Slavia Praha "))
        assertEquals("Paris FC", cardTeamName("Paris FC", null))
        assertEquals("Paris FC", cardTeamName("Paris FC", "  "))
    }

    @Test
    fun `regroupement - FR, UK puis ordre alphabetique des libelles, Autres a la fin`() {
        val channels = listOf(
            ch("Sky DE", "DE"), ch("TF1", "FR"), ch("Sky Sports", "UK"), ch("Inconnue", ""),
            ch("RTS", "CH"), ch("RTBF", "BE"), ch("BT Sport", "GB"), ch("DAZN", "ES"),
        )
        val sections = groupByCountry(channels, label)
        // Allemagne, Belgique, Espagne, Suisse : ordre alphabetique francais des libelles
        assertEquals(listOf("FR", "UK", "DE", "BE", "ES", "CH", ""), sections.map { it.key })
        assertEquals(listOf("BT Sport", "Sky Sports"), sections[1].channels.map { it.name })
    }

    @Test
    fun `tri alphabetique insensible aux accents`() {
        val l: (String) -> String = { mapOf("US" to "États-Unis", "ES" to "Espagne", "IT" to "Italie")[it] ?: it }
        assertEquals(listOf("ES", "US", "IT"), groupByCountry(listOf(ch("a", "IT"), ch("b", "US"), ch("c", "ES")), l).map { it.key })
    }

    @Test
    fun `dans un pays - Confirme avant Probable puis par nom`() {
        val sections = groupByCountry(
            listOf(ch("Z chaine", "FR", Score.MOYEN), ch("B chaine", "FR", Score.FORT), ch("A chaine", "FR", Score.MOYEN)),
            label,
        )
        assertEquals(listOf("B chaine", "A chaine", "Z chaine"), sections.single().channels.map { it.name })
    }

    @Test
    fun `pays disponibles, filtre et filtre effectif`() {
        val ms = listOf(
            match(1, "2026-10-20T19:00:00Z", listOf(ch("A", "BE"), ch("B", "FR"))),
            match(2, "2026-10-20T19:00:00Z", listOf(ch("C", "UK"), ch("D", "FR"))),
        )
        val countries = availableCountries(ms, label)
        assertEquals(listOf("FR", "UK", "BE"), countries)
        assertEquals(listOf("B"), filterChannels(ms[0].channels, "FR").map { it.name })
        assertEquals(2, filterChannels(ms[0].channels, null).size)
        assertEquals("FR", effectiveFilter("FR", countries))
        assertNull(effectiveFilter("DE", countries)) // pays memorise absent aujourd'hui -> Tous
        assertNull(effectiveFilter(null, countries))
    }

    @Test
    fun `meilleur score`() {
        assertEquals(Score.FORT, bestScore(listOf(ch("a", "FR", Score.MOYEN), ch("b", "FR", Score.FORT))))
        assertEquals(Score.MOYEN, bestScore(listOf(ch("a", "FR", Score.MOYEN))))
        assertNull(bestScore(emptyList()))
    }

    @Test
    fun `mentions de qualite des variantes`() {
        assertEquals(listOf("FHD", "HD"), qualityTags(listOf("|FR| CANAL+ HD", "|FR| CANAL+ FHD", "|FR| CANAL+ hd")))
        assertEquals(listOf("4K", "HEVC"), qualityTags(listOf("BEIN 4K HEVC")))
        assertEquals(emptyList<String>(), qualityTags(listOf("|FR| TF1", "SHDTV"))) // pas de faux positif dans un mot
    }

    // ------------------------------------------------------------------ anciennete

    @Test
    fun `il y a X min - bornes`() {
        val t = Instant.parse("2026-10-20T10:00:00Z")
        assertEquals(Age.JustNow, ageOf(t, t.plusSeconds(59)))
        assertEquals(Age.JustNow, ageOf(t, t.minusSeconds(600))) // horloge decalee
        assertEquals(Age.Minutes(1), ageOf(t, t.plusSeconds(60)))
        assertEquals(Age.Minutes(59), ageOf(t, t.plusSeconds(3599)))
        assertEquals(Age.Hours(1), ageOf(t, t.plusSeconds(3600)))
        assertEquals(Age.Hours(23), ageOf(t, t.plus(Duration.ofHours(24)).minusSeconds(1)))
        assertEquals(Age.Older(t), ageOf(t, t.plus(Duration.ofHours(24))))
    }

    @Test
    fun `rafraichissement automatique a l'ouverture`() {
        val now = Instant.parse("2026-10-20T10:00:00Z")
        assertTrue(shouldAutoRefresh(null, now))
        assertFalse(shouldAutoRefresh(now.minus(Duration.ofMinutes(10)), now))
        assertTrue(shouldAutoRefresh(now.minus(Duration.ofMinutes(30)), now))
        assertTrue(shouldAutoRefresh(now.plus(Duration.ofHours(2)), now)) // date future incoherente
    }
}
