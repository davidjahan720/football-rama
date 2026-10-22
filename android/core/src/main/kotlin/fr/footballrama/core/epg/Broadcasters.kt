package fr.footballrama.core.epg

import fr.footballrama.core.matching.ChannelNames
import fr.footballrama.core.text.Normalizer
import fr.footballrama.core.xtream.LiveCategory
import fr.footballrama.core.xtream.LiveStream

/**
 * Diffuseurs de football interroges par le repli "guide chaine par chaine".
 *
 * FICHIER DE DONNEES : modifier [TABLE] pour ajouter / retirer une chaine. Une ligne par chaine :
 *   `PAYS | TYPE | NOM`
 * - PAYS : FR, UK, BE, CH (prioritaires, dans cet ordre) ; DE, IT, ES, NL, PT (championnats couverts, ensuite) ;
 *   `*` (tous les autres pays, en dernier).
 * - TYPE : `=` chaine principale, nom exact ; `~` variantes (LIVE 2..9, EX1..9, multiplex...), debut de nom.
 * - NOM : nom du flux SANS prefixe pays ("|FR|", "FR:") ni qualite (HD, FHD, 4K...) ; casse, accents, espaces et
 *   ponctuation ignores (seul `+` compte : "CANAL+" n'est pas "CANAL").
 * Ordre de priorite : principales FR, UK, BE, CH ; principales DE, IT, ES, NL, PT ; variantes FR, UK, BE, CH ;
 * flux sans pays reconnu ; variantes DE, IT, ES, NL, PT ; enfin les lignes `*`. Le budget de requetes s'applique
 * ensuite (voir ChannelEpgFallback.Config.maxRequests).
 * A ordre egal, l'ordre des lignes compte.
 */
object Broadcasters {

    const val TABLE = """
# --- France
FR | = | CANAL+ FOOT
FR | = | CANAL+ SPORT
FR | = | CANAL+ SPORT 360
FR | = | CANAL+
FR | = | CANAL+ LIVE 1
FR | = | BEIN SPORTS 1
FR | = | BEIN SPORTS 2
FR | = | BEIN SPORTS 3
FR | = | LIGUE 1+
FR | = | DAZN 1
FR | = | DAZN
FR | = | RMC SPORT 1
FR | = | RMC SPORT 2
FR | = | EUROSPORT 1
FR | = | EUROSPORT 2
FR | = | L'EQUIPE
FR | ~ | CANAL+ LIVE
FR | ~ | BEIN SPORTS MAX
FR | ~ | LIGUE 1+
FR | ~ | DAZN
FR | ~ | RMC SPORT
FR | ~ | EUROSPORT
# --- Royaume-Uni
UK | = | SKY SPORTS MAIN EVENT
UK | = | SKY SPORTS PREMIER LEAGUE
UK | = | SKY SPORTS FOOTBALL
UK | = | TNT SPORTS 1
UK | = | TNT SPORTS 2
UK | = | TNT SPORTS 3
UK | = | TNT SPORTS 4
UK | = | AMAZON PRIME
UK | = | PRIME VIDEO
UK | ~ | AMAZON PRIME
UK | ~ | PRIME VIDEO
UK | ~ | SKY SPORTS
UK | ~ | TNT SPORTS
# --- Belgique
BE | = | PLAY SPORTS
BE | = | PLAY SPORTS 1
BE | = | PLAY SPORTS 2
BE | = | PICKX+ SPORTS
BE | ~ | PLAY SPORTS
BE | ~ | PICKX
# --- Suisse
CH | = | BLUE SPORT
CH | = | BLUE SPORT 1
CH | = | BLUE SPORT 2
CH | ~ | BLUE SPORT
# --- Allemagne (Bundesliga)
DE | = | SKY SPORT BUNDESLIGA
DE | = | SKY SPORT BUNDESLIGA 1
DE | = | DAZN 1
DE | = | DAZN 2
DE | ~ | SKY SPORT BUNDESLIGA
DE | ~ | DAZN
# --- Italie (Serie A)
IT | = | SKY SPORT CALCIO
IT | = | SKY SPORT UNO
IT | = | DAZN
IT | ~ | SKY SPORT CALCIO
IT | ~ | DAZN
# --- Espagne (Liga)
ES | = | MOVISTAR LALIGA
ES | = | DAZN LALIGA
ES | ~ | MOVISTAR LALIGA
ES | ~ | DAZN LALIGA
ES | ~ | LALIGA TV
# --- Pays-Bas (Eredivisie)
NL | = | ESPN
NL | = | ESPN 1
NL | = | ESPN 2
NL | ~ | ESPN
NL | ~ | ZIGGO SPORT
# --- Portugal (Liga Portugal)
PT | = | SPORT TV 1
PT | = | SPORT TV 2
PT | ~ | SPORT TV
# --- Autres pays (seulement s'il reste du budget)
* | ~ | BEIN SPORTS
* | ~ | SKY SPORT
* | ~ | DAZN
* | ~ | ESPN
* | ~ | MOVISTAR LALIGA
* | ~ | MOVISTAR LIGA DE CAMPEONES
* | ~ | SPORT TV
* | ~ | ELEVEN
* | ~ | VIAPLAY
* | ~ | ZIGGO SPORT
* | ~ | CANAL+ SPORT
* | ~ | SUPERSPORT
* | ~ | ARENA SPORT
* | ~ | POLSAT SPORT
* | ~ | DIGI SPORT
* | ~ | NOVA SPORT
"""

