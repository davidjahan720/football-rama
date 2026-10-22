package fr.footballrama.app.ui.logic

import fr.footballrama.core.matching.MatchResult
import java.text.Collator
import java.util.Locale

/**
 * Competitions du calendrier : libelle francais, couleurs du badge, ordre des filtres (pur, teste en JVM).
 *
 * Un code inconnu de cette version de l'app (competition ajoutee plus tard par le publisher) est affiche
 * avec le nom fourni par fixtures.json, sinon avec le code brut, et le style neutre : rien n'est rejete.
 */
object Competitions {

    /** Couleurs ARGB du badge : fond sombre + texte clair, contraste >= 4,5:1 (verifie par les tests). */
    data class Style(val background: Long, val content: Long)

    /** Ordre d'affichage des filtres ; les codes inconnus suivent, par ordre alphabetique de libelle. */
    val ORDER = listOf("FL1", "PL", "CL", "UEL", "UECL", "BL1", "SA", "PD", "DED", "PPL", "ELC")

    private val LABELS = mapOf(
        "FL1" to "Ligue 1",
        "PL" to "Premier League",
        "CL" to "Ligue des champions",
        "UEL" to "Ligue Europa",
        "UECL" to "Conference League",
        "BL1" to "Bundesliga",
        "SA" to "Serie A",
        "PD" to "LaLiga",
        "DED" to "Eredivisie",
        "PPL" to "Liga Portugal",
        "ELC" to "Championship",
    )

    /** Style neutre (= ancien badge unique FrColors.BadgeBg / BadgeText). */
    val DEFAULT_STYLE = Style(0xFF2A3440, 0xFFD6E2EE)

    // Teintes sobres et sombres, une par competition (pas de vert/ambre : reserves a Confirme/Probable).
    private val STYLES = mapOf(
        "FL1" to Style(0xFF1D3557, 0xFFDCE8FF), // bleu marine
        "PL" to Style(0xFF3B1F5C, 0xFFEADCFF), // violet
        "CL" to Style(0xFF16306B, 0xFFD6E2FF), // bleu nuit
        "UEL" to Style(0xFF5A1A30, 0xFFFFD9E4), // bordeaux
        "UECL" to Style(0xFF2C2F66, 0xFFE0E0FF), // indigo
        "BL1" to Style(0xFF5C1F1F, 0xFFFFDAD4), // rouge brique
        "SA" to Style(0xFF0E4552, 0xFFCFF1F8), // bleu canard
        "PD" to Style(0xFF5A2D0C, 0xFFFFE2CC), // brun orange
        "DED" to Style(0xFF57204A, 0xFFFFD9F1), // prune
        "PPL" to Style(0xFF12463A, 0xFFCDF3E3), // vert sapin
        "ELC" to Style(0xFF3A3F4A, 0xFFE6E9EE), // ardoise
    )

    val knownCodes: Set<String> get() = LABELS.keys

    /** Libelle francais ; code inconnu : nom du calendrier, sinon le code. */
    fun label(code: String, nameFromFixtures: String? = null): String =
        LABELS[code] ?: nameFromFixtures?.trim()?.takeIf { it.isNotEmpty() } ?: code.trim().ifEmpty { "?" }

    fun label(match: MatchResult): String = label(match.competition, match.competitionName)

    fun style(code: String): Style = STYLES[code] ?: DEFAULT_STYLE

    /** Codes connus dans [ORDER], puis inconnus par libelle (francais). */
    fun comparator(label: (String) -> String = { code -> Competitions.label(code) }): Comparator<String> {
        val collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }
        return Comparator { a, b ->
            val ra = ORDER.indexOf(a).let { if (it < 0) ORDER.size else it }
            val rb = ORDER.indexOf(b).let { if (it < 0) ORDER.size else it }
            if (ra != rb) ra - rb else collator.compare(label(a), label(b)).takeIf { it != 0 } ?: a.compareTo(b)
        }
    }
}

/** Competitions presentes dans les matchs affiches, dans l'ordre des filtres. */
fun availableCompetitions(matches: List<MatchResult>): List<String> {
    val names = matches.associate { it.competition to it.competitionName }
    return names.keys.sortedWith(Competitions.comparator { Competitions.label(it, names[it]) })
}

/** null = toutes les competitions. Le tri (coup d'envoi) est conserve. */
fun filterByCompetition(sections: DaySections, code: String?): DaySections =
    if (code == null) {
        sections
    } else {
        DaySections(sections.today.filter { it.competition == code }, sections.tomorrow.filter { it.competition == code })
    }
