package fr.footballrama.app.data

import fr.footballrama.core.epg.EpgParseException
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
import java.time.Instant
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
    private val reportFile = File(filesDir, "matches.json")

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
     */
    suspend fun refresh(minIntervalMs: Long = 60_000): RefreshState = withContext(io) {
        mutex.withLock {
            activeRefresh = coroutineContext[Job]
            val current = _state.value
            if (current is RefreshState.Success && !current.fromCache &&
                clock.millis() - current.updatedAt.toEpochMilli() < minIntervalMs
            ) {
                return@withLock current
            }
            val result = try {
                doRefresh()
            } catch (e: RefreshFailure) {
                RefreshState.Error(e.error, readReport())
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
            cleanupTempFiles()
            _state.value = RefreshState.Idle
        }
    }

    // ------------------------------------------------------------------ etapes

    private class RefreshFailure(val error: RefreshError) : Exception(error.name)

    private suspend fun doRefresh(): RefreshState {
        val job = coroutineContext[Job]
        if (!verifier.isConfigured || !fixturesClient.isConfigured) throw RefreshFailure(RefreshError.NOT_CONFIGURED)
        val account = credentialStore.load() ?: throw RefreshFailure(RefreshError.NO_ACCOUNT)

        progress(RefreshState.Step.FIXTURES, 0f)
        val (fixtures, warning) = loadFixtures()

        progress(RefreshState.Step.CHANNELS, 0.05f)
        val categories = xtream { xtreamClient.getLiveCategories(account) }
        val streams = xtream { xtreamClient.getLiveStreams(account) }

        val epgFile = File(cacheDir, "$TEMP_PREFIX${System.nanoTime()}.xml")
        try {
            progress(RefreshState.Step.EPG_DOWNLOAD, 0.15f)
            var lastEmit = -1
            xtream {
                xtreamClient.downloadEpg(account, epgFile) { read, total ->
                    job?.ensureActive()
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
            }

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
                throw RefreshFailure(if (e.reason == EpgParseException.Reason.TOO_LARGE) RefreshError.TOO_LARGE else RefreshError.EPG_INVALID)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw RefreshFailure(RefreshError.STORAGE)
            }

            progress(RefreshState.Step.MATCHING, 0.97f)
            writeReport(report)
            return RefreshState.Success(report, Instant.ofEpochMilli(report.updatedAtMillis), fromCache = false, warning = warning)
        } finally {
            epgFile.delete()
        }
    }

    private fun progress(step: RefreshState.Step, value: Float) {
        _state.value = RefreshState.Loading(step, value)
    }

    private suspend fun <T> xtream(block: () -> T): T = try {
        block()
    } catch (e: XtreamException) {
        currentCoroutineContext().ensureActive() // annulation : on propage la CancellationException
        throw RefreshFailure(
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
            },
        )
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
    }
}
