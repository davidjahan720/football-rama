package fr.footballrama.core.matching

import fr.footballrama.core.epg.Programme
import fr.footballrama.core.fixtures.Fixture
import fr.footballrama.core.fixtures.Fixtures
import fr.footballrama.core.text.Normalizer
import fr.footballrama.core.xtream.LiveCategory
import fr.footballrama.core.xtream.LiveStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

@Serializable
enum class Score {
    @SerialName("fort") FORT,
    @SerialName("moyen") MOYEN,
}

/** Un programme EPG retenu pour un match (`fx.hits` du prototype). */
data class Hit(val channelEpgId: String, val title: String, val start: Instant, val score: Score)

@Serializable
data class StreamVariant(val streamId: Long, val name: String)

/** Une chaine (flux FHD/HD/... regroupes par [ChannelNames.group]). */
@Serializable
data class ChannelGroup(
    /** Nom regroupe, ex. "|FR| CANAL+ LIVE 1". */
    val name: String,
    /** Nom sans prefixe pays, ex. "CANAL+ LIVE 1". */
    val displayName: String,
    /** Code du prefixe "|FR|" si present ("FR"), sinon nom de la categorie Xtream. */
    val country: String,
    val category: String,
    val score: Score,
    /** Titre EPG qui a permis la detection. */
    val epgTitle: String,
    val variants: List<StreamVariant>,
)

@Serializable
data class MatchResult(
    val fixtureId: Long,
    val competition: String,
    val competitionName: String,
    val kickoffUtcMillis: Long,
    val home: String,
    val away: String,
    val homeShort: String? = null,
    val awayShort: String? = null,
    val channels: List<ChannelGroup>,
)

object ChannelNames {
    private val QUALITY_TAGS =
        // Pas de flag inline U (Unicode) : non supporte par le moteur regex d'Android (ICU), crash au chargement de la classe
        Regex("""(?i)\b(?:FHD|UHD|HD|SD|LQ|HQ|4K|8K|HEVC|H265|50FPS|60FPS|RAW|BACKUP)\b""")
    private val MULTI_SPACE = Regex("""\s{2,}""")
    // Code pays de 2 a 4 lettres, avec un tiret eventuel ("|EX-YU|").
    private val COUNTRY_PREFIX = Regex("""^[^A-Za-z0-9|]*\|\s*([A-Za-z]{2,4}(?:-[A-Za-z]{2,4})?)\s*\|\s*""")

    /** `channel_group` : "|FR| CANAL+ LIVE 1 FHD" et "|FR| CANAL+ LIVE 1 HD" -> "|FR| CANAL+ LIVE 1". */
    fun group(name: String): String = MULTI_SPACE.replace(QUALITY_TAGS.replace(name, ""), " ").trim()

    fun countryPrefix(name: String): String? = COUNTRY_PREFIX.find(name)?.groupValues?.get(1)?.uppercase()

    fun withoutCountry(name: String): String = COUNTRY_PREFIX.replaceFirst(name, "").trim().ifEmpty { name }
}

/** Regles de matching du contrat, portage fidele de `match()` / `foreign_team_in_title()` de scripts/poc_epg.py. */
class Matcher(private val fixtures: Fixtures) {

    /** fixtureId -> hits, dans l'ordre chronologique des programmes. */
    fun findHits(programmes: List<Programme>): Map<Long, List<Hit>> {
        val ordered = programmes.sortedBy { it.start }
        val starts = ordered.map { it.start }
        val out = LinkedHashMap<Long, List<Hit>>()
        for (fx in fixtures.matches) {
            val lo = fx.kickoff.minus(BEFORE)
            val hi = fx.kickoff.plus(AFTER)
            val keywords = fixtures.competitionKeywords[fx.competition].orEmpty()
            val hits = ArrayList<Hit>()
            var i = lowerBound(starts, fx.kickoff.minus(COVER))
            while (i < ordered.size) {
                val p = ordered[i++]
                if (p.start.isAfter(hi)) break
                val covers = !p.start.isAfter(fx.kickoff) && (p.stop ?: p.start).isAfter(fx.kickoff)
                if (!(!p.start.isBefore(lo) || covers)) continue
                val title = Normalizer.normalize(p.title)
                if (fixtures.excludedTitleWords.any { Normalizer.containsPhrase(title, it) }) continue
                val home = fx.home.isIn(p.text)
                val away = fx.away.isIn(p.text)
                val comp = keywords.any { Normalizer.containsPhrase(p.text, it) }
                if ((home || away) && foreignTeamInTitle(p.title, fx)) continue
                val score = when {
                    fx.home.isIn(title) && fx.away.isIn(title) -> Score.FORT
                    home && away -> Score.MOYEN
                    (home || away) && comp -> Score.MOYEN
                    else -> null
                } ?: continue
                hits.add(Hit(p.channelId, p.title, p.start, score))
            }
            out[fx.id] = hits
        }
        return out
    }

