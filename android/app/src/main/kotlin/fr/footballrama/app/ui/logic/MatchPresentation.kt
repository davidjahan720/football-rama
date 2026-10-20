package fr.footballrama.app.ui.logic

import fr.footballrama.core.matching.ChannelGroup
import fr.footballrama.core.matching.MatchResult
import fr.footballrama.core.matching.Score
import java.text.Collator
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Logique de presentation pure (sans Android) : decoupage par jour, pays, filtres, anciennete. */

data class DaySections(val today: List<MatchResult>, val tomorrow: List<MatchResult>) {
    val isEmpty: Boolean get() = today.isEmpty() && tomorrow.isEmpty()
}

/**
 * Matchs du jour et du lendemain dans le fuseau [zone] de l'appareil, tries par coup d'envoi.
 * Les matchs d'hier (soirees UTC) ou d'apres-demain presents dans la fenetre du calendrier sont ignores.
 */
fun splitByDay(matches: List<MatchResult>, now: Instant, zone: ZoneId): DaySections {
    val today = now.atZone(zone).toLocalDate()
    val tomorrow = today.plusDays(1)
    val sorted = matches.sortedWith(compareBy<MatchResult> { it.kickoffUtcMillis }.thenBy { it.fixtureId })
    val byDay = sorted.groupBy { Instant.ofEpochMilli(it.kickoffUtcMillis).atZone(zone).toLocalDate() }
    return DaySections(byDay[today].orEmpty(), byDay[tomorrow].orEmpty())
}

object Countries {
    const val FRANCE = "FR"
    const val UK = "UK"
    private val ALIASES = mapOf("GB" to UK, "USA" to "US")

    /** Cle de regroupement : code pays en majuscules (GB -> UK, USA -> US), sinon nom de categorie tel quel, "" si inconnu. */
    fun key(country: String): String {
        val c = country.trim()
        if (c.isEmpty()) return ""
        if (c.length in 2..4 && c.all { it in 'A'..'Z' || it in 'a'..'z' }) {
            val up = c.uppercase(Locale.ROOT)
            return ALIASES[up] ?: up
        }
        return c
    }

    /** France, puis Royaume-Uni, puis les autres par ordre alphabetique de libelle (francais), "" en dernier. */
    fun comparator(label: (String) -> String): Comparator<String> {
        val collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }
        return Comparator { a, b ->
            val ra = rank(a)
            val rb = rank(b)
            if (ra != rb) ra - rb else collator.compare(label(a), label(b))
        }
    }

    private fun rank(key: String) = when (key) {
        FRANCE -> 0
        UK -> 1
        "" -> 3
        else -> 2
    }
}

data class CountrySection(val key: String, val channels: List<ChannelGroup>)

/** Chaines regroupees par pays ; dans un pays : "Confirme" avant "Probable", puis par nom. */
fun groupByCountry(channels: List<ChannelGroup>, label: (String) -> String): List<CountrySection> {
    val nameCollator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }
    return channels.groupBy { Countries.key(it.country) }
        .toSortedMap(Countries.comparator(label))
        .map { (key, list) ->
            CountrySection(
                key,
                list.sortedWith(
                    compareBy<ChannelGroup> { if (it.score == Score.FORT) 0 else 1 }
                        .then { x, y -> nameCollator.compare(x.displayName, y.displayName) },
                ),
            )
        }
}

/** Pays presents dans les matchs affiches, dans l'ordre d'affichage des filtres. */
fun availableCountries(matches: List<MatchResult>, label: (String) -> String): List<String> =
    matches.asSequence().flatMap { it.channels }.map { Countries.key(it.country) }.toSet()
        .sortedWith(Countries.comparator(label))

/** null = tous les pays. */
fun filterChannels(channels: List<ChannelGroup>, countryKey: String?): List<ChannelGroup> =
    if (countryKey == null) channels else channels.filter { Countries.key(it.country) == countryKey }

/** Filtre effectif : le filtre memorise s'il existe dans les donnees affichees, sinon "Tous". */
fun effectiveFilter(stored: String?, available: List<String>): String? = stored?.takeIf { it in available }

fun bestScore(channels: List<ChannelGroup>): Score? = when {
    channels.any { it.score == Score.FORT } -> Score.FORT
    channels.isNotEmpty() -> Score.MOYEN
    else -> null
}

/** Anciennete d'une mise a jour, pour "Mis a jour il y a X min". */
sealed interface Age {
    data object JustNow : Age
    data class Minutes(val value: Int) : Age
    data class Hours(val value: Int) : Age
    /** Plus de 24 h : afficher la date. */
    data class Older(val at: Instant) : Age
}

fun ageOf(updatedAt: Instant, now: Instant): Age {
    val d = Duration.between(updatedAt, now)
    return when {
        d < Duration.ofMinutes(1) -> Age.JustNow // y compris horloge decalee (date future)
        d < Duration.ofHours(1) -> Age.Minutes(d.toMinutes().toInt())
        d < Duration.ofHours(24) -> Age.Hours(d.toHours().toInt())
        else -> Age.Older(updatedAt)
    }
}

/** Rafraichissement automatique a l'ouverture si aucune donnee ou donnees plus vieilles que [maxAge]. */
fun shouldAutoRefresh(lastUpdate: Instant?, now: Instant, maxAge: Duration = Duration.ofMinutes(30)): Boolean =
    lastUpdate == null || Duration.between(lastUpdate, now) >= maxAge || lastUpdate.isAfter(now.plus(Duration.ofMinutes(5)))

private val QUALITY = Regex("""(?i)(?<![A-Za-z0-9])(UHD|4K|8K|FHD|HD|SD|HEVC|H265|50FPS|60FPS|LQ|HQ|RAW|BACKUP)(?![A-Za-z0-9])""")
private val QUALITY_ORDER = listOf("8K", "4K", "UHD", "FHD", "HD", "SD", "HQ", "LQ", "HEVC", "H265", "60FPS", "50FPS", "RAW", "BACKUP")

/** Mentions de qualite presentes dans les noms des flux d'une chaine, ex. ["FHD", "HD"] (sans doublon, ordre fixe). */
fun qualityTags(variantNames: List<String>): List<String> =
    variantNames.flatMap { n -> QUALITY.findAll(n).map { it.value.uppercase(Locale.ROOT) }.toList() }
        .toSet()
        .sortedBy { QUALITY_ORDER.indexOf(it) }
