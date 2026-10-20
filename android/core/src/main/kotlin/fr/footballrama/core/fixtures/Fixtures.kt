package fr.footballrama.core.fixtures

import fr.footballrama.core.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Format brut de fixtures.json, schema 1 (docs/CONTRAT_FIXTURES.md). */
@Serializable
data class FixturesDocument(
    val schema: Int,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("valid_until") val validUntil: String,
    val window: WindowDto,
    val competitions: Map<String, String> = emptyMap(),
    val matching: MatchingDto = MatchingDto(),
    val matches: List<MatchDto> = emptyList(),
)

@Serializable
data class WindowDto(val from: String, val to: String)

@Serializable
data class MatchingDto(
    @SerialName("competition_keywords") val competitionKeywords: Map<String, List<String>> = emptyMap(),
    @SerialName("excluded_title_words") val excludedTitleWords: List<String> = emptyList(),
)

@Serializable
data class MatchDto(
    val id: Long,
    val competition: String,
    @SerialName("kickoff_utc") val kickoffUtc: String,
    val home: TeamDto,
    val away: TeamDto,
)

@Serializable
data class TeamDto(
    val name: String,
    val short: String? = null,
    val aliases: List<String> = emptyList(),
    val tokens: List<String> = emptyList(),
)

/** Calendrier verifie (signature + regles), pret pour le matching. Termes re-normalises par defense. */
data class Fixtures(
    val schema: Int,
    val generatedAt: Instant,
    val validUntil: Instant,
    val windowFrom: Instant,
    val windowTo: Instant,
    val competitions: Map<String, String>,
    val competitionKeywords: Map<String, List<String>>,
    val excludedTitleWords: List<String>,
    val matches: List<Fixture>,
) {
    /** Tous les alias/tokens d'equipes (prefiltre du parseur EPG). */
    val allTeamTerms: Set<String> by lazy {
        matches.flatMapTo(HashSet()) { it.home.terms + it.away.terms }
    }
}

data class Fixture(
    val id: Long,
    val competition: String,
    val kickoff: Instant,
    val home: Team,
    val away: Team,
)

data class Team(
    val name: String,
    val short: String?,
    val aliases: List<String>,
    val tokens: List<String>,
) {
    val terms: List<String> get() = aliases + tokens

    /** `team_hit` du prototype. */
    fun isIn(normalizedText: String): Boolean =
        aliases.any { Normalizer.containsPhrase(normalizedText, it) } ||
            tokens.any { Normalizer.containsPhrase(normalizedText, it) }
}

/** Mentions obligatoires a afficher par l'UI. */
object DataAttribution {
    /** Obligation de licence football-data.org : texte a afficher tel quel dans l'UI. */
    const val FOOTBALL_DATA = "Football data provided by the Football-Data.org API"
}
