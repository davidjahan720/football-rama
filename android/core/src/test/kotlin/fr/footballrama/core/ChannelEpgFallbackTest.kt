package fr.footballrama.core

import fr.footballrama.core.epg.Broadcasters
import fr.footballrama.core.epg.ChannelEpgFallback
import fr.footballrama.core.epg.ChannelEpgFallback.Skip
import fr.footballrama.core.epg.ChannelEpgFallback.StopReason
import fr.footballrama.core.epg.ChannelEpgState
import fr.footballrama.core.epg.ProgrammeFilter
import fr.footballrama.core.epg.XmltvParser
import fr.footballrama.core.matching.EpgSource
import fr.footballrama.core.matching.MatchPipeline
import fr.footballrama.core.matching.Score
import fr.footballrama.core.xtream.EpgListing
import fr.footballrama.core.xtream.LiveCategory
import fr.footballrama.core.xtream.LiveStream
import fr.footballrama.core.xtream.XtreamAccount
import fr.footballrama.core.xtream.XtreamClient
import fr.footballrama.core.xtream.XtreamException
import fr.footballrama.core.xtream.XtreamException.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ChannelEpgFallbackTest {

    @TempDir
    lateinit var tmp: File

    private val categories = listOf(
        LiveCategory("1", "|FR| SPORTS"),
        LiveCategory("2", "|FR| GENERALISTES"),
        LiveCategory("3", "UK | SPORTS"),
        LiveCategory("4", "DE | BUNDESLIGA"),
        LiveCategory("5", "BELGIQUE"),
    )

    private fun stream(id: Long, name: String, epg: String?, cat: String? = null) = LiveStream(id, name, epg, cat)

    // ------------------------------------------------------------------ diffuseurs

    @Test
    fun `table de diffuseurs - lignes invalides et commentaires ignores`() {
        val t = Broadcasters.parse("# commentaire\nFR | = | CANAL+ FOOT\nFR | ? | X\nmauvaise ligne\n\nUK | ~ | SKY SPORTS\n")
        assertEquals(listOf("CANAL+FOOT", "SKYSPORTS"), t.map { it.compact })
        assertTrue(Broadcasters.entries.size > 50)
        assertTrue(Broadcasters.entries.all { it.country == "*" || it.country in Broadcasters.PRIORITY_COUNTRIES + Broadcasters.SECONDARY_COUNTRIES })
    }

    @Test
    fun `nom de chaine sans pays ni qualite`() {
        assertEquals("CANAL+FOOT", Broadcasters.channelKey("|FR| CANAL+ FOOT FHD"))
        assertEquals("CANAL+FOOT", Broadcasters.channelKey("FR: Canal+ Foot ᴴᴰ"))
        assertEquals("SKYSPORTSMAINEVENT", Broadcasters.channelKey("UK | Sky Sports Main Event HD"))
        assertEquals("LIGUE1+", Broadcasters.channelKey("|FR| LIGUE 1+ 4K"))
        assertEquals("FR", Broadcasters.countryOf("FR: beIN Sports 1", null))
        assertEquals("UK", Broadcasters.countryOf("Sky Sports Main Event", "UNITED KINGDOM SPORTS"))
        assertEquals("DE", Broadcasters.countryOf("|DE| Sky Sport Bundesliga 1", null))
        assertNull(Broadcasters.countryOf("ESPN", null))
    }

    @Test
    fun `priorisation - principales FR UK BE CH, principales autres championnats, variantes, puis le reste`() {
        val streams = listOf(
            stream(1, "|FR| CANAL+ LIVE 5 FHD", "cplive5.fr"), // variante FR
            stream(2, "|DE| Sky Sport Bundesliga 1 HD", "skybl1.de"), // championnat secondaire
            stream(3, "|FR| TF1 FHD", "tf1.fr", "2"), // pas un diffuseur liste
            stream(4, "|CH| Blue Sport 1", "blue1.ch"),
            stream(5, "|PL| CANAL+ SPORT", "cpsport.pl"), // autre pays : seulement les lignes `*`
            stream(6, "UK: Sky Sports Premier League", "skypl.uk"),
            stream(7, "|FR| CANAL+ FOOT FHD", "CanalPlusFoot.fr"),
            stream(8, "|FR| CANAL+ FOOT HD", "canalplusfoot.fr"), // meme chaine EPG : dedoublonnee
            stream(9, "Play Sports 1", "play1.be", "5"),
            stream(10, "|FR| BEIN SPORTS 1", null), // sans epg : impossible a relier
            stream(11, "|IT| Sky Sport Calcio", "calcio.it"),
            stream(12, "|ES| Movistar LaLiga", "mlaliga.es"),
        )
        val selected = Broadcasters.select(streams, categories)
        assertEquals(listOf(7L, 6L, 9L, 4L, 2L, 11L, 12L, 1L, 5L), selected.map { it.streamId })
    }

    // ------------------------------------------------------------------ repli

    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val target = Instant.parse("2026-10-23T20:00:00Z") // J+1 22:00 a Paris
    private val filter = ProgrammeFilter(
        Instant.parse("2026-10-22T00:00:00Z"),
        Instant.parse("2026-10-24T03:00:00Z"),
        listOf("bayern", "bayern munich", "arsenal"),
    )

    private fun candidates(n: Int) = (1..n).map { stream(it.toLong(), "|FR| CANAL+ LIVE $it", "ch$it.fr") }

    /** Guide de 2 jours : couvre la cible. */
    private fun fullGuide(title: String = "Bayern Munich / Arsenal") = (0 until 24).map { i ->
        val start = now.plus(Duration.ofHours(2L * i))
        EpgListing("x", start, start.plus(Duration.ofHours(2)), if (i == 3) title else "Magazine $i", "")
    }

    private class FakeSource(
        val simple: (LiveStream) -> List<EpgListing>,
        val short: (LiveStream) -> List<EpgListing> = { emptyList() },
    ) : ChannelEpgFallback.Source {
        val simpleCalls = AtomicInteger()
        val shortCalls = AtomicInteger()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        private fun <T> track(block: () -> T): T {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                return block()
            } finally {
                inFlight.decrementAndGet()
            }
        }
        override fun simpleDataTable(stream: LiveStream) = track { simpleCalls.incrementAndGet(); simple(stream) }
        override fun shortEpg(stream: LiveStream, limit: Int) = track { shortCalls.incrementAndGet(); short(stream) }
        val total get() = simpleCalls.get() + shortCalls.get()
    }

    private val fast = ChannelEpgFallback.Config(pauseMs = 0)

    private suspend fun ChannelEpgFallback.go(
        source: FakeSource,
        state: ChannelEpgState = ChannelEpgState(),
        streams: List<LiveStream> = candidates(200),
        at: Instant = now,
        manual: Boolean = false,
    ) = run(state, streams, filter, at, target, manual, source)

    @Test
    fun `budget dur jamais depasse, une requete a la fois`() = runBlocking {
        // get_simple_data_table supporte.
        val ok = FakeSource({ fullGuide() })
        val r1 = ChannelEpgFallback(fast).go(ok)
        assertEquals(60, r1.requests)
        assertEquals(60, ok.total)
        assertEquals(1, ok.maxInFlight.get())
        assertEquals(true, r1.state.simpleDataTable)
        // Non supporte : bascule sur get_short_epg, toujours dans le budget.
        val unsupported = FakeSource({ throw XtreamException(Kind.INVALID_RESPONSE) }, { fullGuide() })
        val r2 = ChannelEpgFallback(fast).go(unsupported)
        assertEquals(60, unsupported.total)
        assertEquals(1, unsupported.simpleCalls.get())
        assertEquals(false, r2.state.simpleDataTable)
        // Support inconnu et listes vides : 2 requetes par chaine, budget respecte quand meme.
        val empty = FakeSource({ emptyList() }, { emptyList() })
        val r3 = ChannelEpgFallback(fast.copy(maxRequests = 7)).go(empty)
        assertEquals(7, empty.total)
        assertEquals(7, r3.requests)
        assertEquals(0, r3.listingsTotal)
    }

    @Test
    fun `arret au premier 429 avec Retry-After, date pas-avant persistante`() = runBlocking {
        val calls = AtomicInteger()
        val src = FakeSource({ if (calls.incrementAndGet() == 3) throw XtreamException(Kind.HTTP, 429, 120) else fullGuide() })
        val r = ChannelEpgFallback(fast).go(src)
        assertEquals(3, src.total)
        assertEquals(StopReason.RATE_LIMITED, r.stopReason)
        assertEquals(now.plusSeconds(120), r.retryAt)
        assertEquals(2, r.channelsFetched) // on garde ce qui a ete recupere
        assertTrue(r.data.programmes.isNotEmpty())

        // Etat persiste puis relu : aucune requete avant la date, meme sur demande manuelle.
        val reloaded = ChannelEpgState.decode(ChannelEpgState.encode(r.state))
        assertEquals(now.plusSeconds(120), reloaded.notBefore())
        val again = FakeSource({ fullGuide() })
        val blocked = ChannelEpgFallback(fast).go(again, reloaded, at = now.plusSeconds(60), manual = true)
        assertEquals(0, again.total)
        assertEquals(Skip.NOT_BEFORE, blocked.skipped)
        assertEquals(now.plusSeconds(120), blocked.retryAt)
        assertTrue(blocked.data.programmes.isNotEmpty(), "le cache reste utilise pendant la limitation")
    }

    @Test
    fun `429 sans Retry-After - 1 h minimum`() = runBlocking {
        val src = FakeSource({ throw XtreamException(Kind.HTTP, 429) })
        val r = ChannelEpgFallback(fast).go(src)
        assertEquals(1, src.total)
        assertEquals(now.plus(Duration.ofHours(1)), r.retryAt)
        assertEquals(0, r.listingsTotal)
    }

    @Test
    fun `cache par chaine reutilise, seules les chaines non couvertes sont reinterrogees`() = runBlocking {
        val streams = candidates(10)
        val first = FakeSource({ s -> if (s.streamId <= 7) fullGuide() else fullGuide().take(3) }) // 8..10 : guide court
        val r1 = ChannelEpgFallback(fast).go(first, streams = streams)
        assertEquals(10, first.total)

        // 1 h plus tard, demande manuelle : tout vient du cache (les guides courts ont ete recuperes il y a < 6 h).
        val second = FakeSource({ fullGuide() })
        val r2 = ChannelEpgFallback(fast).go(second, r1.state, streams, at = now.plus(Duration.ofHours(1)), manual = true)
        assertEquals(0, second.total)
        assertEquals(Skip.NOTHING_TO_FETCH, r2.skipped)
        assertEquals(7, r2.data.programmes.size) // "Bayern Munich / Arsenal" des 7 guides complets

        // 7 h plus tard : seules les 3 chaines au guide court sont reinterrogees.
        val third = FakeSource({ fullGuide() })
        val r3 = ChannelEpgFallback(fast).go(third, r2.state, streams, at = now.plus(Duration.ofHours(7)))
        assertEquals(3, third.total)
        assertEquals(10, r3.state.channels.size)

        // Au-dela de 48 h, le cache expire : les 7 chaines recuperees au debut sont reinterrogees.
        val later = FakeSource({ fullGuide() })
        ChannelEpgFallback(fast).go(later, r3.state, streams, at = now.plus(Duration.ofHours(50)))
        assertEquals(7, later.total)
    }

    @Test
    fun `repli automatique au plus une fois toutes les 6 h, la demande manuelle passe`() = runBlocking {
        val streams = candidates(5)
        val r1 = ChannelEpgFallback(fast).go(FakeSource({ throw XtreamException(Kind.TIMEOUT) }), streams = streams)
        assertEquals(now.toEpochMilli(), r1.state.lastFetchMillis)
        val auto = FakeSource({ fullGuide() })
        val r2 = ChannelEpgFallback(fast).go(auto, r1.state, streams, at = now.plus(Duration.ofHours(4)))
        assertEquals(0, auto.total)
        assertEquals(Skip.TOO_SOON, r2.skipped)
        val manual = FakeSource({ fullGuide() })
        ChannelEpgFallback(fast).go(manual, r1.state, streams, at = now.plus(Duration.ofHours(4)), manual = true)
        assertEquals(5, manual.total)
    }

    @Test
    fun `arret apres 2 refus d acces`() = runBlocking {
        val src = FakeSource({ s -> if (s.streamId <= 2) fullGuide() else throw XtreamException(Kind.AUTH, 403) })
        val r = ChannelEpgFallback(fast).go(src)
        assertEquals(StopReason.AUTH, r.stopReason)
        assertEquals(4, src.total)
        assertEquals(2, r.channelsFetched)
    }

    @Test
    fun `annulation respectee (deconnexion)`() = runBlocking {
        val calls = AtomicInteger()
        val job = async(Dispatchers.Default) {
            ChannelEpgFallback(fast.copy(maxRequests = 1000, pauseMs = 1)).go(
                FakeSource({ calls.incrementAndGet(); Thread.sleep(5); fullGuide() }),
                streams = candidates(1000),
            )
        }
        while (calls.get() < 5) delay(2)
        job.cancel()
        assertThrows<CancellationException> { runBlocking { job.await() } }
        val after = calls.get()
        delay(100)
        assertEquals(after, calls.get(), "aucune requete apres l'annulation")
        assertTrue(after < 100)
    }

    // ------------------------------------------------------------------ integration MockWebServer

    private fun sample() = javaClass.getResourceAsStream("/short_epg_sample.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun source(client: XtreamClient, account: XtreamAccount) = object : ChannelEpgFallback.Source {
        override fun simpleDataTable(stream: LiveStream) = client.getSimpleDataTable(account, stream.streamId)
        override fun shortEpg(stream: LiveStream, limit: Int) = client.getShortEpg(account, stream.streamId, limit)
    }

    @Test
    fun `xmltv vide - repli chaine par chaine - match en direct detecte`() = runBlocking {
        val apiCalls = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.url
                    return when {
                        url.encodedPath == "/xmltv.php" -> MockResponse.Builder().body("\n").build()
                        url.queryParameter("action") == "get_simple_data_table" -> {
                            apiCalls.incrementAndGet()
                            if (url.queryParameter("stream_id") == "101") {
                                MockResponse.Builder().body(sample()).build()
                            } else {
                                MockResponse.Builder().body("""{"epg_listings":[]}""").build()
                            }
                        }
                        else -> {
                            apiCalls.incrementAndGet()
                            MockResponse.Builder().body("[]").build()
                        }
                    }
                }
            }
            server.start()
            val client = XtreamClient()
            val account = XtreamAccount.create(server.url("/").toString(), "u", "p")!!
            assertEquals(1L, client.downloadEpg(account, File(tmp, "epg.xml"))) // fournisseur sans guide XMLTV

            val streams = listOf(
                stream(101, "|FR| CANAL+ FOOT FHD", "CanalPlusFoot.fr", "1"),
                stream(102, "|FR| CANAL+ FOOT HD", "CanalPlusFoot.fr", "1"),
                stream(103, "|FR| TF1 FHD", "TF1.fr", "2"),
            )
            val fixtures = TestAliases.fixtures(
                TestAliases.fixture(1, "CL", "2026-10-22T11:15:00Z", TestAliases.team("FC Bayern München", "Bayern"), TestAliases.team("Arsenal FC", "Arsenal")),
                from = "2026-10-22T00:00:00Z",
                to = "2026-10-23T03:00:00Z",
            )
            val pipeline = MatchPipeline(XmltvParser(kxmlFactory))
            val at = Instant.parse("2026-10-22T11:30:00Z")
            val result = ChannelEpgFallback(fast).run(
                ChannelEpgState(), Broadcasters.select(streams, categories), pipeline.filterFor(fixtures), at,
                Instant.parse("2026-10-23T20:00:00Z"), manual = false, source = source(client, account),
            )
            val report = pipeline.runOnProgrammes(fixtures, result.data, streams, categories, at, result.elapsedMillis, result.requests, result.channelsFailed)

            assertEquals(1, apiCalls.get()) // CANAL+ FOOT interrogee une seule fois, TF1 jamais
            assertEquals(true, result.state.simpleDataTable)
            assertEquals(EpgSource.CHANNELS, report.epgSource)
            val channel = report.matches.single().channels.single()
            assertEquals("|FR| CANAL+ FOOT", channel.name)
            assertEquals(Score.FORT, channel.score)
            assertEquals(setOf(101L, 102L), channel.variants.map { it.streamId }.toSet())
        }
    }

    @Test
    fun `HTTP 429 reel - Retry-After lu, une seule requete`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "600").build())
            server.start()
            val client = XtreamClient()
            val account = XtreamAccount.create(server.url("/").toString(), "u", "p")!!
            val r = ChannelEpgFallback(fast).go(FakeSourceAdapter(source(client, account)), streams = candidates(30))
            assertEquals(1, server.requestCount)
            assertEquals(StopReason.RATE_LIMITED, r.stopReason)
            assertEquals(now.plusSeconds(600), r.retryAt)
            assertFalse(r.state.notBeforeMillis == 0L)
        }
    }

    /** Adapte une vraie source au type attendu par [go]. */
    private fun FakeSourceAdapter(real: ChannelEpgFallback.Source) =
        FakeSource({ real.simpleDataTable(it) }, { real.shortEpg(it, 150) })
}
