package fr.footballrama.app.pairing

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/*
 * Mini-serveur HTTP/1.1 du jumelage telephone -> boitier : parsing STRICT et BORNE (logique pure, sans Android,
 * testee en JVM). Une seule requete par connexion, `Connection: close`, aucune fonctionnalite superflue
 * (pas de keep-alive, pas de chunked, pas de compression, pas de multipart, pas de fichiers statiques).
 */

/** Limites appliquees a chaque requete. */
data class HttpLimits(
    /** Ligne de requete + en-tetes (CRLF compris). */
    val maxHeaderBytes: Int = 8 * 1024,
    val maxBodyBytes: Int = 8 * 1024,
    val maxHeaders: Int = 40,
    val maxTargetLength: Int = 256,
)

/** Requete analysee. Noms d'en-tetes en minuscules. Aucune valeur n'est jamais journalisee. */
class HttpRequest(
    val method: String,
    val target: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
) {
    fun headerValues(name: String): List<String> = headers.filter { it.first == name }.map { it.second }

    /** Valeur unique, null si absent ; [HttpException] 400 si l'en-tete est repete. */
    fun singleHeader(name: String): String? {
        val values = headerValues(name)
        if (values.size > 1) throw HttpException(400)
        return values.firstOrNull()
    }

    override fun toString() = "HttpRequest($method, <target>, ${headers.size} headers, ${body.size} bytes)"
}

/** Erreur protocolaire : la reponse est le code [status] sans detail. Pas de pile (chemin non exceptionnel). */
class HttpException(val status: Int) : Exception(null, null, false, false)

/** Appele avant chaque lecture : ajuste le delai restant (date limite globale) ou leve SocketTimeoutException. */
fun interface ReadDeadline {
    fun beforeRead()
}

object HttpRequestReader {

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()

    /**
     * Lit UNE requete. Leve [HttpException] (400, 411, 413, 414, 431, 505...) sur toute entree non conforme,
     * [java.net.SocketTimeoutException] si la date limite est depassee, [IOException] si la connexion tombe.
     */
    fun read(input: InputStream, limits: HttpLimits = HttpLimits(), deadline: ReadDeadline = ReadDeadline {}): HttpRequest {
        val buf = ByteArray(limits.maxHeaderBytes)
        var len = 0
        var headerEnd = -1
        while (headerEnd < 0) {
            if (len == buf.size) throw HttpException(431)
            deadline.beforeRead()
            val n = input.read(buf, len, buf.size - len)
            if (n < 0) throw HttpException(400)
            val searchFrom = maxOf(0, len - 3)
            len += n
            headerEnd = indexOfHeaderEnd(buf, searchFrom, len)
        }

        val lines = splitLines(buf, headerEnd - 4)
        val requestLine = lines.first()
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw HttpException(400)
        val (method, target, version) = parts
        if (method.isEmpty() || method.length > 10 || !method.all { it in 'A'..'Z' }) throw HttpException(400)
        if (!version.startsWith("HTTP/")) throw HttpException(400)
        if (version != "HTTP/1.1") throw HttpException(505)
        if (target.length > limits.maxTargetLength) throw HttpException(414)
        if (!target.startsWith("/") || target.any { it.code !in 0x21..0x7E }) throw HttpException(400)

        val headerLines = lines.drop(1)
        if (headerLines.size > limits.maxHeaders) throw HttpException(431)
        val headers = headerLines.map(::parseHeader)

        val request = HttpRequest(method, target, headers, ByteArray(0))
        if (request.headerValues("transfer-encoding").isNotEmpty()) throw HttpException(400)
        val contentLength = request.singleHeader("content-length")?.let { v ->
            if (v.isEmpty() || v.length > 9 || !v.all { it in '0'..'9' }) throw HttpException(400)
            v.toInt()
        }
        if (contentLength != null && contentLength > limits.maxBodyBytes) throw HttpException(413)
        if (method == "POST" && contentLength == null) throw HttpException(411)
        val bodyLength = contentLength ?: 0

        // Octets deja lus apres les en-tetes : debut du corps. Plus que Content-Length = 2e requete -> refus.
        val already = len - headerEnd
        if (already > bodyLength) throw HttpException(400)
        val body = ByteArray(bodyLength)
        System.arraycopy(buf, headerEnd, body, 0, already)
        var filled = already
        while (filled < bodyLength) {
            deadline.beforeRead()
            val n = input.read(body, filled, bodyLength - filled)
            if (n < 0) throw HttpException(400)
            filled += n
        }
        return HttpRequest(method, target, headers, body)
    }

