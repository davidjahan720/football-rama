package fr.footballrama.core.epg

import fr.footballrama.core.text.Normalizer
import fr.footballrama.core.xtream.EpgListing
import fr.footballrama.core.xtream.LiveStream
import fr.footballrama.core.xtream.XtreamException
import fr.footballrama.core.xtream.XtreamException.Kind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/** Programme memorise pour une chaine (epoch secondes, textes deja decodes et bornes). */
@Serializable
data class CachedListing(
    @SerialName("s") val start: Long,
    @SerialName("e") val stop: Long? = null,
    @SerialName("t") val title: String,
    @SerialName("d") val description: String = "",
)

@Serializable
data class CachedChannel(
    val fetchedAtMillis: Long,
    /** Fin du dernier programme connu (epoch ms) : la chaine est "couverte" jusque-la. */
    val coverUntilMillis: Long,
    val listings: List<CachedListing>,
)

/**
 * Etat persistant du repli (fichier local, aucune donnee personnelle) : cache par chaine EPG, date "pas avant"
 * imposee par le fournisseur (HTTP 429), date du dernier repli et support de get_simple_data_table.
 */
@Serializable
data class ChannelEpgState(
    val version: Int = 1,
    /** epg_channel_id (minuscules) -> programmes. */
    val channels: Map<String, CachedChannel> = emptyMap(),
    /** Aucune requete player_api avant cette date (epoch ms), 0 = aucune limitation. */
    val notBeforeMillis: Long = 0,
    /** Dernier repli ayant reellement interroge le serveur (epoch ms). */
    val lastFetchMillis: Long = 0,
    /** null = inconnu, true/false = get_simple_data_table fonctionne / pas sur ce panel. */
    val simpleDataTable: Boolean? = null,
) {
    fun notBefore(): Instant? = if (notBeforeMillis > 0) Instant.ofEpochMilli(notBeforeMillis) else null

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(state: ChannelEpgState): String = JSON.encodeToString(serializer(), state)

        /** Etat vide si le texte est illisible ou d'une version inconnue. */
        fun decode(text: String?): ChannelEpgState =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }?.takeIf { it.version == 1 }
                ?: ChannelEpgState()
    }
}

/**
 * Repli "guide chaine par chaine" quand xmltv.php est vide ou illisible, concu pour NE PAS faire bannir le compte :
 * - budget dur de [Config.maxRequests] requetes par rafraichissement, jamais depasse ;
 * - diffuseurs cibles ([Broadcasters]) au lieu de toutes les chaines sport ;
 * - cache par chaine ([Config.cacheTtl]) : seules les chaines dont le guide ne couvre pas [coverTarget] sont
 *   reinterrogees ;
 * - arret IMMEDIAT au premier HTTP 429, avec une date "pas avant" persistante (Retry-After, sinon
 *   [Config.defaultBackoff]) : plus aucune requete avant cette date ;
 * - repli automatique au plus une fois toutes les [Config.minAutoInterval] (une demande manuelle passe, mais
 *   respecte la date "pas avant") ;
 * - une requete a la fois, avec une pause entre deux.
 * get_simple_data_table (tout le guide de la chaine en 1 requete) est prefere ; get_short_epg sinon.
 * Les programmes sont convertis au modele du parseur XMLTV ([Programme]) avec le meme [ProgrammeFilter].
 */
