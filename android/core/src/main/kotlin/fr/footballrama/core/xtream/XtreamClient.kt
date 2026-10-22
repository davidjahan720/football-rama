package fr.footballrama.core.xtream

import fr.footballrama.core.io.LimitedInputStream
import fr.footballrama.core.io.SizeLimitExceededException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import javax.net.ssl.SSLException

/**
 * Acces Xtream Codes de l'utilisateur. [toString] ne revele jamais les identifiants.
 * Utiliser [XtreamAccount.create] pour valider l'URL.
 */
class XtreamAccount private constructor(
    val serverUrl: HttpUrl,
    val username: String,
    val password: String,
) {
    /** Vrai si le serveur est en HTTP : identifiants et flux circulent en clair (l'UI doit avertir). */
    val isCleartext: Boolean get() = !serverUrl.isHttps

    override fun toString() = "XtreamAccount(<redacted>)"

    companion object {
        /**
         * @return null si l'URL est invalide (schema hors http/https, hote vide, identifiants dans l'URL,
         *   query ou fragment) ou si user/mot de passe sont vides.
         */
        fun create(serverUrl: String, username: String, password: String): XtreamAccount? {
            val raw = serverUrl.trim().let { if ("://" in it) it else "http://$it" }
            val url = raw.toHttpUrlOrNull() ?: return null
            if (url.host.isEmpty() || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.querySize > 0 || url.fragment != null) return null
            if (username.isBlank() || password.isEmpty() || username.length > 256 || password.length > 256) return null
            // Normalise : chemin de base sans segment vide final.
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            val base = url.newBuilder().encodedPath("/").apply { segments.forEach { addPathSegment(it) } }.build()
            return XtreamAccount(base, username.trim(), password)
        }
    }
}

data class LiveCategory(val id: String, val name: String)

data class LiveStream(val streamId: Long, val name: String, val epgChannelId: String?, val categoryId: String?)

/** Erreur reseau/serveur SANS URL, identifiants ni cause chainee (le message de la cause peut contenir l'URL). */
class XtreamException(
    val kind: Kind,
    val httpCode: Int = 0,
    /** En-tete Retry-After (secondes) d'une reponse 429/503, null si absent ou illisible. */
    val retryAfterSeconds: Long? = null,
) : Exception("xtream error: $kind${if (httpCode > 0) " $httpCode" else ""}") {
    enum class Kind { NETWORK, TIMEOUT, TLS, AUTH, HTTP, SERVER, TOO_LARGE, INVALID_RESPONSE, CANCELLED, STORAGE }
}

data class XtreamLimits(
    val maxEpgBytes: Long = 500L * 1024 * 1024,
    val maxStreamsBytes: Long = 50L * 1024 * 1024,
    val maxCategoriesBytes: Long = 5L * 1024 * 1024,
    val maxStreams: Int = 200_000,
    val maxCategories: Int = 20_000,
    /** get_short_epg : quelques dizaines de programmes, quelques Ko en pratique. */
    val maxShortEpgBytes: Long = 256L * 1024,
    /** get_simple_data_table : guide complet d'une chaine (plusieurs jours). */
    val maxSimpleDataTableBytes: Long = 2L * 1024 * 1024,
)

/**
 * Client Xtream (OkHttp). Les identifiants sont passes en query comme l'exige l'API Xtream ; ils ne sont
 * jamais loggues (aucun intercepteur de log), et aucune exception levee ne contient l'URL.
 * Les reponses sont traitees comme NON fiables (taille bornee, JSON parse defensivement).
 *
 * @param userAgent null = User-Agent par defaut d'OkHttp ("okhttp/x.y.z", accepte par la plupart des panels).
 */
class XtreamClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val userAgent: String? = null,
    private val limits: XtreamLimits = XtreamLimits(),
    readTimeoutSeconds: Long = 60,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // borne par appel ci-dessous
        .followRedirects(true)
        // Pas de redirection HTTPS -> HTTP : les identifiants ne doivent pas repasser en clair.
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Verifie les identifiants via `player_api.php` sans action (reponse `{"user_info":{"auth":1,"status":"Active"}}`).
     * Ne pas se contenter de get_live_categories : certains panels y repondent `[]` (200) meme avec un mauvais
     * mot de passe, puis refusent get_live_streams (constate sur boitier reel).
     */
    fun authenticate(account: XtreamAccount) {
        val url = account.serverUrl.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
            .build()
        execute(url, API_CALL_TIMEOUT_S) { body ->
            val text = LimitedInputStream(maybeGunzip(body.stream, AUTH_MAX_BYTES), AUTH_MAX_BYTES).readBytes()
                .toString(Charsets.UTF_8).trim().removePrefix("﻿")
            val obj = runCatching { JSON.decodeFromString(JsonObject.serializer(), text) }.getOrNull()
                ?: throw XtreamException(XtreamException.Kind.AUTH) // corps vide, [] ou HTML : pas d'acces
            val info = obj["user_info"] as? JsonObject ?: throw XtreamException(XtreamException.Kind.AUTH)
            if (info.str("auth") != "1") throw XtreamException(XtreamException.Kind.AUTH)
            val status = info.str("status")?.trim()?.lowercase()
            if (status != null && status != "active") throw XtreamException(XtreamException.Kind.AUTH)
        }
    }

    fun getLiveCategories(account: XtreamAccount): List<LiveCategory> =
        execute(apiUrl(account, "get_live_categories"), API_CALL_TIMEOUT_S) { body ->
            val out = ArrayList<LiveCategory>()
            readJsonArray(body, limits.maxCategoriesBytes) { el ->
                val o = el as? JsonObject ?: return@readJsonArray
                val id = o.str("category_id") ?: return@readJsonArray
                if (out.size < limits.maxCategories) out.add(LiveCategory(id.take(64), (o.str("category_name") ?: "").take(256)))
            }
            out
        }

    fun getLiveStreams(account: XtreamAccount): List<LiveStream> =
        execute(apiUrl(account, "get_live_streams"), API_CALL_TIMEOUT_S) { body ->
            val out = ArrayList<LiveStream>()
            readJsonArray(body, limits.maxStreamsBytes) { el ->
                val o = el as? JsonObject ?: return@readJsonArray
                val id = o.str("stream_id")?.toLongOrNull() ?: return@readJsonArray
                if (out.size >= limits.maxStreams) return@readJsonArray
                out.add(
                    LiveStream(
                        streamId = id,
                        name = (o.str("name") ?: "").take(256),
                        epgChannelId = o.str("epg_channel_id")?.trim()?.takeIf { it.isNotEmpty() }?.take(300),
                        categoryId = o.str("category_id")?.take(64),
                    ),
                )
            }
            out
        }

    /**
     * Guide d'UNE chaine : `player_api.php?action=get_short_epg&stream_id=..&limit=..` (programme en cours puis
     * suivants). Repli quand xmltv.php est vide. Reponse bornee a [XtreamLimits.maxShortEpgBytes].
     */
    fun getShortEpg(account: XtreamAccount, streamId: Long, limit: Int): List<EpgListing> {
        val url = apiUrl(account, "get_short_epg").newBuilder()
            .addQueryParameter("stream_id", streamId.toString())
            .addQueryParameter("limit", limit.coerceIn(1, MAX_SHORT_EPG_LIMIT).toString())
            .build()
        return execute(url, SHORT_EPG_CALL_TIMEOUT_S) { body -> ShortEpgParser.parse(readText(body, limits.maxShortEpgBytes)) }
    }

    /** Variante plus complete (meme structure `epg_listings`), proposee par la plupart des panels. */
    fun getSimpleDataTable(account: XtreamAccount, streamId: Long): List<EpgListing> {
        val url = apiUrl(account, "get_simple_data_table").newBuilder()
            .addQueryParameter("stream_id", streamId.toString())
            .build()
        return execute(url, SHORT_EPG_CALL_TIMEOUT_S) { body -> ShortEpgParser.parse(readText(body, limits.maxSimpleDataTableBytes)) }
    }

    /**
     * Telecharge xmltv.php en flux vers [dest] (decompresse si gzip), sans jamais charger le document en memoire.
     * @param onProgress (octets recus, taille annoncee ou -1) ; peut lever une exception pour annuler.
     * @param callTimeoutSeconds duree maximale de l'appel complet (court quand le fournisseur est connu
     *   pour renvoyer un fichier vide).
     * @return taille ecrite.
     */
    fun downloadEpg(
        account: XtreamAccount,
        dest: File,
        callTimeoutSeconds: Long = EPG_CALL_TIMEOUT_S,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Long {
        val url = account.serverUrl.newBuilder()
            .addPathSegment("xmltv.php")
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
            .build()
        return execute(url, callTimeoutSeconds.coerceAtLeast(1)) { body ->
            val total = body.contentLength
            val raw = LimitedInputStream(body.stream, limits.maxEpgBytes) { n -> onProgress?.invoke(n, total) }
            val out = try {
                dest.outputStream().buffered(64 * 1024)
            } catch (_: IOException) {
                throw XtreamException(XtreamException.Kind.STORAGE)
            }
            var written = 0L
            out.use { o ->
                try {
                    val input = maybeGunzip(raw, limits.maxEpgBytes)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        try {
                            o.write(buf, 0, n)
                        } catch (_: IOException) {
                            throw XtreamException(XtreamException.Kind.STORAGE) // disque plein, etc.
                        }
                        written += n
                    }
                } catch (e: ZipException) {
                    throw XtreamException(XtreamException.Kind.INVALID_RESPONSE)
                }
            }
            written
        }
    }

    // ------------------------------------------------------------------ interne

    private class Body(val stream: InputStream, val contentLength: Long)

    private fun readText(body: Body, maxBytes: Long): String =
        try {
            LimitedInputStream(maybeGunzip(LimitedInputStream(body.stream, maxBytes), maxBytes), maxBytes).readBytes()
                .toString(Charsets.UTF_8)
        } catch (_: ZipException) {
            throw XtreamException(XtreamException.Kind.INVALID_RESPONSE)
        }

    private fun apiUrl(account: XtreamAccount, action: String): HttpUrl =
        account.serverUrl.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
            .addQueryParameter("action", action)
            .build()

    private fun <T> execute(url: HttpUrl, callTimeoutS: Long, read: (Body) -> T): T {
        val reqBuilder = Request.Builder().url(url).get()
        userAgent?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("User-Agent", it) }
        val call = client.newCall(reqBuilder.build())
        call.timeout().timeout(callTimeoutS, TimeUnit.SECONDS)
        try {
            call.execute().use { response ->
                checkStatus(response)
                val body = response.body
                return read(Body(body.byteStream(), body.contentLength()))
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: Exception) {
            if (call.isCanceled()) throw XtreamException(XtreamException.Kind.CANCELLED)
            throw map(e)
        }
    }

    private fun checkStatus(r: Response) {
        val code = r.code
        when {
            r.isSuccessful -> Unit
            code == 401 || code == 403 -> throw XtreamException(XtreamException.Kind.AUTH, code)
            code in 300..399 -> throw XtreamException(XtreamException.Kind.HTTP, code) // ex. HTTPS -> HTTP refuse
            code >= 500 -> throw XtreamException(XtreamException.Kind.SERVER, code, retryAfter(r))
            else -> throw XtreamException(XtreamException.Kind.HTTP, code, retryAfter(r))
        }
    }

    /** Retry-After en secondes ("120") ou en date HTTP ; borne a 7 jours. */
    private fun retryAfter(r: Response): Long? {
        val v = r.header("Retry-After")?.trim()?.takeIf { it.isNotEmpty() && it.length < 64 } ?: return null
        val seconds = v.toLongOrNull() ?: runCatching {
            ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - System.currentTimeMillis() / 1000
        }.getOrNull() ?: return null
        return seconds.coerceIn(0, 7 * 24 * 3600L)
    }

    private fun map(e: Throwable): XtreamException = when {
        e is XtreamException -> e
        e.isLimit() -> XtreamException(XtreamException.Kind.TOO_LARGE)
        e is SocketTimeoutException || e is InterruptedIOException && e.message == "timeout" ->
            XtreamException(XtreamException.Kind.TIMEOUT)
        e is SSLException -> XtreamException(XtreamException.Kind.TLS)
        e is UnknownHostException || e is IOException -> XtreamException(XtreamException.Kind.NETWORK)
        e is SerializationException || e is IllegalArgumentException || e is IllegalStateException ->
            XtreamException(XtreamException.Kind.INVALID_RESPONSE)
        else -> XtreamException(XtreamException.Kind.INVALID_RESPONSE)
    }

    private fun Throwable.isLimit(): Boolean {
        var c: Throwable? = this
        var depth = 0
        while (c != null && depth++ < 10) {
            if (c is SizeLimitExceededException) return true
            c = c.cause
        }
        return false
    }

    /** Detecte le gzip par les octets magiques (certains panels envoient un .gz sans Content-Encoding). */
    private fun maybeGunzip(raw: InputStream, maxDecompressed: Long): InputStream {
        val buffered = BufferedInputStream(raw, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) LimitedInputStream(GZIPInputStream(buffered, 64 * 1024), maxDecompressed) else buffered
    }

    /**
     * Lit un tableau JSON element par element (pas d'arbre complet en memoire). Un objet en racine
     * (ex. {"user_info":{"auth":0}}) signifie identifiants refuses ou reponse inattendue.
     */
    private fun readJsonArray(body: Body, maxBytes: Long, onElement: (JsonElement) -> Unit) {
        val input = BufferedInputStream(maybeGunzip(LimitedInputStream(body.stream, maxBytes), maxBytes), 64 * 1024)
        input.mark(4096)
        var first: Int
        do {
            first = input.read()
        } while (first == ' '.code || first == '\n'.code || first == '\r'.code || first == '\t'.code || first == 0xEF || first == 0xBB || first == 0xBF)
        input.reset()
        when (first) {
            '['.code -> JSON.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
                .forEach(onElement)
            '{'.code -> {
                val obj = runCatching {
                    JSON.decodeFromString(JsonObject.serializer(), LimitedInputStream(input, 1024 * 1024).readBytes().toString(Charsets.UTF_8))
                }.getOrNull()
                val auth = (obj?.get("user_info") as? JsonObject)?.str("auth")
                if (auth == "0") throw XtreamException(XtreamException.Kind.AUTH)
                // Certains panels renvoient {} ou {"1": {...}, ...} : on accepte les valeurs objet.
                if (obj != null && obj["user_info"] == null) {
                    obj.values.forEach(onElement)
                    return
                }
                throw XtreamException(XtreamException.Kind.INVALID_RESPONSE)
            }
            -1 -> Unit // corps vide : aucune donnee
            else -> throw XtreamException(XtreamException.Kind.INVALID_RESPONSE)
        }
    }

    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        val p = v as? JsonPrimitive ?: return null
        return p.content
    }

    companion object {
        private const val API_CALL_TIMEOUT_S = 120L
        private const val AUTH_MAX_BYTES = 1024L * 1024
        const val EPG_CALL_TIMEOUT_S = 15 * 60L
        private const val SHORT_EPG_CALL_TIMEOUT_S = 30L
        private const val MAX_SHORT_EPG_LIMIT = 500
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = false }
    }
}
