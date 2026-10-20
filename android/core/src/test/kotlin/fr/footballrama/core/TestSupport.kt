package fr.footballrama.core

import com.google.crypto.tink.subtle.Ed25519Sign
import fr.footballrama.core.fixtures.Fixture
import fr.footballrama.core.fixtures.Fixtures
import fr.footballrama.core.fixtures.Team
import fr.footballrama.core.text.Normalizer
import org.kxml2.io.KXmlParser
import java.time.Instant
import java.util.Base64

/**
 * Portage Kotlin (tests uniquement) de la generation d'alias du publisher
 * (publisher/football_rama_publisher/aliases.py + aliases.json), pour construire des calendriers
 * au format du contrat a partir de noms d'equipes football-data.
 */
object TestAliases {
    val COMPETITIONS = mapOf("PL" to "Premier League", "FL1" to "Ligue 1", "CL" to "Ligue des champions")
    val COMPETITION_KEYWORDS = mapOf(
        "PL" to listOf("premier league", "epl"),
        "FL1" to listOf("ligue 1", "ligue1", "mcdonald"),
        "CL" to listOf("champions league", "ligue des champions", "uefa champions", "ucl", "liga de campeones", "champions"),
    )
    val EXCLUDED_TITLE_WORDS = listOf(
        "youth league", "u19", "u21", "u23", "women", "womens", "feminin", "feminine", "femenino",
        "frauen", "wsl", "highlights", "resume", "resumen", "replay", "rediffusion", "rediff",
        "magazine", "best of", "golos", "goals", "talk", "preview", "avant match", "debrief",
    )
    private val STOP_WORDS = setOf(
        "fc", "afc", "sc", "ac", "as", "cf", "club", "de", "du", "la", "le", "the", "and", "football",
        "olympique", "stade", "racing", "sporting", "association", "sad", "sv", "ssc", "us", "rc", "ogc", "losc",
        "sk", "fk", "pae", "bk", "nk", "cp",
    )
    private val GENERIC_WORDS = setOf(
        "united", "city", "real", "saint", "germain", "athletic", "atletico", "borussia", "royal", "union",
        "dynamo", "dinamo", "manchester", "madrid", "milano", "london", "lisboa", "glasgow", "sheffield",
        "bristol", "hotspur", "wanderers", "albion", "rovers", "county", "town", "sport", "sports",
        "calcio", "football", "futbol", "olympique", "sporting", "club", "young", "boys", "1907", "paris",
    )
    private val EXTRA = mapOf(
        "paris saint germain" to listOf("psg", "paris sg"),
        "marseille" to listOf("om", "marseille"),
        "lyonnais" to listOf("ol", "lyon"),
        "lille osc" to listOf("losc", "lille"),
        "manchester united" to listOf("man utd", "man united", "manchester utd"),
        "manchester city" to listOf("man city"),
        "tottenham hotspur" to listOf("spurs", "tottenham"),
        "wolverhampton wanderers" to listOf("wolves"),
        "brighton hove albion" to listOf("brighton"),
        "nottingham forest" to listOf("nottm forest", "forest"),
        "newcastle united" to listOf("newcastle"),
        "west ham united" to listOf("west ham"),
        "bayern munchen" to listOf("bayern", "bayern munich"),
        "internazionale milano" to listOf("inter", "inter milan", "internazionale"),
        "barcelona" to listOf("barca", "barcelone"),
        "atletico madrid" to listOf("atletico", "atl madrid"),
        "real madrid" to listOf("real madrid"),
        "borussia dortmund" to listOf("dortmund", "bvb"),
        "bayer 04 leverkusen" to listOf("leverkusen"),
        "juventus" to listOf("juve"),
        "milan" to listOf("ac milan"),
        "napoli" to listOf("naples"),
        "aek" to listOf("aek athens", "aek athenes", "aek athen", "aek"),
        "psv" to listOf("psv eindhoven", "psv"),
        "slavia praha" to listOf("slavia prague", "slavia praga", "slavia praag", "slavia"),
    )

    private fun core(full: String): String {
        val c = full.split(" ").filter { it.isNotEmpty() && it !in STOP_WORDS }.joinToString(" ")
        return c.split(" ").filter { it.isNotEmpty() && !(it.all(Char::isDigit) && it.length >= 4) }
            .joinToString(" ").ifEmpty { c }
    }

    fun aliases(name: String, short: String?): List<String> {
        val full = Normalizer.normalize(name)
        val set = linkedSetOf(full, Normalizer.normalize(short ?: ""))
        val c = core(full)
        set.add(c)
        val explicit = (EXTRA[c].orEmpty() + c).toSet()
        set.addAll(explicit)
        return set.filter { it.isNotEmpty() && (it.length >= 4 || (it in explicit && it.length >= 2)) }.sorted()
    }

    fun tokens(name: String, short: String?): List<String> =
        listOf(name, short ?: "").flatMap { Normalizer.normalize(it).split(" ") }
            .filter { it.length >= 5 && it !in STOP_WORDS && it !in GENERIC_WORDS && !it.all(Char::isDigit) }
            .toSortedSet().toList()

    fun team(name: String, short: String? = null) = Team(name, short, aliases(name, short), tokens(name, short))

    fun fixture(id: Long, comp: String, kickoff: String, home: Team, away: Team) =
        Fixture(id, comp, Instant.parse(kickoff), home, away)

    fun fixtures(vararg matches: Fixture, from: String = "2026-10-20T00:00:00Z", to: String = "2026-10-22T03:00:00Z") =
        Fixtures(
            schema = 1,
            generatedAt = Instant.parse("2026-10-20T06:00:00Z"),
            validUntil = Instant.parse("2026-10-21T18:00:00Z"),
            windowFrom = Instant.parse(from),
            windowTo = Instant.parse(to),
            competitions = COMPETITIONS,
            competitionKeywords = COMPETITION_KEYWORDS,
            excludedTitleWords = EXCLUDED_TITLE_WORDS,
            matches = matches.toList(),
        )
}

/** Paire de cles Ed25519 de TEST generee a la volee (aucune cle reelle dans le depot). */
class TestSigner {
    private val pair = Ed25519Sign.KeyPair.newKeyPair()
    private val signer = Ed25519Sign(pair.privateKey)
    val publicKeyBase64: String = Base64.getEncoder().encodeToString(pair.publicKey)
    fun sign(bytes: ByteArray): ByteArray = Base64.getEncoder().encode(signer.sign(bytes))
}

val kxmlFactory: () -> KXmlParser = { KXmlParser() }