class ChannelEpgFallback(
    private val config: Config = Config(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Config(
        val maxRequests: Int = 60,
        /** limit de get_short_epg (repli quand get_simple_data_table n'est pas supporte). */
        val shortEpgLimit: Int = 150,
        val pauseMs: Long = 400,
        val cacheTtl: Duration = Duration.ofHours(48),
        val minAutoInterval: Duration = Duration.ofHours(6),
        val defaultBackoff: Duration = Duration.ofHours(1),
        val maxBackoff: Duration = Duration.ofHours(24),
        val maxAuthErrors: Int = 2,
        val maxServerErrors: Int = 2,
        val maxConsecutiveNetworkErrors: Int = 3,
        val maxFailuresWithoutSuccess: Int = 4,
        /** Programmes gardes par chaine, et fenetre gardee autour de maintenant. */
        val maxListingsPerChannel: Int = 150,
        val keepBefore: Duration = Duration.ofHours(12),
        val keepAfter: Duration = Duration.ofHours(72),
        val maxCachedChannels: Int = 200,
        val maxCachedDescription: Int = 400,
    )

    enum class StopReason { RATE_LIMITED, AUTH, SERVER, NETWORK, UNSUPPORTED }

    /** Pourquoi aucune requete n'a ete faite. */
    enum class Skip { NOT_BEFORE, TOO_SOON, NOTHING_TO_FETCH }

    /** Appels reseau bloquants fournis par l'appelant (XtreamClient). */
    interface Source {
        fun simpleDataTable(stream: LiveStream): List<EpgListing>
        fun shortEpg(stream: LiveStream, limit: Int): List<EpgListing>
    }

    data class Result(
        val state: ChannelEpgState,
        val data: EpgData,
        val requests: Int,
        val channelsFetched: Int,
        val channelsFailed: Int,
        /** Programmes connus (cache compris) pour les chaines retenues : 0 = aucun guide. */
        val listingsTotal: Int,
        val stopReason: StopReason?,
        val skipped: Skip?,
        val lastError: Kind?,
        /** Date "pas avant" active a la fin (limitation du fournisseur), sinon null. */
        val retryAt: Instant?,
        val elapsedMillis: Long,
    )

    /**
     * @param candidates flux par priorite decroissante ([Broadcasters.select]).
     * @param coverTarget une chaine dont le guide connu va jusqu'a cette date n'est pas reinterrogee (J+1 22:00).
     * @param manual demande explicite de l'utilisateur (ignore [Config.minAutoInterval], jamais la date "pas avant").
     * @param onProgress (requetes faites, requetes prevues).
     */
    suspend fun run(
        state: ChannelEpgState,
        candidates: List<LiveStream>,
        filter: ProgrammeFilter,
        now: Instant,
        coverTarget: Instant,
        manual: Boolean,
        source: Source,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        val t0 = System.nanoTime()
        val nowMs = now.toEpochMilli()
        val cache = LinkedHashMap(prune(state.channels, now))
        var simpleSupported = state.simpleDataTable
        var notBefore = state.notBeforeMillis.takeIf { it > nowMs } ?: 0L
        var lastFetch = state.lastFetchMillis

        val wanted = candidates.filter { !it.epgChannelId.isNullOrBlank() }
        val toFetch = wanted.filter { s -> needsFetch(cache[s.epgChannelId!!.lowercase()], nowMs, coverTarget) }
        val skipped = when {
            notBefore > nowMs -> Skip.NOT_BEFORE
            toFetch.isEmpty() -> Skip.NOTHING_TO_FETCH
            !manual && lastFetch > 0 && nowMs - lastFetch < config.minAutoInterval.toMillis() -> Skip.TOO_SOON
            else -> null
        }

        var requests = 0
        var fetched = 0
        var failed = 0
        var authErrors = 0
        var serverErrors = 0
        var consecutiveNetwork = 0
        var stop: StopReason? = null
        var lastError: Kind? = null
        val budget = config.maxRequests.coerceAtLeast(0)
        val planned = minOf(toFetch.size, budget)

        /** Un appel reseau ; null = echec (deja comptabilise). Leve [RateLimited] au premier 429. */
        suspend fun call(block: () -> List<EpgListing>): Outcome {
            if (requests >= budget) return Outcome.NoBudget
            if (requests > 0 && config.pauseMs > 0) delay(config.pauseMs)
            currentCoroutineContext().ensureActive()
            requests++
            return try {
                Outcome.Ok(runInterruptible(dispatcher) { block() })
            } catch (e: XtreamException) {
                currentCoroutineContext().ensureActive() // annulation : on propage
                Outcome.Failed(e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Outcome.Failed(XtreamException(Kind.INVALID_RESPONSE))
            }
        }

        if (skipped == null) {
            lastFetch = nowMs
            loop@ for (stream in toFetch) {
                if (requests >= budget || stop != null) break
                val epgId = stream.epgChannelId!!.lowercase()
                var listings: List<EpgListing>? = null
                var error: XtreamException? = null

                if (simpleSupported != false) {
                    when (val o = call { source.simpleDataTable(stream) }) {
                        is Outcome.Ok -> {
                            listings = o.listings
                            if (o.listings.isNotEmpty()) simpleSupported = true
                        }
                        is Outcome.Failed -> error = o.e
                        Outcome.NoBudget -> break@loop
                    }
                    val e = error
                    val unsupported = e != null && (e.kind == Kind.INVALID_RESPONSE ||
                        e.kind == Kind.HTTP && e.httpCode != 429 && e.httpCode != 401 && e.httpCode != 403)
                    if (unsupported && simpleSupported == null) simpleSupported = false
                    // Liste vide alors que le support est inconnu, ou reponse trop grande : get_short_epg pour CETTE chaine.
                    val retryShort = (e == null && listings!!.isEmpty() && simpleSupported == null) ||
                        e?.kind == Kind.TOO_LARGE || unsupported
                    if (retryShort) {
                        when (val o = call { source.shortEpg(stream, config.shortEpgLimit) }) {
                            is Outcome.Ok -> {
                                if (o.listings.isNotEmpty() && listings.isNullOrEmpty() && simpleSupported == null) simpleSupported = false
                                listings = o.listings
                                error = null
                            }
                            is Outcome.Failed -> error = o.e
                            Outcome.NoBudget -> Unit // on garde le resultat (vide) de get_simple_data_table
                        }
                    }
                } else {
                    when (val o = call { source.shortEpg(stream, config.shortEpgLimit) }) {
                        is Outcome.Ok -> listings = o.listings
                        is Outcome.Failed -> error = o.e
                        Outcome.NoBudget -> break@loop
                    }
                }

                val err = error
                if (err == null && listings != null) {
                    fetched++
                    consecutiveNetwork = 0
                    cache.remove(epgId)
                    cache[epgId] = toCached(listings, now)
                } else if (err != null) {
                    failed++
                    lastError = err.kind
                    when {
                        err.kind == Kind.HTTP && err.httpCode == 429 -> {
                            // Le fournisseur limite : arret immediat, plus rien avant la date indiquee.
                            val wait = err.retryAfterSeconds?.let { Duration.ofSeconds(it) } ?: config.defaultBackoff
                            notBefore = nowMs + wait.coerceIn(Duration.ofMinutes(1), config.maxBackoff).toMillis()
                            stop = StopReason.RATE_LIMITED
                        }
                        err.kind == Kind.AUTH -> if (++authErrors >= config.maxAuthErrors) stop = StopReason.AUTH
                        err.kind == Kind.SERVER -> if (++serverErrors >= config.maxServerErrors) stop = StopReason.SERVER
                        err.kind in NETWORK_KINDS ->
                            if (++consecutiveNetwork >= config.maxConsecutiveNetworkErrors) stop = StopReason.NETWORK
                        else -> Unit // 404 ponctuel, reponse invalide : chaine ignoree
                    }
                    if (stop == null && fetched == 0 && failed >= config.maxFailuresWithoutSuccess) stop = StopReason.UNSUPPORTED
                }
                onProgress(requests, planned)
            }
        }
        currentCoroutineContext().ensureActive()

        // Bornage du cache : les chaines les plus recemment recuperees d'abord.
        val bounded = cache.entries.sortedByDescending { it.value.fetchedAtMillis }.take(config.maxCachedChannels)
            .associate { it.key to it.value }
        val newState = ChannelEpgState(
            channels = bounded,
            notBeforeMillis = notBefore,
            lastFetchMillis = lastFetch,
            simpleDataTable = simpleSupported,
        )
        val (data, total) = toEpgData(newState, wanted, filter)
        return Result(
            state = newState,
            data = data,
            requests = requests,
            channelsFetched = fetched,
            channelsFailed = failed,
            listingsTotal = total,
            stopReason = stop,
            skipped = skipped,
            lastError = lastError,
            retryAt = if (notBefore > nowMs) Instant.ofEpochMilli(notBefore) else null,
            elapsedMillis = (System.nanoTime() - t0) / 1_000_000,
        )
    }

    private sealed interface Outcome {
        class Ok(val listings: List<EpgListing>) : Outcome
        class Failed(val e: XtreamException) : Outcome
        data object NoBudget : Outcome
    }

    private fun needsFetch(entry: CachedChannel?, nowMs: Long, coverTarget: Instant): Boolean {
        if (entry == null) return true
        if (entry.coverUntilMillis >= coverTarget.toEpochMilli()) return false
        // Guide trop court : on ne reinterroge qu'apres l'intervalle minimal (le panel n'en a peut-etre pas plus).
        return nowMs - entry.fetchedAtMillis >= config.minAutoInterval.toMillis()
    }

    private fun prune(channels: Map<String, CachedChannel>, now: Instant): Map<String, CachedChannel> {
        val minFetched = now.minus(config.cacheTtl).toEpochMilli()
        return channels.filterValues { it.fetchedAtMillis in minFetched..now.toEpochMilli() + 60_000 }
    }

    private fun toCached(listings: List<EpgListing>, now: Instant): CachedChannel {
        val from = now.minus(config.keepBefore).epochSecond
        val to = now.plus(config.keepAfter).epochSecond
        val kept = listings.asSequence()
            .filter { it.start.epochSecond in from..to }
            .sortedBy { it.start }
            .distinctBy { it.start }
            .take(config.maxListingsPerChannel)
            .map { CachedListing(it.start.epochSecond, it.stop?.epochSecond, it.title, it.description.take(config.maxCachedDescription)) }
            .toList()
        val cover = kept.maxOfOrNull { (it.stop ?: it.start) * 1000 } ?: now.toEpochMilli()
        return CachedChannel(now.toEpochMilli(), cover, kept)
    }

    /** Programmes du cache pour les chaines retenues, au modele XMLTV et avec le meme prefiltre. */
    private fun toEpgData(state: ChannelEpgState, wanted: List<LiveStream>, filter: ProgrammeFilter): Pair<EpgData, Int> {
        val lowerBound = filter.from.minus(XmltvParser.COVER_MARGIN).epochSecond
        val upper = filter.to.epochSecond
        val programmes = ArrayList<Programme>()
        val channels = HashMap<String, String>()
        var total = 0
        var inWindow = 0
        for (s in wanted) {
            val epgId = s.epgChannelId!!.lowercase()
            if (channels.containsKey(epgId)) continue
            val entry = state.channels[epgId] ?: continue
            channels[epgId] = s.name
            total += entry.listings.size
            for (l in entry.listings) {
                if (l.start < lowerBound || l.start > upper) continue
                inWindow++
                val text = Normalizer.normalize("${l.title} ${l.description}")
                if (filter.matchesTerms(text)) {
                    programmes.add(Programme(epgId.take(300), Instant.ofEpochSecond(l.start), l.stop?.let(Instant::ofEpochSecond), l.title, text))
                }
            }
        }
        return EpgData(channels, programmes, EpgStats(total, inWindow, programmes.size, channels.size)) to total
    }

    private companion object {
        val NETWORK_KINDS = setOf(Kind.NETWORK, Kind.TIMEOUT, Kind.TLS, Kind.CANCELLED)
    }
}