    /**
     * Resultat final : chaque match avec ses chaines (flux de l'utilisateur), regroupees, triees
     * fort avant moyen, puis par pays et par nom. Les chaines EPG sans flux Xtream correspondant sont ignorees
     * (non lisibles par l'utilisateur).
     */
    fun buildResults(
        hits: Map<Long, List<Hit>>,
        streams: List<LiveStream>,
        categories: List<LiveCategory>,
    ): List<MatchResult> {
        val catName = categories.associate { it.id to it.name }
        val byEpg = HashMap<String, MutableList<LiveStream>>()
        for (s in streams) {
            val id = s.epgChannelId?.lowercase() ?: continue
            byEpg.getOrPut(id) { ArrayList() }.add(s)
        }
        return fixtures.matches.sortedWith(compareBy<Fixture> { it.kickoff }.thenBy { it.id }).map { fx ->
            val groups = LinkedHashMap<String, MutableGroup>()
            val fxHits = hits[fx.id].orEmpty().sortedBy { if (it.score == Score.FORT) 0 else 1 }
            for (h in fxHits) {
                for (s in byEpg[h.channelEpgId].orEmpty()) {
                    val key = ChannelNames.group(s.name)
                    val category = s.categoryId?.let { catName[it] }.orEmpty()
                    val g = groups.getOrPut(key) { MutableGroup(key, category, h.score, h.title) }
                    if (g.variants.none { it.streamId == s.streamId }) g.variants.add(StreamVariant(s.streamId, s.name))
                }
            }
            MatchResult(
                fixtureId = fx.id,
                competition = fx.competition,
                competitionName = fixtures.competitions[fx.competition] ?: fx.competition,
                kickoffUtcMillis = fx.kickoff.toEpochMilli(),
                home = fx.home.name,
                away = fx.away.name,
                homeShort = fx.home.short,
                awayShort = fx.away.short,
                channels = groups.values.map { it.toChannel() }
                    .sortedWith(compareBy<ChannelGroup> { it.score.ordinal }.thenBy { it.country }.thenBy { it.name }),
            )
        }
    }

    private class MutableGroup(val name: String, val category: String, val score: Score, val title: String) {
        val variants = ArrayList<StreamVariant>()
        fun toChannel() = ChannelGroup(
            name = name,
            displayName = ChannelNames.withoutCountry(name),
            country = ChannelNames.countryPrefix(name) ?: category,
            category = category,
            score = score,
            epgTitle = title,
            variants = variants.toList(),
        )
    }

    companion object {
        private val BEFORE = Duration.ofMinutes(60)
        private val AFTER = Duration.ofMinutes(30)
        private val COVER = Duration.ofHours(6)

        // "\s+(-|–|—|v|vs|x|/)\.?\s+", insensible a la casse ; prefixe "LIVE: UCL: " retire.
        // Espaces Unicode listes explicitement : le flag inline U n existe pas sur Android (ICU)
        private val TEAM_SEPARATOR = Regex("""(?i)[\s   ]+(?:-|–|—|v|vs|x|/)\.?[\s   ]+""")
        private val TITLE_PREFIX = Regex("""^.*:\s+""")

        /** `foreign_team_in_title` : "A - B" dont un des 2 premiers cotes n'est aucune des equipes du match. */
        fun foreignTeamInTitle(title: String, fx: Fixture): Boolean {
            val sides = TEAM_SEPARATOR.split(TITLE_PREFIX.replaceFirst(title, ""))
            if (sides.size < 2) return false
            for (side in sides.take(2).map { Normalizer.normalize(it) }) {
                if (!(fx.home.isIn(side) || fx.away.isIn(side))) return true
            }
            return false
        }

        private fun lowerBound(sorted: List<Instant>, key: Instant): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid].isBefore(key)) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
