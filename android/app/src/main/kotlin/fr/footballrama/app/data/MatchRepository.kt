package fr.footballrama.app.data

import fr.footballrama.core.epg.ChannelEpgFallback
import fr.footballrama.core.epg.EpgParseException
import fr.footballrama.core.epg.Broadcasters
import fr.footballrama.core.epg.ChannelEpgState
import fr.footballrama.core.fixtures.DataAttribution
import fr.footballrama.core.fixtures.Fixtures
import fr.footballrama.core.fixtures.FixturesClient
import fr.footballrama.core.fixtures.FixturesDownloadException
import fr.footballrama.core.fixtures.FixturesException
import fr.footballrama.core.fixtures.FixturesVerifier
import fr.footballrama.core.io.LimitedInputStream
import fr.footballrama.core.matching.MatchPipeline
import fr.footballrama.core.matching.MatchReport
import fr.footballrama.core.matching.MatchReportCodec
import fr.footballrama.core.xtream.LiveCategory
import fr.footballrama.core.xtream.LiveStream
import fr.footballrama.core.xtream.XtreamAccount
import fr.footballrama.core.xtream.XtreamClient
import fr.footballrama.core.xtream.XtreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Orchestration : calendrier signe -> chaines Xtream -> EPG (fichier temporaire) -> matching -> resultat persiste.
 * Tout se fait sur l'appareil ; les identifiants ne partent que vers le serveur IPTV de l'utilisateur.
 * Un seul rafraichissement a la fois (Mutex) : UI et WorkManager peuvent appeler [refresh] sans doublon.
 */
