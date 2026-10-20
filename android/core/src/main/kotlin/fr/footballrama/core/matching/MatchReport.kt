package fr.footballrama.core.matching

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Resultat complet d'un rafraichissement, persiste tel quel (aucun identifiant dedans). */
@Serializable
data class MatchReport(
    val version: Int = 1,
    /** Fin du rafraichissement (epoch ms). */
    val updatedAtMillis: Long,
    /** generated_at du calendrier utilise (epoch ms). */
    val fixturesGeneratedAtMillis: Long,
    val matches: List<MatchResult>,
    val stats: ReportStats,
)

@Serializable
data class ReportStats(
    val liveStreams: Int,
    val programmesInWindow: Int,
    val programmesKept: Int,
    val matchesWithChannels: Int,
    /** Duree du parsing EPG (ms). */
    val parseMillis: Long,
)

object MatchReportCodec {
    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(report: MatchReport): String = JSON.encodeToString(MatchReport.serializer(), report)

    /** null si le fichier est illisible ou d'une version inconnue. */
    fun decode(text: String): MatchReport? =
        runCatching { JSON.decodeFromString(MatchReport.serializer(), text) }.getOrNull()?.takeIf { it.version == 1 }
}
