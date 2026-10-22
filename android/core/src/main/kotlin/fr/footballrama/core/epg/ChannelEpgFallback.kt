package fr.footballrama.core.epg

import fr.footballrama.core.matching.ChannelNames
import fr.footballrama.core.text.Normalizer
import fr.footballrama.core.xtream.EpgListing
import fr.footballrama.core.xtream.LiveCategory
import fr.footballrama.core.xtream.LiveStream
import fr.footballrama.core.xtream.XtreamException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Selection des chaines "sport" a interroger une par une quand xmltv.php est vide ou illisible.
 * Heuristique sans regex (moteur ICU d'Android) : mots-cles cherches dans le nom de flux et le nom de
 * categorie, compares sous une forme compacte (majuscules, sans accents, seuls [A-Z0-9+] gardes).
 */
object SportChannels {
    /** Plafond dur du nombre de chaines interrogees (une requete par chaine). */
    const val DEFAULT_MAX_CHANNELS = 600

    val KEYWORDS: List<String> = listOf(
        "SPORT", "FOOT", "SOCCER", "FUTBOL", "BEIN", "CANAL+", "RMC", "DAZN", "EUROSPORT", "SKY SPORT",
        "TNT SPORT", "AMAZON", "PRIME", "MOVISTAR", "ZIGGO", "BLUE SPORT", "PLAY SPORTS", "PICKX", "ELEVEN",
        "SPORT TV", "SPORTV", "ESPN", "TUDN", "ARENA SPORT", "NOVA SPORT", "DIGI SPORT", "PRIMA SPORT",
        "TVP SPORT", "POLSAT", "VIAPLAY", "TV 3 SPORT", "SUPERSPORT", "SETANTA", "COSMOTE", "LIGUE 1",
        "L'EQUIPE", "TELEFOOT",
    ).map(::compact).distinct()

    /** Pays prioritaires (rang) : FR, UK, BE, CH ; tous les autres ensuite. */
    private val COUNTRY_RANK: Map<String, Int> = buildMap {
        listOf("FR", "FRA", "FRANCE").forEach { put(it, 0) }
        listOf("UK", "GB", "GBR", "ENGLAND", "ENG").forEach { put(it, 1) }
        listOf("BE", "BEL", "BELGIUM", "BELGIQUE", "BELGIE").forEach { put(it, 2) }
        listOf("CH", "SUI", "SWISS", "SUISSE", "SWITZERLAND", "SCHWEIZ").forEach { put(it, 3) }
    }
    private const val OTHER_RANK = 4

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

    fun isSport(streamName: String, categoryName: String?): Boolean {
        val n = compact(streamName)
        val c = categoryName?.let(::compact).orEmpty()
        return KEYWORDS.any { k -> n.contains(k) || c.contains(k) }
    }

    /** Rang pays 0..4 (0 = FR) d'apres le prefixe du flux ("|FR| ...", "FR: ...") ou de la categorie. */
    fun countryRank(streamName: String, categoryName: String?): Int {
        ChannelNames.countryPrefix(streamName)?.let { code -> COUNTRY_RANK[code]?.let { return it } }
        val fromName = firstWord(streamName)?.let { COUNTRY_RANK[it] }
        if (fromName != null) return fromName
        val cat = categoryName ?: return OTHER_RANK
        ChannelNames.countryPrefix(cat)?.let { code -> COUNTRY_RANK[code]?.let { return it } }
        val words = Normalizer.normalize(cat).uppercase().split(' ').filter { it.isNotEmpty() }
        if (words.size >= 2 && words[0] == "UNITED" && words[1] == "KINGDOM") return 1
        return words.firstOrNull()?.let { COUNTRY_RANK[it] } ?: OTHER_RANK
    }

    private fun firstWord(s: String): String? =
        Normalizer.normalize(s).substringBefore(' ').uppercase().takeIf { it.isNotEmpty() }

    /**
     * Flux a interroger : sport uniquement, un seul flux par epg_channel_id (sans epg_channel_id, aucun
     * programme ne pourrait etre relie au flux), pays prioritaires d'abord, au plus [max] chaines.
     */
    fun select(streams: List<LiveStream>, categories: List<LiveCategory>, max: Int = DEFAULT_MAX_CHANNELS): List<LiveStream> {
        val catName = categories.associate { it.id to it.name }
        val candidates = ArrayList<Pair<Int, LiveStream>>()
        for (s in streams) {
            if (s.epgChannelId.isNullOrBlank()) continue
            val cat = s.categoryId?.let { catName[it] }
            if (!isSport(s.name, cat)) continue
            candidates.add(countryRank(s.name, cat) to s)
        }
        // Tri stable : l'ordre du fournisseur est conserve a rang egal.
        candidates.sortBy { it.first }
        val seen = HashSet<String>()
        val out = ArrayList<LiveStream>()
        for ((_, s) in candidates) {
            if (out.size >= max) break
            if (seen.add(s.epgChannelId!!.lowercase())) out.add(s)
        }
        return out
    }
}

/**
 * Repli "guide chaine par chaine" : interroge get_short_epg pour chaque flux selectionne, avec une
 * concurrence bornee et une pause entre requetes (eviter le bannissement par le fournisseur), puis convertit
 * les programmes au modele du parseur XMLTV ([Programme]) avec le meme prefiltre ([ProgrammeFilter]) :
 * [fr.footballrama.core.matching.Matcher] fonctionne alors a l'identique.
 *
 * Arrets anticipes (on garde ce qui a deja ete recupere) : [Config.maxAuthErrors] refus d'acces,
 * [Config.maxThrottleErrors] reponses 429/5xx, [Config.maxConsecutiveNetworkErrors] erreurs reseau
 * consecutives, ou [Config.maxFailuresWithoutSuccess] echecs sans aucun succes (action non supportee).
 */
class ChannelEpgFallback(
    private val config: Config = Config(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Config(
        /** Nombre de programmes demandes par chaine : couvre J/J+1 sur une chaine sport (programmes de 1 a 2 h). */
        val limit: Int = 40,
        val concurrency: Int = 2,
        /** Pause apres chaque requete, par travailleur. */
        val pauseMs: Long = 150,
        /** Pause maximale apres ralentissements successifs (429/5xx). */
        val maxPauseMs: Long = 3_000,
        val maxAuthErrors: Int = 2,
        val maxThrottleErrors: Int = 3,
        val maxConsecutiveNetworkErrors: Int = 4,
        val maxFailuresWithoutSuccess: Int = 8,
    )

    enum class StopReason { AUTH, THROTTLED, NETWORK, UNSUPPORTED }

    data class Result(
        val data: EpgData,
        val channelsQueried: Int,
        val channelsSucceeded: Int,
        val channelsFailed: Int,
        /** Programmes recus (avant prefiltre) : 0 = le fournisseur n'a aucun guide. */
        val listingsTotal: Int,
        val stopReason: StopReason?,
        /** Derniere erreur rencontree (pour choisir le message si rien n'a ete recupere). */
        val lastError: XtreamException.Kind?,
        val elapsedMillis: Long,
    )

    val limit: Int get() = config.limit

    /**
     * @param fetchOne appel bloquant (ex. `client.getShortEpg(account, s.streamId, limit)`) ; peut lever
     *   [XtreamException]. Toute autre exception est traitee comme une reponse invalide.
     * @param onProgress (chaines traitees, total) ; appele depuis les travailleurs.
     */
    suspend fun fetch(
        streams: List<LiveStream>,
        filter: ProgrammeFilter,
        fetchOne: (LiveStream) -> List<EpgListing>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        val t0 = System.nanoTime()
        val total = streams.size
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val stop = AtomicReference<StopReason?>(null)
        val lock = Any()
        val programmes = ArrayList<Programme>()
        val channels = HashMap<String, String>()
        var queried = 0
        var succeeded = 0
        var failed = 0
        var listingsTotal = 0
        var inWindow = 0
        var authErrors = 0
        var throttleErrors = 0
        var consecutiveNetwork = 0
        var pauseMs = config.pauseMs
        var lastError: XtreamException.Kind? = null
        val lowerBound = filter.from.minus(XmltvParser.COVER_MARGIN)

        fun record(kind: XtreamException.Kind, httpCode: Int) = synchronized(lock) {
            failed++
            lastError = kind
            when {
                kind == XtreamException.Kind.AUTH -> {
                    authErrors++
                    if (authErrors >= config.maxAuthErrors) stop.compareAndSet(null, StopReason.AUTH)
                }
                kind == XtreamException.Kind.SERVER || kind == XtreamException.Kind.HTTP && httpCode == 429 -> {
                    throttleErrors++
                    // Le serveur demande de ralentir : pause doublee (au moins 500 ms), plafonnee.
                    pauseMs = minOf(maxOf(pauseMs * 2, 500L), config.maxPauseMs)
                    if (throttleErrors >= config.maxThrottleErrors) stop.compareAndSet(null, StopReason.THROTTLED)
                }
                kind == XtreamException.Kind.NETWORK || kind == XtreamException.Kind.TIMEOUT ||
                    kind == XtreamException.Kind.TLS || kind == XtreamException.Kind.CANCELLED -> {
                    consecutiveNetwork++
                    if (consecutiveNetwork >= config.maxConsecutiveNetworkErrors) stop.compareAndSet(null, StopReason.NETWORK)
                }
                else -> Unit // 404, reponse invalide ou trop grande : chaine ignoree
            }
            if (succeeded == 0 && failed >= config.maxFailuresWithoutSuccess) stop.compareAndSet(null, StopReason.UNSUPPORTED)
        }

        coroutineScope {
            repeat(minOf(config.concurrency.coerceAtLeast(1), total)) { worker ->
                launch {
                    if (worker > 0) delay(config.pauseMs / 2) // demarrages decales
                    while (stop.get() == null) {
                        val i = next.getAndIncrement()
                        if (i >= total) break
                        val stream = streams[i]
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) { queried++ }
                        val listings: List<EpgListing>? = try {
                            runInterruptible(dispatcher) { fetchOne(stream) }
                        } catch (e: XtreamException) {
                            currentCoroutineContext().ensureActive() // annulation : on propage
                            record(e.kind, e.httpCode)
                            null
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            record(XtreamException.Kind.INVALID_RESPONSE, 0)
                            null
                        }
                        if (listings != null) {
                            val epgId = stream.epgChannelId!!.lowercase().take(MAX_ID)
                            var win = 0
                            val kept = ArrayList<Programme>()
                            for (l in listings) {
                                if (l.start.isBefore(lowerBound) || l.start.isAfter(filter.to)) continue
                                win++
                                val text = Normalizer.normalize("${l.title} ${l.description}")
                                if (filter.matchesTerms(text)) kept.add(Programme(epgId, l.start, l.stop, l.title, text))
                            }
                            synchronized(lock) {
                                succeeded++
                                consecutiveNetwork = 0
                                listingsTotal += listings.size
                                inWindow += win
                                if (programmes.size < XmltvParser.MAX_PROGRAMMES) programmes.addAll(kept)
                                channels.putIfAbsent(epgId, stream.name)
                            }
                        }
                        onProgress(done.incrementAndGet(), total)
                        val wait = synchronized(lock) { pauseMs }
                        if (wait > 0 && stop.get() == null && next.get() < total) delay(wait)
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            Result(
                data = EpgData(channels, programmes.toList(), EpgStats(listingsTotal, inWindow, programmes.size, channels.size)),
                channelsQueried = queried,
                channelsSucceeded = succeeded,
                channelsFailed = failed,
                listingsTotal = listingsTotal,
                stopReason = stop.get(),
                lastError = lastError,
                elapsedMillis = (System.nanoTime() - t0) / 1_000_000,
            )
        }
    }

    private companion object {
        const val MAX_ID = 300
    }
}
