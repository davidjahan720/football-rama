package fr.footballrama.core.epg

import fr.footballrama.core.io.SizeLimitExceededException
import fr.footballrama.core.io.UnsafeXmlException
import fr.footballrama.core.io.XmlSafetyInputStream
import fr.footballrama.core.text.Normalizer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant

/** Programme EPG retenu. [text] = titre + description normalises (comme `Programme.text` du prototype). */
data class Programme(
    val channelId: String,
    val start: Instant,
    val stop: Instant?,
    val title: String,
    val text: String,
)

data class EpgData(
    /** id de chaine EPG (minuscules) -> display-name. */
    val channels: Map<String, String>,
    val programmes: List<Programme>,
    val stats: EpgStats,
)

data class EpgStats(val programmesTotal: Int, val programmesInWindow: Int, val programmesKept: Int, val channels: Int)

class EpgParseException(val reason: Reason) : Exception("epg parse failed: $reason") {
    enum class Reason { MALFORMED, UNSAFE, TOO_LARGE, IO }
}

/**
 * Prefiltre : fenetre temporelle + presence d'au moins un alias/token d'equipe du calendrier
 * (suite de mots entiere) dans le titre/description normalises. Limite la memoire au strict utile.
 */
class ProgrammeFilter(val from: Instant, val to: Instant, terms: Collection<String>) {
    private val terms: Set<String> = terms.filter { it.isNotEmpty() }.toHashSet()
    private val maxWords = this.terms.maxOfOrNull { t -> t.count { it == ' ' } + 1 } ?: 0

    fun inWindow(start: Instant) = !start.isBefore(from) && !start.isAfter(to)

    /** Vrai si un des termes est present comme n-gramme de mots de [normalized]. */
    fun matchesTerms(normalized: String): Boolean {
        if (terms.isEmpty() || normalized.isEmpty()) return false
        val words = normalized.split(' ')
        val sb = StringBuilder()
        for (i in words.indices) {
            sb.setLength(0)
            for (n in 0 until minOf(maxWords, words.size - i)) {
                if (n > 0) sb.append(' ')
                sb.append(words[i + n])
                if (sb.toString() in terms) return true
            }
        }
        return false
    }
}

/**
 * Parseur XMLTV en flux (XmlPull : kxml2 sur JVM, `android.util.Xml.newPullParser()` sur Android).
 * Ne charge jamais le document en memoire ; ne garde que les programmes utiles.
 * Securite : [XmlSafetyInputStream] refuse les declarations d'entites (XXE / billion laughs) et le DOCTYPE
 * n'est jamais traite (FEATURE_PROCESS_DOCDECL = false). Les DTD externes ne sont jamais telechargees.
 */
class XmltvParser(private val parserFactory: () -> XmlPullParser) {