    /** Pays prioritaires, dans l'ordre. */
    val PRIORITY_COUNTRIES = listOf("FR", "UK", "BE", "CH")

    /** Pays des autres championnats couverts, interroges apres les pays prioritaires. */
    val SECONDARY_COUNTRIES = listOf("DE", "IT", "ES", "NL", "PT")
    private val ALL_COUNTRIES = PRIORITY_COUNTRIES + SECONDARY_COUNTRIES

    data class Entry(val country: String, val exact: Boolean, val name: String, val compact: String, val line: Int)

    val entries: List<Entry> = parse(TABLE)

    fun parse(table: String): List<Entry> = table.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapIndexedNotNull { i, line ->
            val parts = line.split('|').map { it.trim() }
            if (parts.size != 3 || parts[1] !in setOf("=", "~")) return@mapIndexedNotNull null
            val compact = compact(parts[2])
            if (compact.isEmpty()) null else Entry(parts[0].uppercase(), parts[1] == "=", parts[2], compact, i)
        }
        .toList()

    // ------------------------------------------------------------------ noms et pays

    private val COUNTRY_ALIASES: Map<String, String> = buildMap {
        listOf("FR", "FRA", "FRANCE").forEach { put(it, "FR") }
        listOf("UK", "GB", "GBR", "ENGLAND", "ENG").forEach { put(it, "UK") }
        listOf("BE", "BEL", "BELGIUM", "BELGIQUE", "BELGIE").forEach { put(it, "BE") }
        listOf("CH", "SUI", "SWISS", "SUISSE", "SWITZERLAND", "SCHWEIZ").forEach { put(it, "CH") }
        listOf("DE", "GER", "DEU", "GERMANY", "DEUTSCHLAND", "ALLEMAGNE").forEach { put(it, "DE") }
        listOf("IT", "ITA", "ITALY", "ITALIA", "ITALIE").forEach { put(it, "IT") }
        listOf("ES", "ESP", "SPAIN", "ESPANA", "ESPAGNE").forEach { put(it, "ES") }
        listOf("NL", "NED", "NLD", "NETHERLANDS", "NEDERLAND", "HOLLAND").forEach { put(it, "NL") }
        listOf("PT", "POR", "PRT", "PORTUGAL").forEach { put(it, "PT") }
    }
    private val QUALITY_SUFFIXES = listOf("FHD", "UHD", "HEVC", "H265", "HD", "SD", "4K", "8K", "RAW", "LQ", "HQ", "50FPS", "60FPS")

