package fr.footballrama.core.io

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Levee quand un flux depasse la taille autorisee (protection DoS memoire/disque, gzip bomb). */
class SizeLimitExceededException(val limit: Long) : IOException("size limit exceeded ($limit bytes)")

/** Compte les octets lus et echoue au-dela de [limit]. [onRead] recoit le total lu (progression). */
class LimitedInputStream(
    input: InputStream,
    private val limit: Long,
    private val onRead: ((Long) -> Unit)? = null,
) : FilterInputStream(input) {
    var count: Long = 0
        private set

    private fun add(n: Int) {
        if (n <= 0) return
        count += n
        if (count > limit) throw SizeLimitExceededException(limit)
        onRead?.invoke(count)
    }

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) add(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        add(n)
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(n)
        add(s.toInt())
        return s
    }

    override fun markSupported() = false
}

/** Le document XML est refuse (declaration d'entite, texte demesure). */
class UnsafeXmlException(message: String) : IOException(message)

/**
 * Garde-fou place AVANT le parseur XML, independant de l'implementation (kxml2 sur JVM, KXmlParser du
 * framework sur Android, qui lui interprete le sous-ensemble interne du DOCTYPE) :
 * - toute declaration `<!ENTITY` est refusee : bloque XXE et "billion laughs" (un EPG XMLTV n'en a jamais besoin) ;
 * - une suite de plus de [maxRunWithoutMarkup] octets sans `<` ni `>` est refusee : un serveur malveillant ne
 *   peut pas faire accumuler un texte geant en memoire par le parseur.
 * Le DOCTYPE externe classique (`<!DOCTYPE tv SYSTEM "xmltv.dtd">`) reste accepte : il n'est jamais resolu.
 *
 * Le filtre travaille sur les OCTETS : il n'est exact que si le parseur decode le document avec un encodage
 * compatible ASCII (UTF-8, ISO-8859-x, windows-125x). Or le KXmlParser d'Android detecte UTF-16/UTF-32 (BOM ou
 * "<?" sur 2/4 octets) et applique l'encodage declare dans `<?xml ... encoding="..."?>` : un `<!ENTITY` encode
 * en UTF-16 ou en EBCDIC passerait sous le filtre. D'ou deux regles supplementaires (audit securite) :
 * - tout octet NUL est refuse (illegal en XML 1.0, present dans tout texte ASCII encode en UTF-16/UTF-32) ;
 * - l'encodage declare dans la declaration XML doit appartenir a [ALLOWED_ENCODINGS].
 */
class XmlSafetyInputStream(
    input: InputStream,
    private val maxRunWithoutMarkup: Int = 1 shl 20,
) : FilterInputStream(input) {
    private var matched = 0
    private var run = 0

    /** Debut du document (jusqu'au premier '>'), pour verifier l'encodage declare. null = verification faite. */
    private var prolog: StringBuilder? = StringBuilder()

    private fun checkProlog(b: Int) {
        val sb = prolog ?: return
        sb.append(b.toChar())
        if (b != '>'.code && sb.length < MAX_PROLOG) return
        prolog = null
        val text = sb.toString().removePrefix(UTF8_BOM)
        if (!text.startsWith("<?xm")) return // pas de declaration : UTF-8 par defaut
        // Lecture aussi permissive que celle du KXmlParser Android : premier guillemet apres "encoding".
        val at = text.indexOf("encoding")
        if (at < 0) return
        val open = text.indexOfAny(charArrayOf('"', '\''), at)
        val close = if (open < 0) -1 else text.indexOf(text[open], open + 1)
        if (close < 0) throw UnsafeXmlException("xml encoding unreadable")
        val encoding = text.substring(open + 1, close).trim().lowercase()
        if (encoding !in ALLOWED_ENCODINGS) throw UnsafeXmlException("xml encoding refused")
    }

    private fun inspect(b: Int) {
        if (b == 0) throw UnsafeXmlException("nul byte refused (utf-16/utf-32 not supported)")
        checkProlog(b)
        if (b == '<'.code || b == '>'.code) run = 0 else if (++run > maxRunWithoutMarkup) {
            throw UnsafeXmlException("xml text run too long")
        }
        matched = when {
            b == PATTERN[matched].toInt() -> matched + 1
            b == PATTERN[0].toInt() -> 1
            else -> 0
        }
        if (matched == PATTERN.size) throw UnsafeXmlException("xml entity declaration refused")
    }

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) inspect(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        for (i in off until off + maxOf(n, 0)) inspect(b[i].toInt() and 0xFF)
        return n
    }

    override fun skip(n: Long): Long {
        // Pas de saut sans inspection.
        var skipped = 0L
        val buf = ByteArray(8192)
        while (skipped < n) {
            val r = read(buf, 0, minOf(buf.size.toLong(), n - skipped).toInt())
            if (r < 0) break
            skipped += r
        }
        return skipped
    }

    override fun markSupported() = false

    private companion object {
        // "<!ENTITY" : aucun prefixe propre n'est aussi suffixe, le repli simple ci-dessus est donc exact.
        val PATTERN = "<!ENTITY".toByteArray(Charsets.US_ASCII)

        const val MAX_PROLOG = 1024
        /** BOM UTF-8 vu octet par octet (chaque octet converti en Char). */
        val UTF8_BOM = String(charArrayOf(0xEF.toChar(), 0xBB.toChar(), 0xBF.toChar()))

        /** Encodages compatibles ASCII : un octet < 0x80 y represente toujours le meme caractere ASCII. */
        val ALLOWED_ENCODINGS: Set<String> = buildSet {
            addAll(listOf("utf-8", "utf8", "us-ascii", "ascii", "latin1", "latin-1"))
            for (i in 1..16) add("iso-8859-$i")
            for (i in 1..16) add("iso8859-$i")
            for (i in 1250..1258) {
                add("windows-$i")
                add("cp$i")
            }
        }
    }
}