    /**
     * @param filter fenetre et termes du prefiltre. La borne basse est elargie de [COVER_MARGIN] pour garder
     *   les programmes qui commencent avant mais couvrent un coup d'envoi (regle "covers" du prototype).
     */
    fun parse(input: InputStream, filter: ProgrammeFilter): EpgData {
        val parser = parserFactory()
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        runCatching { parser.setFeature(XmlPullParser.FEATURE_VALIDATION, false) }

        val channels = HashMap<String, String>()
        val programmes = ArrayList<Programme>()
        var total = 0
        var inWindow = 0
        val lowerBound = filter.from.minus(COVER_MARGIN)
        var rootSeen = false
        var rootClosed = false
        // Budget memoire global (audit securite) : un EPG hostile ne peut pas faire retenir des Go de texte
        // (200 000 programmes x 9 000 caracteres). EPG reel de reference : ~4 300 programmes gardes.
        var retainedChars = 0L
        fun retain(chars: Int) {
            retainedChars += chars
            if (retainedChars > MAX_RETAINED_CHARS) throw SizeLimitExceededException(MAX_RETAINED_CHARS)
        }
        try {
            parser.setInput(XmlSafetyInputStream(input), null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && !rootSeen) {
                    // Racine XMLTV obligatoire : toute autre reponse (HTML d'erreur, JSON...) est rejetee.
                    if (parser.name != "tv") throw XmlPullParserException("root")
                    rootSeen = true
                } else if (event == XmlPullParser.END_TAG && parser.depth == 1) {
                    rootClosed = true
                } else if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> readChannel(parser)?.let { (id, name) ->
                            if (channels.size < MAX_CHANNELS && channels.putIfAbsent(id, name) == null) retain(id.length + name.length)
                        }
                        "programme" -> {
                            total++
                            val start = XmltvTime.parse(parser.getAttributeValue(null, "start"))
                            if (start == null || start.isBefore(lowerBound) || start.isAfter(filter.to)) {
                                skipSubtree(parser)
                            } else {
                                inWindow++
                                val p = readProgramme(parser, start)
                                if (p != null && programmes.size < MAX_PROGRAMMES && filter.matchesTerms(p.text)) {
                                    retain(p.channelId.length + p.title.length + p.text.length)
                                    programmes.add(p)
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
            // Document tronque (telechargement interrompu) : on ne peut pas garantir un resultat complet.
            if (!rootSeen || !rootClosed) throw XmlPullParserException("truncated")
        } catch (e: XmlPullParserException) {
            // setInput() de kxml enveloppe l'exception du flux (ex. encodage refuse) dans "detail".
            val reason = classify(e)
            throw EpgParseException(if (reason == EpgParseException.Reason.IO) EpgParseException.Reason.MALFORMED else reason)
        } catch (e: UnsafeXmlException) {
            throw EpgParseException(EpgParseException.Reason.UNSAFE)
        } catch (e: SizeLimitExceededException) {
            throw EpgParseException(EpgParseException.Reason.TOO_LARGE)
        } catch (e: IOException) {
            // kxml peut envelopper l'exception du flux : on regarde la cause.
            throw EpgParseException(classify(e))
        } catch (e: RuntimeException) {
            // Parseur sur entree hostile (ex. ArrayIndexOutOfBounds) : jamais de crash de l'app.
            throw EpgParseException(EpgParseException.Reason.MALFORMED)
        }
        return EpgData(channels, programmes, EpgStats(total, inWindow, programmes.size, channels.size))
    }

    private fun classify(e: Throwable): EpgParseException.Reason {
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth++ < 10) {
            if (c is UnsafeXmlException) return EpgParseException.Reason.UNSAFE
            if (c is SizeLimitExceededException) return EpgParseException.Reason.TOO_LARGE
            c = (c as? XmlPullParserException)?.detail?.takeIf { it !== c } ?: c.cause
        }
        return EpgParseException.Reason.IO
    }

    private fun readChannel(p: XmlPullParser): Pair<String, String>? {
        val id = p.getAttributeValue(null, "id")?.lowercase()?.take(MAX_ID)
        var name: String? = null
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) throw XmlPullParserException("eof")
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.START_TAG && p.name == "display-name" && name == null) {
                name = readText(p, MAX_TITLE)
            }
        }
        return if (id.isNullOrEmpty()) null else id to (name ?: "").trim()
    }

    private fun readProgramme(p: XmlPullParser, start: Instant): Programme? {
        val channel = p.getAttributeValue(null, "channel")?.lowercase()?.take(MAX_ID)
        val stop = XmltvTime.parse(p.getAttributeValue(null, "stop"))
        var title: String? = null
        var desc: String? = null
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) throw XmlPullParserException("eof")
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.START_TAG && p.depth == depth + 1) {
                when (p.name) {
                    "title" -> if (title == null) title = readText(p, MAX_TITLE) else skipSubtree(p)
                    "desc" -> if (desc == null) desc = readText(p, MAX_DESC) else skipSubtree(p)
                    else -> skipSubtree(p)
                }
            }
        }
        if (channel.isNullOrEmpty()) return null
        val t = (title ?: "").trim()
        val d = (desc ?: "").trim()
        return Programme(channel, start, stop, t, Normalizer.normalize("$t $d"))
    }

    /** Texte direct de l'element courant (positionne sur START_TAG), tronque a [max] caracteres. */
    private fun readText(p: XmlPullParser, max: Int): String {
        val sb = StringBuilder()
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) throw XmlPullParserException("eof")
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.TEXT && p.depth == depth && sb.length < max) {
                val txt = p.text ?: continue
                sb.append(txt, 0, minOf(txt.length, max - sb.length))
            }
        }
        return sb.toString()
    }

    private fun skipSubtree(p: XmlPullParser) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) throw XmlPullParserException("eof")
            if (ev == XmlPullParser.END_TAG && p.depth == depth) return
        }
    }

    companion object {
        /** Un programme qui couvre le coup d'envoi commence au plus 6 h avant (comme le prototype). */
        val COVER_MARGIN: Duration = Duration.ofHours(6)
        const val MAX_PROGRAMMES = 200_000
        const val MAX_CHANNELS = 200_000
        /** ~32 Mo de texte retenu au maximum (caracteres UTF-16). */
        const val MAX_RETAINED_CHARS = 16L * 1024 * 1024
        private const val MAX_TITLE = 1_000
        private const val MAX_DESC = 8_000
        private const val MAX_ID = 300
    }
}