    /** Majuscules, accents retires, seuls A-Z, 0-9 et '+' conserves ("Canal+ Sport 360" -> "CANAL+SPORT360"). */
    fun compact(s: String): String {
        val decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            val u = if (c in 'a'..'z') c - 32 else c
            if (u in 'A'..'Z' || u in '0'..'9' || u == '+') sb.append(u)
        }
        return sb.toString()
    }

    /** Nom de chaine compact sans pays ni qualite : "|FR| CANAL+ FOOT FHD" / "FR: Canal+ Foot ᴴᴰ" -> "CANAL+FOOT". */
    fun channelKey(streamName: String): String {
        var name = ChannelNames.withoutCountry(ChannelNames.group(streamName))
        // Prefixe "FR:" / "FR -" / "UK |" (sans regex : moteur ICU d'Android).
        var i = 0
        while (i < name.length && name[i].isLetter() && i < 4) i++
        var j = i
        while (j < name.length && name[j] == ' ') j++
        if (i in 2..3 && j < name.length && name[j] in ":|-") name = name.substring(j + 1)
        var key = compact(name)
        var changed = true
        while (changed) {
            changed = false
            for (q in QUALITY_SUFFIXES) {
                if (key.length > q.length && key.endsWith(q)) {
                    key = key.dropLast(q.length)
                    changed = true
                }
            }
        }
        return key
    }

    /** Code pays (FR, UK, BE, CH, DE, IT, ES, NL, PT) d'apres le prefixe du flux ou la categorie ; null si autre / inconnu. */
    fun countryOf(streamName: String, categoryName: String?): String? {
        ChannelNames.countryPrefix(streamName)?.let { return COUNTRY_ALIASES[it] }
        firstWord(streamName)?.let { w -> COUNTRY_ALIASES[w]?.let { return it } }
        val cat = categoryName ?: return null
        ChannelNames.countryPrefix(cat)?.let { return COUNTRY_ALIASES[it] }
        val words = Normalizer.normalize(cat).uppercase().split(' ').filter { it.isNotEmpty() }
        if (words.size >= 2 && words[0] == "UNITED" && words[1] == "KINGDOM") return "UK"
        return words.firstOrNull()?.let { COUNTRY_ALIASES[it] }
    }

    /** Vrai si le flux porte un prefixe d'un pays non liste ("|PL| ...", "AR: ..."). */
    private fun hasOtherCountry(streamName: String): Boolean {
        val p = ChannelNames.countryPrefix(streamName) ?: firstWord(streamName)
        return p != null && COUNTRY_ALIASES[p] == null
    }

    private fun firstWord(s: String): String? {
        val t = s.trimStart()
        var i = 0
        while (i < t.length && t[i].isLetter()) i++
        if (i !in 2..3) return null
        val rest = t.substring(i).trimStart()
        return if (rest.isNotEmpty() && rest[0] in ":|-") t.substring(0, i).uppercase() else null
    }

    // ------------------------------------------------------------------ selection

    /**
     * Rang de priorite d'un flux (plus petit = plus prioritaire), null si ce n'est pas un diffuseur liste.
     * Un flux sans pays reconnu peut correspondre a une ligne pays, mais passe apres les pays prioritaires.
     */
    fun rank(stream: LiveStream, categoryName: String?, table: List<Entry> = entries): Int? {
        val key = channelKey(stream.name)
        if (key.isEmpty()) return null
        val country = countryOf(stream.name, categoryName)
        val foreign = country == null && hasOtherCountry(stream.name)
        var best: Int? = null
        for (e in table) {
            val matches = if (e.exact) key == e.compact else key.startsWith(e.compact)
            if (!matches) continue
            val variant = if (e.exact) 0 else 1
            val priorityLine = e.country in PRIORITY_COUNTRIES
            // Niveaux : 0 principales FR/UK/BE/CH, 1 principales DE/IT/ES/NL/PT, 2 variantes FR/UK/BE/CH,
            // 3-4 flux sans pays reconnu, 5 variantes DE/IT/ES/NL/PT, 6-7 lignes `*`.
            val tier = when {
                e.country == "*" -> 6 + variant
                foreign -> continue // "|PL| CANAL+ SPORT" ne doit pas prendre la ligne FR
                country == null -> 3 + variant // pays inconnu
                e.country != country -> continue // "|DE| SKY SPORT..." ne prend pas une ligne UK
                priorityLine -> if (e.exact) 0 else 2
                else -> if (e.exact) 1 else 5
            }
            val countryIdx = ALL_COUNTRIES.indexOf(e.country).let { if (it < 0) ALL_COUNTRIES.size else it }
            val r = tier * 1_000_000 + countryIdx * 10_000 + e.line
            if (best == null || r < best) best = r
        }
        return best
    }

    /**
     * Flux a interroger, par priorite decroissante, un seul par epg_channel_id (sans epg_channel_id, aucun
     * programme ne pourrait etre relie au flux). La liste n'est PAS tronquee : le budget s'applique ensuite.
     */
    fun select(streams: List<LiveStream>, categories: List<LiveCategory>, table: List<Entry> = entries): List<LiveStream> {
        val catName = categories.associate { it.id to it.name }
        val ranked = ArrayList<Pair<Int, LiveStream>>()
        for (s in streams) {
            if (s.epgChannelId.isNullOrBlank()) continue
            val r = rank(s, s.categoryId?.let { catName[it] }, table) ?: continue
            ranked.add(r to s)
        }
        ranked.sortBy { it.first } // tri stable
        val seen = HashSet<String>()
        return ranked.mapNotNull { (_, s) -> s.takeIf { seen.add(it.epgChannelId!!.lowercase()) } }
    }
}