    /** Index juste apres le premier CRLFCRLF dans [from, to), -1 sinon. */
    private fun indexOfHeaderEnd(b: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + 3 < to) {
            if (b[i] == CR && b[i + 1] == LF && b[i + 2] == CR && b[i + 3] == LF) return i + 4
            i++
        }
        return -1
    }

    /** Decoupe [0, end) en lignes CRLF ; ASCII visible + espace/tabulation uniquement, LF ou CR isole refuses. */
    private fun splitLines(b: ByteArray, end: Int): List<String> {
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < end) {
            val c = b[i].toInt() and 0xFF
            when {
                c == 0x0D -> {
                    if (i + 1 >= end || b[i + 1] != LF) throw HttpException(400)
                    lines.add(sb.toString())
                    sb.setLength(0)
                    i += 2
                    continue
                }
                c == 0x09 || c in 0x20..0x7E -> sb.append(c.toChar())
                else -> throw HttpException(400) // LF isole, octet de controle, non-ASCII
            }
            i++
        }
        lines.add(sb.toString())
        if (lines.first().isEmpty()) throw HttpException(400)
        return lines
    }

    private fun parseHeader(line: String): Pair<String, String> {
        if (line.isEmpty() || line[0] == ' ' || line[0] == '\t') throw HttpException(400) // obs-fold refuse
        val colon = line.indexOf(':')
        if (colon <= 0) throw HttpException(400)
        val name = line.substring(0, colon)
        if (!name.all(::isTchar)) throw HttpException(400)
        val value = line.substring(colon + 1).trim { it == ' ' || it == '\t' }
        return name.lowercase(Locale.ROOT) to value
    }

    private fun isTchar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"
}

/** Reponse : en-tetes de securite systematiques, pas de CORS, pas d'en-tete Server. */
class HttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val extraHeaders: List<Pair<String, String>> = emptyList(),
) {
    fun headers(): List<Pair<String, String>> = buildList {
        contentType?.let { add("Content-Type" to it) }
        add("Content-Length" to body.size.toString())
        add("Connection" to "close")
        add("Cache-Control" to "no-store")
        add("Pragma" to "no-cache")
        add("X-Content-Type-Options" to "nosniff")
        add("Content-Security-Policy" to CSP)
        add("Referrer-Policy" to "no-referrer")
        add("X-Frame-Options" to "DENY")
        add("Cross-Origin-Resource-Policy" to "same-origin")
        addAll(extraHeaders)
    }

    fun writeTo(out: OutputStream) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        headers().forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    companion object {
        /** Aucun script, aucune ressource externe ; seul le style inline et l'envoi du formulaire vers soi. */
        const val CSP = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; " +
            "frame-ancestors 'none'; base-uri 'none'"

        fun empty(status: Int, extra: List<Pair<String, String>> = emptyList()) = HttpResponse(status, extraHeaders = extra)

        fun html(status: Int, html: String) = HttpResponse(status, html.toByteArray(Charsets.UTF_8), "text/html; charset=utf-8")

        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            410 -> "Gone"
            411 -> "Length Required"
            413 -> "Content Too Large"
            414 -> "URI Too Long"
            415 -> "Unsupported Media Type"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            505 -> "HTTP Version Not Supported"
            else -> "Error"
        }
    }
}

/** Decodage strict de application/x-www-form-urlencoded (UTF-8 valide, %XX hexadecimal, champs connus, sans doublon). */
object FormDecoder {

    fun decode(body: ByteArray, allowed: Set<String>, maxFields: Int = allowed.size): Map<String, String> {
        if (body.isEmpty()) return emptyMap()
        for (b in body) {
            if ((b.toInt() and 0xFF) !in 0x21..0x7E) throw HttpException(400) // le corps encode est ASCII visible
        }
        val text = String(body, Charsets.US_ASCII)
        val pairs = text.split('&')
        if (pairs.size > maxFields) throw HttpException(400)
        val out = LinkedHashMap<String, String>()
        for (p in pairs) {
            val eq = p.indexOf('=')
            if (eq <= 0) throw HttpException(400)
            val key = percentDecode(p.substring(0, eq))
            if (key !in allowed || key in out) throw HttpException(400)
            out[key] = percentDecode(p.substring(eq + 1))
        }
        return out
    }

    fun percentDecode(s: String): String {
        val bytes = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '+' -> bytes.write(' '.code)
                '%' -> {
                    if (i + 2 >= s.length) throw HttpException(400)
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi < 0 || lo < 0) throw HttpException(400)
                    bytes.write(hi * 16 + lo)
                    i += 2
                }
                else -> bytes.write(c.code)
            }
            i++
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            throw HttpException(400)
        }
        // Aucun caractere de controle dans une valeur (retours a la ligne, NUL...).
        if (decoded.any { it.isISOControl() }) throw HttpException(400)
        return decoded
    }
}

/** Echappement HTML de toute valeur reflechie (texte ET attributs entre guillemets). */
fun escapeHtml(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (c in s) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

internal fun closeQuietly(c: java.io.Closeable?) {
    try {
        c?.close()
    } catch (_: IOException) {
    }
}
