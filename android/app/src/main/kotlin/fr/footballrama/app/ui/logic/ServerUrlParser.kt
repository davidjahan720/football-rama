package fr.footballrama.app.ui.logic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validation de l'adresse du serveur saisie a l'ecran de connexion (logique pure, testee en JVM).
 *
 * Accepte "hote:port", "http(s)://hote:port[/chemin]". Si l'utilisateur colle une URL Xtream complete
 * (player_api.php, get.php, xmltv.php... avec username/password en parametres, ou user:mdp@hote), les
 * identifiants sont extraits et l'adresse est ramenee a sa base. Tout parametre inconnu est refuse plutot
 * que tronque en silence. Les identifiants extraits ne sont jamais journalises.
 */
object ServerUrlParser {

    sealed interface Result {
        data class Valid(
            /** Adresse de base normalisee, sans identifiants, parametres ni fragment. */
            val serverUrl: String,
            val isCleartext: Boolean,
            val extractedUsername: String? = null,
            val extractedPassword: String? = null,
        ) : Result {
            override fun toString() = "Valid(serverUrl=$serverUrl, isCleartext=$isCleartext, credentials=<redacted>)"
        }

        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason {
        EMPTY,
        /** Schema autre que http/https (rtmp://, ftp://...). */
        BAD_SCHEME,
        /** Adresse illisible (espaces, port invalide, caracteres interdits...). */
        MALFORMED,
        NO_HOST,
        /** Un seul des deux parametres username/password present dans l'URL collee. */
        PARTIAL_CREDENTIALS,
        /** Parametres d'URL non reconnus : on refuse plutot que de deviner. */
        UNKNOWN_PARAMETERS,
        /** Lien de flux (/live/..., .ts, .m3u8) colle a la place de l'adresse du serveur. */
        STREAM_URL,
    }

    /** Points d'entree Xtream qu'on retire du chemin s'ils sont colles avec l'adresse. */
    private val ENDPOINTS = setOf("player_api.php", "get.php", "xmltv.php", "panel_api.php")

    /** Parametres connus d'une URL Xtream/M3U ; les autres provoquent un refus. */
    private val KNOWN_PARAMS = setOf("username", "password", "type", "output", "action")

    private val STREAM_PREFIXES = setOf("live", "movie", "series", "timeshift")
    private val STREAM_EXTENSIONS = listOf(".ts", ".m3u8", ".mp4", ".mkv", ".m3u")

    private const val MAX_LENGTH = 2048

    fun parse(input: String): Result {
        val text = input.trim()
        if (text.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (text.length > MAX_LENGTH || text.any { it.isWhitespace() || it.isISOControl() }) {
            return Result.Invalid(Reason.MALFORMED)
        }

        val schemeEnd = text.indexOf("://")
        val raw = if (schemeEnd >= 0) {
            val scheme = text.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") return Result.Invalid(Reason.BAD_SCHEME)
            scheme + text.substring(schemeEnd)
        } else {
            // "mailto:x" ou "rtmp:/x" sans "//" : schema explicite non supporte.
            val colon = text.indexOf(':')
            if (colon > 0 && text.substring(0, colon).lowercase() in setOf("rtmp", "rtsp", "ftp", "file", "udp", "mailto")) {
                return Result.Invalid(Reason.BAD_SCHEME)
            }
            "http://$text"
        }

        val url: HttpUrl = raw.toHttpUrlOrNull() ?: return Result.Invalid(Reason.MALFORMED)
        if (url.host.isEmpty()) return Result.Invalid(Reason.NO_HOST)

        // Identifiants : user:mdp@hote ou ?username=...&password=...
        var user: String? = url.username.ifEmpty { null }
        var pass: String? = url.password.ifEmpty { null }
        if ((user == null) != (pass == null)) return Result.Invalid(Reason.PARTIAL_CREDENTIALS)

        val names = url.queryParameterNames
        if (names.any { it.lowercase() !in KNOWN_PARAMS }) return Result.Invalid(Reason.UNKNOWN_PARAMETERS)
        val qUser = url.queryParameter("username")?.takeIf { it.isNotEmpty() }
        val qPass = url.queryParameter("password")?.takeIf { it.isNotEmpty() }
        if ((qUser == null) != (qPass == null)) return Result.Invalid(Reason.PARTIAL_CREDENTIALS)
        if (qUser != null) {
            user = qUser
            pass = qPass
        }

        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segments.isNotEmpty()) {
            val first = segments.first().lowercase()
            val last = segments.last().lowercase()
            if (first in STREAM_PREFIXES && segments.size >= 3) return Result.Invalid(Reason.STREAM_URL)
            if (STREAM_EXTENSIONS.any { last.endsWith(it) } && last !in ENDPOINTS) return Result.Invalid(Reason.STREAM_URL)
            if (last in ENDPOINTS) segments.removeAt(segments.lastIndex)
        }

        val base = url.newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .encodedPath("/")
            .apply { segments.forEach { addPathSegment(it) } }
            .build()

        return Result.Valid(
            serverUrl = base.toString().trimEnd('/'),
            isCleartext = !base.isHttps,
            extractedUsername = user,
            extractedPassword = pass,
        )
    }
}