class MatchRepository(
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient,
    private val fixturesClient: FixturesClient,
    private val verifier: FixturesVerifier,
    private val pipeline: MatchPipeline,
    private val filesDir: File,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
    /** Delais entre tentatives quand la signature est invalide (incoherence CDN passagere). */
    private val signatureRetryDelaysMs: List<Long> = listOf(20_000, 60_000),
    /** Repli "guide chaine par chaine" quand xmltv.php est vide ou illisible. */
    private val channelFallback: ChannelEpgFallback = ChannelEpgFallback(dispatcher = io),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val _state = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val state: StateFlow<RefreshState> = _state.asStateFlow()

    /** Mention de licence a afficher par l'UI. */
    val attribution: String = DataAttribution.FOOTBALL_DATA

    private val mutex = Mutex()

    /** Job du rafraichissement en cours (sous [mutex]), annule par [signOut]. */
    @Volatile
    private var activeRefresh: Job? = null
    private val fixturesDir = File(filesDir, "fixtures")

    /** Present si le dernier xmltv.php etait vide/absent/illisible (preference locale non sensible). */
    private val noXmltvMarker = File(filesDir, "xmltv_unavailable")
    private val reportFile = File(filesDir, "matches.json")

    /** Cache du guide par chaine + date "pas avant" du fournisseur (aucune donnee personnelle). */
    private val fallbackStateFile = File(filesDir, "epg_channels.json")

    init {
        scope.launch(io) {
            cleanupTempFiles()
            val cached = readReport()
            if (cached != null && _state.value == RefreshState.Idle) {
                _state.value = RefreshState.Success(cached, Instant.ofEpochMilli(cached.updatedAtMillis), fromCache = true)
            }
        }
    }

    /** Dernier resultat persiste (null si aucun). */
    suspend fun cachedReport(): MatchReport? = withContext(io) { readReport() }

    /**
     * Rafraichissement complet. Si un rafraichissement est deja en cours, attend sa fin puis en relance un
     * seulement si le resultat a plus de [minIntervalMs].
     * @param manual demande explicite de l'utilisateur (bouton Actualiser) : le repli chaine par chaine peut alors
     *   tourner avant l'intervalle minimal automatique, mais jamais avant la date imposee par le fournisseur.
     */
    suspend fun refresh(minIntervalMs: Long = 60_000, manual: Boolean = false): RefreshState = withContext(io) {
        mutex.withLock {
            activeRefresh = coroutineContext[Job]
            val current = _state.value
            if (current is RefreshState.Success && !current.fromCache &&
                clock.millis() - current.updatedAt.toEpochMilli() < minIntervalMs
            ) {
                return@withLock current
            }
            val result = try {
                doRefresh(manual)
            } catch (e: RefreshFailure) {
                RefreshState.Error(e.error, readReport(), e.retryAt)
            } catch (e: CancellationException) {
                _state.value = readReport()?.let { RefreshState.Error(RefreshError.UNKNOWN, it) } ?: RefreshState.Idle
                activeRefresh = null
                throw e
            } catch (e: Throwable) {
                // Toute erreur imprevue (bug, OOM...) doit sortir l'UI de l'etat Loading, sinon l'ecran reste fige
                RefreshState.Error(RefreshError.UNKNOWN, readReport())
            }
            _state.value = result
            activeRefresh = null
            result
        }
    }

    /** Se deconnecter : efface identifiants, resultats et fichiers temporaires. Le calendrier (public) est garde. */
    suspend fun signOut() = withContext(io) {
        // Audit securite : un rafraichissement lance par l'UI (hors WorkManager) peut durer jusqu'a 15 min
        // (EPG) en tenant le Mutex ; sans annulation, l'effacement demande par l'utilisateur attendrait d'autant.
        activeRefresh?.cancel()
        mutex.withLock {
            activeRefresh = null
            credentialStore.clear()
            reportFile.delete()
            noXmltvMarker.delete()
            fallbackStateFile.delete()
            cleanupTempFiles()
            _state.value = RefreshState.Idle
        }
    }

    // ------------------------------------------------------------------ etapes

    private class RefreshFailure(val error: RefreshError, val retryAt: Instant? = null) : Exception(error.name)

    private suspend fun doRefresh(manual: Boolean): RefreshState {
        val job = coroutineContext[Job]
        if (!verifier.isConfigured || !fixturesClient.isConfigured) throw RefreshFailure(RefreshError.NOT_CONFIGURED)
        val account = credentialStore.load() ?: throw RefreshFailure(RefreshError.NO_ACCOUNT)
        // Fournisseur qui a demande de ralentir (HTTP 429) : AUCUNE requete Xtream avant la date indiquee.
        readFallbackState().notBefore()?.takeIf { it.isAfter(clock.instant()) }?.let {
            throw RefreshFailure(RefreshError.RATE_LIMITED, it)
        }

        progress(RefreshState.Step.FIXTURES, 0f)
        val (fixtures, warning) = loadFixtures()

        progress(RefreshState.Step.CHANNELS, 0.05f)
        val categories = xtream { xtreamClient.getLiveCategories(account) }
        val streams = xtream { xtreamClient.getLiveStreams(account) }

        val epgFile = File(cacheDir, "$TEMP_PREFIX${System.nanoTime()}.xml")
        try {
            val xmltv = readXmltv(account, epgFile, fixtures, streams, categories, job)
            val (report, limitedUntil) = xmltv?.let { it to null }
                ?: runChannelFallback(account, fixtures, streams, categories, manual)
            progress(RefreshState.Step.MATCHING, 0.97f)
            writeReport(report)
            return RefreshState.Success(
                report,
                Instant.ofEpochMilli(report.updatedAtMillis),
                fromCache = false,
                warning = if (limitedUntil != null) RefreshState.Warning.PROVIDER_LIMITED else warning,
                retryAt = limitedUntil,
            )
        } finally {
            epgFile.delete()
        }
    }

    /**
     * Fichier complet xmltv.php. @return null si le guide est vide, absent (404) ou illisible : le repli chaine
     * par chaine prend alors le relais. Si le fournisseur est connu pour ne pas fournir de fichier (marqueur
     * [noXmltvMarker]), xmltv.php est quand meme tente d'abord, mais avec un delai court.
     */
    private suspend fun readXmltv(
        account: XtreamAccount,
        epgFile: File,
        fixtures: Fixtures,
        streams: List<LiveStream>,
        categories: List<LiveCategory>,
        job: Job?,
    ): MatchReport? {
        val knownMissing = noXmltvMarker.isFile
        progress(RefreshState.Step.EPG_DOWNLOAD, 0.15f)
        var lastEmit = -1
        var received = 0L
        try {
            xtreamClient.downloadEpg(
                account,
                epgFile,
                callTimeoutSeconds = if (knownMissing) SHORT_XMLTV_TIMEOUT_S else XtreamClient.EPG_CALL_TIMEOUT_S,
            ) { read, total ->
                job?.ensureActive()
                received = read
                if (total > 0) {
                    val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                    if (pct != lastEmit) {
                        lastEmit = pct
                        _state.value = RefreshState.Loading(RefreshState.Step.EPG_DOWNLOAD, 0.15f + 0.55f * pct / 100f)
                    }
                } else if (lastEmit != -2) {
                    lastEmit = -2
                    _state.value = RefreshState.Loading(RefreshState.Step.EPG_DOWNLOAD, null)
                }
            }
        } catch (e: XtreamException) {
            currentCoroutineContext().ensureActive()
            when {
                // Pas de fichier XMLTV sur ce panel : repli.
                e.kind == XtreamException.Kind.HTTP && e.httpCode == 404 -> return markNoXmltv()
                // Delai court depasse : si des donnees arrivaient, le fichier est peut-etre revenu -> delai normal
                // la prochaine fois. Dans tous les cas, repli pour ce rafraichissement.
                knownMissing && e.kind == XtreamException.Kind.TIMEOUT -> {
                    if (received >= MIN_EPG_BYTES) noXmltvMarker.delete()
                    return null
                }
                e.isTooManyRequests() -> throw rateLimited(e)
                else -> throw RefreshFailure(toRefreshError(e))
            }
        }

        // Guide vide (quelques octets) : cas reel d'un fournisseur qui ne publie plus xmltv.php.
        if (epgFile.length() < MIN_EPG_BYTES) return markNoXmltv()
        progress(RefreshState.Step.EPG_PARSE, 0.7f)
        val size = epgFile.length().coerceAtLeast(1)
        var lastParse = -1
        val report = try {
            LimitedInputStream(epgFile.inputStream().buffered(1 shl 16), Long.MAX_VALUE) { read ->
                job?.ensureActive()
                val pct = (read * 100 / size).toInt()
                if (pct != lastParse) {
                    lastParse = pct
                    _state.value = RefreshState.Loading(RefreshState.Step.EPG_PARSE, 0.7f + 0.25f * pct / 100f)
                }
            }.use { input -> pipeline.run(fixtures, input, streams, categories, clock.instant()) }
        } catch (e: EpgParseException) {
            currentCoroutineContext().ensureActive()
            when (e.reason) {
                EpgParseException.Reason.MALFORMED -> return markNoXmltv() // HTML, JSON, tronque... : repli
                EpgParseException.Reason.TOO_LARGE -> throw RefreshFailure(RefreshError.TOO_LARGE)
                else -> throw RefreshFailure(RefreshError.EPG_INVALID)
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw RefreshFailure(RefreshError.STORAGE)
        }
        noXmltvMarker.delete() // le fichier complet fonctionne (de nouveau)
        return report
    }

    /** Memorise (fichier local non sensible) que ce fournisseur ne fournit pas de xmltv.php exploitable. */
    private fun markNoXmltv(): MatchReport? {
        runCatching { noXmltvMarker.writeText("1") }
        return null
    }

    /**
     * Repli : guide chaine par chaine sur les diffuseurs listes ([Broadcasters]), budget dur de requetes, cache
     * par chaine, arret au premier 429 (voir [ChannelEpgFallback]). Aucun programme -> EPG_EMPTY (ou l'erreur
     * qui a tout bloque, dont RATE_LIMITED).
     */
    private suspend fun runChannelFallback(
        account: XtreamAccount,
        fixtures: Fixtures,
        streams: List<LiveStream>,
        categories: List<LiveCategory>,
        manual: Boolean,
    ): Pair<MatchReport, Instant?> {
        progress(RefreshState.Step.EPG_CHANNELS, 0.15f)
        val candidates = Broadcasters.select(streams, categories)
        if (candidates.isEmpty()) throw RefreshFailure(RefreshError.EPG_EMPTY)
        val now = clock.instant()
        val lastPct = AtomicInteger(-1)
        val source = object : ChannelEpgFallback.Source {
            override fun simpleDataTable(stream: LiveStream) = xtreamClient.getSimpleDataTable(account, stream.streamId)
            override fun shortEpg(stream: LiveStream, limit: Int) = xtreamClient.getShortEpg(account, stream.streamId, limit)
        }
        val result = channelFallback.run(
            state = readFallbackState(),
            candidates = candidates,
            filter = pipeline.filterFor(fixtures),
            now = now,
            coverTarget = coverTarget(now),
            manual = manual,
            source = source,
        ) { done, total ->
            val pct = done * 100 / total.coerceAtLeast(1)
            if (lastPct.getAndSet(pct) != pct) {
                _state.value = RefreshState.Loading(RefreshState.Step.EPG_CHANNELS, 0.15f + 0.8f * pct / 100f)
            }
        }
        writeFallbackState(result.state)
        if (result.listingsTotal == 0) {
            if (result.retryAt != null) throw RefreshFailure(RefreshError.RATE_LIMITED, result.retryAt)
            throw RefreshFailure(
                when (result.stopReason) {
                    ChannelEpgFallback.StopReason.AUTH -> RefreshError.AUTH
                    ChannelEpgFallback.StopReason.SERVER -> RefreshError.SERVER
                    ChannelEpgFallback.StopReason.NETWORK ->
                        if (result.lastError == XtreamException.Kind.TIMEOUT) RefreshError.TIMEOUT else RefreshError.NETWORK
                    else -> RefreshError.EPG_EMPTY
                },
            )
        }
        val report = pipeline.runOnProgrammes(
            fixtures, result.data, streams, categories, clock.instant(),
            elapsedMillis = result.elapsedMillis,
            channelsQueried = result.requests,
            channelsFailed = result.channelsFailed,
        )
        return report to result.retryAt
    }

    /** Une chaine est "couverte" si son guide connu va jusqu'au lendemain 22:00 (heure de l'appareil). */
    private fun coverTarget(now: Instant): Instant =
        now.atZone(zone).toLocalDate().plusDays(1).atTime(22, 0).atZone(zone).toInstant()

    private fun readFallbackState(): ChannelEpgState =
        ChannelEpgState.decode(runCatching { fallbackStateFile.takeIf { it.isFile }?.readText() }.getOrNull())

    private fun writeFallbackState(state: ChannelEpgState) {
        try {
            atomicWrite(fallbackStateFile, ChannelEpgState.encode(state).toByteArray())
        } catch (_: IOException) {
            // Non bloquant : le resultat de ce rafraichissement reste valable.
        }
    }

    /** HTTP 429 sur n'importe quel appel Xtream : date "pas avant" persistante (Retry-After, sinon 1 h). */
    private fun rateLimited(e: XtreamException): RefreshFailure {
        val now = clock.instant()
        val wait = e.retryAfterSeconds?.let { Duration.ofSeconds(it) } ?: Duration.ofHours(1)
        val until = now.plus(wait.coerceIn(Duration.ofMinutes(1), Duration.ofHours(24)))
        writeFallbackState(readFallbackState().copy(notBeforeMillis = until.toEpochMilli()))
        return RefreshFailure(RefreshError.RATE_LIMITED, until)
    }

    private fun progress(step: RefreshState.Step, value: Float) {
        _state.value = RefreshState.Loading(step, value)
    }

    private suspend fun <T> xtream(block: () -> T): T = try {
        block()
    } catch (e: XtreamException) {
        currentCoroutineContext().ensureActive() // annulation : on propage la CancellationException
        if (e.isTooManyRequests()) throw rateLimited(e)
        throw RefreshFailure(toRefreshError(e))
    }

    private fun XtreamException.isTooManyRequests() = kind == XtreamException.Kind.HTTP && httpCode == 429

    private fun toRefreshError(e: XtreamException): RefreshError =
        when (e.kind) {
            XtreamException.Kind.NETWORK -> RefreshError.NETWORK
            XtreamException.Kind.TIMEOUT -> RefreshError.TIMEOUT
            XtreamException.Kind.TLS -> RefreshError.TLS
            XtreamException.Kind.AUTH -> RefreshError.AUTH
            XtreamException.Kind.HTTP -> if (e.httpCode == 404) RefreshError.INVALID_RESPONSE else RefreshError.SERVER
            XtreamException.Kind.SERVER -> RefreshError.SERVER
            XtreamException.Kind.TOO_LARGE -> RefreshError.TOO_LARGE
            XtreamException.Kind.INVALID_RESPONSE -> RefreshError.INVALID_RESPONSE
            XtreamException.Kind.STORAGE -> RefreshError.STORAGE
            XtreamException.Kind.CANCELLED -> RefreshError.NETWORK
        }

    // ------------------------------------------------------------------ calendrier

    private val cachedJson get() = File(fixturesDir, "fixtures.json")
    private val cachedSig get() = File(fixturesDir, "fixtures.json.sig")
    /** generated_at du dernier calendrier accepte (anti-rollback), conserve meme si le calendrier expire. */
    private val generatedAtMarker get() = File(fixturesDir, "generated_at")

    private suspend fun loadFixtures(): Pair<Fixtures, RefreshState.Warning?> {
        val previous = generatedAtMarker.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let(Instant::ofEpochMilli)
        var lastError = RefreshError.FIXTURES_UNAVAILABLE
        val delays = listOf(0L) + signatureRetryDelaysMs
        for (wait in delays) {
            if (wait > 0) delay(wait)
            try {
                val remote = fixturesClient.download()
                val fixtures = verifier.verify(remote.json, remote.signature, previous)
                saveFixtures(remote.json, remote.signature, fixtures.generatedAt)
                return fixtures to null
            } catch (e: FixturesDownloadException) {
                lastError = RefreshError.FIXTURES_UNAVAILABLE
                break // reseau/HTTP : on se rabat sur le cache, WorkManager reessaiera
            } catch (e: FixturesException) {
                when (e.reason) {
                    // JSON et .sig de deux versions (cache CDN) : on reessaie un peu plus tard.
                    FixturesException.Reason.BAD_SIGNATURE -> lastError = RefreshError.FIXTURES_UNAVAILABLE
                    FixturesException.Reason.EXPIRED -> { lastError = RefreshError.FIXTURES_EXPIRED; break }
                    FixturesException.Reason.NO_PUBLIC_KEY -> throw RefreshFailure(RefreshError.NOT_CONFIGURED)
                    else -> { lastError = RefreshError.FIXTURES_UNAVAILABLE; break } // rollback, schema, malforme
                }
            }
        }
        // Repli : dernier calendrier accepte, s'il est encore valide (signature re-verifiee).
        val cached = runCatching {
            verifier.verify(cachedJson.readBytes(), cachedSig.readBytes(), previous)
        }.getOrNull() ?: throw RefreshFailure(lastError)
        return cached to RefreshState.Warning.FIXTURES_FROM_CACHE
    }

    private fun saveFixtures(json: ByteArray, sig: ByteArray, generatedAt: Instant) {
        try {
            fixturesDir.mkdirs()
            atomicWrite(cachedJson, json)
            atomicWrite(cachedSig, sig)
            atomicWrite(generatedAtMarker, generatedAt.toEpochMilli().toString().toByteArray())
        } catch (_: IOException) {
            // Non bloquant : le calendrier verifie reste utilisable pour ce rafraichissement.
        }
    }

    // ------------------------------------------------------------------ persistance

    private fun readReport(): MatchReport? =
        runCatching { reportFile.takeIf { it.isFile }?.readText()?.let(MatchReportCodec::decode) }.getOrNull()

    private fun writeReport(report: MatchReport) {
        try {
            atomicWrite(reportFile, MatchReportCodec.encode(report).toByteArray())
        } catch (_: IOException) {
            throw RefreshFailure(RefreshError.STORAGE)
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("rename failed")
        }
    }

    private fun cleanupTempFiles() {
        cacheDir.listFiles { f -> f.name.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }
    }

    private companion object {
        const val TEMP_PREFIX = "epg-"
        /** En dessous, le fichier ne peut contenir aucun programme (un XMLTV minimal fait deja plus). */
        const val MIN_EPG_BYTES = 64L

        /** Delai de xmltv.php quand le fournisseur est connu pour renvoyer un fichier vide (reponse rapide). */
        const val SHORT_XMLTV_TIMEOUT_S = 45L
    }
}
