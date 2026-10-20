package fr.footballrama.core.text

/**
 * Normalisation du contrat (docs/CONTRAT_FIXTURES.md, section "Normalisation"),
 * identique a `norm()` de scripts/poc_epg.py et au publisher.
 *
 * 1. Translitteration des lettres sans decomposition Unicode.
 * 2. NFKD puis suppression des caracteres non ASCII.
 * 3. Minuscules.
 * 4. Toute suite hors [a-z0-9] -> un espace, puis trim.
 *
 * Le resultat ne contient que [a-z0-9] et des espaces simples : la recherche "mot entier"
 * se ramene donc a une recherche de " alias " dans " texte " (voir [containsPhrase]).
 */
object Normalizer {

    private val SPECIAL = mapOf(
        'ø' to "o", 'Ø' to "O", 'æ' to "ae", 'Æ' to "AE", 'ß' to "ss", 'đ' to "d", 'Đ' to "D",
        'ł' to "l", 'Ł' to "L", 'ı' to "i", 'œ' to "oe", 'Œ' to "OE",
    )

    fun normalize(input: String): String {
        if (input.isEmpty()) return ""
        val translit = StringBuilder(input.length + 8)
        for (c in input) {
            val r = SPECIAL[c]
            if (r != null) translit.append(r) else translit.append(c)
        }
        val decomposed = java.text.Normalizer.normalize(translit, java.text.Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        var pendingSpace = false
        for (c in decomposed) {
            if (c.code >= 128) continue // suppression des non ASCII (equivalent encode("ascii", "ignore"))
            val lc = if (c in 'A'..'Z') c + 32 else c
            if (lc in 'a'..'z' || lc in '0'..'9') {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(lc)
            } else {
                pendingSpace = true
            }
        }
        return out.toString()
    }

    /**
     * Vrai si [phrase] (normalisee) apparait dans [text] (normalise) comme suite de mots entiere,
     * equivalent de `re.search(r"(?<![a-z0-9])alias(?![a-z0-9])", text)` sur du texte normalise.
     */
    fun containsPhrase(text: String, phrase: String): Boolean {
        if (phrase.isEmpty() || text.length < phrase.length) return false
        var from = 0
        while (true) {
            val i = text.indexOf(phrase, from)
            if (i < 0) return false
            val before = i == 0 || !text[i - 1].isAsciiAlnum()
            val end = i + phrase.length
            val after = end == text.length || !text[end].isAsciiAlnum()
            if (before && after) return true
            from = i + 1
        }
    }

    private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in '0'..'9'
}
