package fr.footballrama.app.ui.logic

import java.text.Collator
import java.util.Locale

/**
 * Pays des chaines : cle canonique + libelle francais (pur, sans ressource Android, teste en JVM).
 *
 * Entrees reelles : prefixe du nom de chaine ("FR", "UK", "EX-YU"...) ou, a defaut, nom de categorie
 * ("|FR| SPORTS", "BALKANS | TV", "SPORTS ARABIC | TV", "SWITZERLAND | TV"). Les synonymes sont ramenes a la
 * meme cle (GB -> UK, BALKANS -> EX-YU, SPORTS ARABIC -> AR...) : un seul filtre par libelle.
 * Aucune expression reguliere (compatibilite du moteur ICU d'Android).
 */
object Countries {
    const val FRANCE = "FR"
    const val UK = "UK"

    /** Cle "Autres" (pays absent ou illisible). */
    const val OTHER = ""

    private val CODE_ALIASES = mapOf(
        "GB" to UK, "ENG" to UK, "USA" to "US", "EXYU" to "EX-YU", "YU" to "EX-YU", "ARA" to "AR", "ARB" to "AR",
        "LATAM" to "LAT", "FRA" to FRANCE, "GER" to "DE", "SUI" to "CH", "NED" to "NL", "ESP" to "ES",
        "ITA" to "IT", "POR" to "PT", "BEL" to "BE", "POL" to "PL", "BRA" to "BR", "AFR" to "AF",
    )

    /** Noms de categorie sans prefixe (mots de remplissage retires) -> code. */
    private val NAME_TO_CODE = mapOf(
        "FRANCE" to FRANCE, "FRENCH" to FRANCE, "UNITED KINGDOM" to UK, "ENGLAND" to UK, "UK" to UK,
        "BELGIUM" to "BE", "BELGIQUE" to "BE", "SWITZERLAND" to "CH", "SUISSE" to "CH", "GERMANY" to "DE",
        "DEUTSCHLAND" to "DE", "NETHERLANDS" to "NL", "HOLLAND" to "NL", "ITALY" to "IT", "ITALIA" to "IT",
        "SPAIN" to "ES", "ESPANA" to "ES", "PORTUGAL" to "PT", "POLAND" to "PL", "CZECH" to "CZ",
        "CZECHIA" to "CZ", "ROMANIA" to "RO", "DENMARK" to "DK", "ARABIC" to "AR", "ARAB" to "AR",
        "ARABIA" to "AR", "ARGENTINA" to "ARG", "BRAZIL" to "BR", "BRASIL" to "BR", "LATINO" to "LAT",
        "LATIN AMERICA" to "LAT", "LATAM" to "LAT", "INDIA" to "IN", "BALKANS" to "EX-YU", "BALKAN" to "EX-YU",
        "EX YU" to "EX-YU", "EXYU" to "EX-YU", "USA" to "US", "CANADA" to "CA", "IRELAND" to "IE",
        "AUSTRIA" to "AT", "TURKEY" to "TR", "AFRICA" to "AF", "AFRIQUE" to "AF", "SWEDEN" to "SE",
        "NORWAY" to "NO", "LUXEMBOURG" to "LU",
    )

    /** Mots ignores pour reconnaitre un nom de categorie ("SPORTS ARABIC | TV" -> "ARABIC"). */
    private val FILLER = setOf("TV", "SPORT", "SPORTS", "CHANNELS", "CHAINES", "HD", "FHD", "UHD", "4K", "VIP", "LIVE")

    private val LABELS = mapOf(
        "FR" to "France", "UK" to "Royaume-Uni", "BE" to "Belgique", "CH" to "Suisse", "LU" to "Luxembourg",
        "CA" to "Canada", "DE" to "Allemagne", "ES" to "Espagne", "IT" to "Italie", "PT" to "Portugal",
        "NL" to "Pays-Bas", "IE" to "Irlande", "US" to "États-Unis", "AT" to "Autriche", "PL" to "Pologne",
        "CZ" to "Tchéquie", "RO" to "Roumanie", "DK" to "Danemark", "SE" to "Suède", "NO" to "Norvège",
        "TR" to "Turquie", "MA" to "Maroc", "DZ" to "Algérie", "TN" to "Tunisie", "AR" to "Chaînes arabes",
        "ARG" to "Argentine", "BR" to "Brésil", "LAT" to "Amérique latine", "IN" to "Inde", "EX-YU" to "Balkans",
        "AF" to "Afrique", "GR" to "Grèce", "AL" to "Albanie", "HU" to "Hongrie", "SK" to "Slovaquie",
        "HR" to "Croatie", "RS" to "Serbie", "BG" to "Bulgarie", "RU" to "Russie", "UA" to "Ukraine",
        "FI" to "Finlande", "MX" to "Mexique", "AU" to "Australie", OTHER to "Autres",
    )

    /** Cle de regroupement : code canonique, sinon nom de categorie nettoye (majuscules), [OTHER] si vide. */
    fun key(country: String): String {
        val s = country.trim()
        if (s.isEmpty()) return OTHER
        leadingCode(s)?.let { return canonical(it) }
        if (isCode(s)) return canonical(s)
        // Categorie sans prefixe : segments separes par '|', mots de remplissage retires ("| TV").
        val words = s.uppercase(Locale.ROOT).split('|', ' ', '\t', '-', '_').filter { it.isNotEmpty() }
        val significant = words.filter { it !in FILLER }
        if (significant.isEmpty()) return if (words.isEmpty()) OTHER else words.joinToString(" ")
        val name = significant.joinToString(" ")
        NAME_TO_CODE[name]?.let { return it }
        significant.firstNotNullOfOrNull { NAME_TO_CODE[it] }?.let { return it }
        return name
    }

    /** Libelle francais d'une cle ; categorie inconnue : nom en casse de titre ("KURDISTAN" -> "Kurdistan"). */
    fun label(key: String): String = LABELS[key] ?: key.lowercase(Locale.FRENCH).split(' ')
        .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.FRENCH) } }

    /** France, puis Royaume-Uni, puis les autres par ordre alphabetique de libelle (francais), "Autres" en dernier. */
    fun comparator(label: (String) -> String = ::label): Comparator<String> {
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
        OTHER -> 3
        else -> 2
    }

    private fun canonical(code: String): String {
        val up = code.uppercase(Locale.ROOT)
        return CODE_ALIASES[up] ?: up
    }

    /** "FR", "ARG", "EX-YU" : 2 a 4 lettres, au plus un tiret. */
    private fun isCode(s: String): Boolean {
        val parts = s.split('-')
        return parts.size <= 2 && parts.all { p -> p.length in 2..4 && p.all { it in 'A'..'Z' || it in 'a'..'z' } }
    }

    /** "|FR| SPORTS" ou "FR| SPORTS" -> "FR" ; null si pas de code en tete ("BALKANS | TV" n'en a pas). */
    private fun leadingCode(s: String): String? {
        val body = s.trimStart('|', ' ')
        val bar = body.indexOf('|')
        if (bar <= 0) return null
        val candidate = body.substring(0, bar).trim()
        if (!isCode(candidate)) return null
        val up = candidate.uppercase(Locale.ROOT)
        // "TV | ..." n'est pas un pays ; un mot de pays connu ("UK") reste un code.
        return candidate.takeIf { up !in FILLER && (up.length <= 3 || up.contains('-') || up in CODE_ALIASES) }
    }
}
