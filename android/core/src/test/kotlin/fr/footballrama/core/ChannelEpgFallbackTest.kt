package fr.footballrama.core

import fr.footballrama.core.epg.ChannelEpgFallback
import fr.footballrama.core.epg.ChannelEpgFallback.StopReason
import fr.footballrama.core.epg.ProgrammeFilter
import fr.footballrama.core.epg.SportChannels
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
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class ChannelEpgFallbackTest {

    @TempDir
    lateinit var tmp: File

    private val categories = listOf(
        LiveCategory("1", "|FR| SPORTS"),
        LiveCategory("2", "|FR| GENERALISTES"),
        LiveCategory("3", "UK | SKY"),
        LiveCategory("4", "DE | BUNDESLIGA"),
        LiveCategory("5", "BELGIQUE"),
        LiveCategory("6", "FR: CINEMA"),
    )

    private fun stream(id: Long, name: String, epg: String?, cat: String?) = LiveStream(id, name, epg, cat)

    // ------------------------------------------------------------------ selection

    @Test
    fun `mots-cles sport sans regex, sur le nom de flux ou de categorie`() {
        assertTrue(SportChannels.isSport("|FR| CANAL+ FOOT FHD", null))
        assertTrue(SportChannels.isSport("|FR| Canal+ Cinéma", null)) // CANAL+ (bouquet) : garde
        assertTrue(SportChannels.isSport("beIN Sports 1", null))
        assertTrue(SportChannels.isSport("UK: Sky Sports Premier League", null))
        assertTrue(SportChannels.isSport("TNT Sports 1", null))
        assertTrue(SportChannels.isSport("L'Équipe", null))
        assertTrue(SportChannels.isSport("Chaine 12", "|FR| SPORTS"))
        assertTrue(SportChannels.isSport("PL: Polsat Sport", null))
        assertFalse(SportChannels.isSport("|FR| TF1 FHD", "|FR| GENERALISTES"))
        assertFalse(SportChannels.isSport("|FR| CANAL J", "FR: JEUNESSE")) // "CANAL" seul n'est pas "CANAL+"
    }

    @Test
    fun `rang pays FR, UK, BE, CH puis autres`() {
        assertEquals(0, SportChannels.countryRank("|FR| RMC SPORT 1", null))
        assertEquals(0, SportChannels.countryRank("FR: beIN Sports 1", null))
        assertEquals(1, SportChannels.countryRank("UK: Sky Sports Main Event", null))
        assertEquals(1, SportChannels.countryRank("Sky Sports Main Event", "UNITED KINGDOM SPORTS"))
        assertEquals(2, SportChannels.countryRank("Play Sports 1", "BELGIQUE"))
        assertEquals(3, SportChannels.countryRank("|CH| Blue Sport 1", null))
        assertEquals(4, SportChannels.countryRank("|DE| Sky Sport Bundesliga", "DE | BUNDESLIGA"))
        assertEquals(4, SportChannels.countryRank("ESPN", null))
    }

    @Test
    fun `selection - sport uniquement, dedoublonnee par epg_channel_id, sans epg ignoree`() {
        val streams = listOf(
            stream(1, "|FR| CANAL+ FOOT FHD", "CanalPlusFoot.fr", "1"),
            stream(2, "|FR| CANAL+ FOOT HD", "canalplusfoot.fr", "1"), // meme chaine EPG (casse differente)
            stream(3, "|FR| TF1 FHD", "TF1.fr", "2"),
            stream(4, "|FR| RMC SPORT 1", null, "1"), // sans epg : impossible a relier
            stream(5, "|FR| RMC SPORT 2", "  ", "1"),
            stream(6, "|DE| Sky Sport Bundesliga 1", "SkySportBundesliga1.de", "4"),
            stream(7, "UK: Sky Sports Premier League", "SkySportsPL.uk", "3"),
        )
        val selected = SportChannels.select(streams, categories)
        assertEquals(listOf(1L, 7L, 6L), selected.map { it.streamId })
    }

    @Test
    fun `plafond dur et priorisation des pays`() {
        val streams = ArrayList<LiveStream>()
        var id = 0L
        repeat(400) { streams.add(stream(++id, "|DE| Sky Sport $it", "de$it", "4")) }
        repeat(300) { streams.add(stream(++id, "|FR| beIN SPORTS $it", "fr$it", "1")) }
        repeat(100) { streams.add(stream(++id, "UK: Sky Sports $it", "uk$it", "3")) }
        repeat(50) { streams.add(stream(++id, "Play Sports $it", "be$it", "5")) }
        repeat(50) { streams.add(stream(++id, "|CH| Blue Sport $it", "ch$it", null)) }
        val selected = SportChannels.select(streams, categories, max = 600)
        assertEquals(600, selected.size)
        val ranks = selected.map { SportChannels.countryRank(it.name, categories.firstOrNull { c -> c.id == it.categoryId }?.name) }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(300, ranks.count { it == 0 })
        assertEquals(100, ranks.count { it == 1 })
        assertEquals(50, ranks.count { it == 2 })
        assertEquals(50, ranks.count { it == 3 })
        assertEquals(100, ranks.count { it == 4 }) // les autres pays completent jusqu'au plafond
        assertEquals(selected.size, selected.map { it.epgChannelId }.toSet().size)
    }

    // ------------------------------------------------------------------ recuperation

    private val filter = ProgrammeFilter(
        Instant.parse("2026-10-22T00:00:00Z"),
        Instant.parse("2026-10-23T03:00:00Z"),
        listOf("bayern", "bayern munich", "arsenal"),
    )

    private fun streams(n: Int) = (1..n).map { stream(it.toLong(), "|FR| SPORT $it", "ch$it.fr", "1") }

    private fun listing(title: String, start: String) =
        EpgListing("x", Instant.parse(start), Instant.parse(start).plusSeconds(6300), title, "")

    private val fast = ChannelEpgFallback.Config(pauseMs = 1)

    @Test
    fun `concurrence maximale respectee`() = runBlocking {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        val fallback = ChannelEpgFallback(fast.copy(concurrency = 2), Dispatchers.IO)
        val result = fallback.fetch(streams(30), filter, { _ ->
            val n = inFlight.incrementAndGet()
            maxSeen.accumulateAndGet(n, ::maxOf)
            Thread.sleep(15)
            inFlight.decrementAndGet()
            emptyList()
        })
        assertEquals(30, result.channelsQueried)
        assertEquals(30, result.channelsSucceeded)
        assertEquals(2, maxSeen.get())
        assertNull(result.stopReason)
    }

    @Test
    fun `conversion au modele XMLTV avec le meme prefiltre`() = runBlocking {
        val progress = Collections.synchronizedList(ArrayList<Int>())
        val result = ChannelEpgFallback(fast).fetch(streams(2), filter, { s ->
            if (s.streamId == 1L) {
                listOf(
                    listing("Bayern Munich / Arsenal", "2026-10-22T19:00:00Z"),
                    listing("Journal", "2026-10-22T21:00:00Z"), // hors termes
                    listing("Arsenal / Bayern (hier)", "2026-10-20T19:00:00Z"), // hors fenetre
                )
            } else {
                emptyList()
            }
        }) { done, _ -> progress.add(done) }
        val p = result.data.programmes.single()
        assertEquals("ch1.fr", p.channelId) // epg_channel_id du flux, en minuscules
        assertEquals("bayern munich arsenal", p.text)
        assertEquals(3, result.listingsTotal)
        assertEquals(2, result.data.stats.programmesInWindow)
        assertEquals(listOf(1, 2), progress.sorted())
    }

    @Test
    fun `arret apres 2 refus d acces, on garde ce qui a ete recupere`() = runBlocking {
        val calls = AtomicInteger()
        val result = ChannelEpgFallback(fast.copy(concurrency = 1)).fetch(streams(50), filter, { s ->
            calls.incrementAndGet()
            if (s.streamId <= 3) listOf(listing("Bayern Munich / Arsenal", "2026-10-22T19:00:00Z")) else throw XtreamException(Kind.AUTH, 403)
        })
        assertEquals(StopReason.AUTH, result.stopReason)
        assertEquals(5, calls.get())
        assertEquals(3, result.channelsSucceeded)
        assertEquals(3, result.data.programmes.size)
    }

    @Test
    fun `arret sur 429 ou 5xx repetes, erreurs isolees tolerees`() = runBlocking {
        val calls = AtomicInteger()
        val result = ChannelEpgFallback(fast.copy(concurrency = 1, maxPauseMs = 20)).fetch(streams(50), filter, { s ->
            calls.incrementAndGet()
            when (s.streamId) {
                2L -> throw XtreamException(Kind.HTTP, 404) // chaine sans guide : ignoree
                4L, 6L -> throw XtreamException(Kind.HTTP, 429)
                8L -> throw XtreamException(Kind.SERVER, 503)
                else -> emptyList()
            }
        })
        assertEquals(StopReason.THROTTLED, result.stopReason)
        assertEquals(8, calls.get())
        assertEquals(4, result.channelsFailed)
    }

    @Test
    fun `arret si l action n est pas supportee`() = runBlocking {
        val result = ChannelEpgFallback(fast).fetch(streams(100), filter, { throw XtreamException(Kind.INVALID_RESPONSE) })
        assertEquals(StopReason.UNSUPPORTED, result.stopReason)
        assertTrue(result.channelsQueried < 12, "${result.channelsQueried}")
        assertEquals(Kind.INVALID_RESPONSE, result.lastError)
    }

    @Test
    fun `annulation respectee (deconnexion)`() = runBlocking {
        val calls = AtomicInteger()
        val job = async(Dispatchers.Default) {
            ChannelEpgFallback(fast).fetch(streams(1000), filter, { _ ->
                calls.incrementAndGet()
                Thread.sleep(5)
                emptyList()
            })
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

    @Test
    fun `xmltv vide - repli chaine par chaine - match en direct detecte`() = runBlocking {
        val shortEpgCalls = AtomicInteger()
        val sample = javaClass.getResourceAsStream("/short_epg_sample.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.url
                    return when {
                        url.encodedPath == "/xmltv.php" -> MockResponse.Builder().body("\n").build()
                        url.queryParameter("action") == "get_short_epg" -> {
                            shortEpgCalls.incrementAndGet()
                            if (url.queryParameter("stream_id") == "101") {
                                MockResponse.Builder().body(sample).build()
                            } else {
                                MockResponse.Builder().body("""{"epg_listings":[]}""").build()
                            }
                        }
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
            server.start()
            val client = XtreamClient()
            val account = XtreamAccount.create(server.url("/").toString(), "u", "p")!!
            val epgFile = File(tmp, "epg.xml")
            assertEquals(1L, client.downloadEpg(account, epgFile)) // fournisseur sans guide XMLTV

            val streams = listOf(
                stream(101, "|FR| CANAL+ FOOT FHD", "CanalPlusFoot.fr", "1"),
                stream(102, "|FR| CANAL+ FOOT HD", "CanalPlusFoot.fr", "1"),
                stream(103, "|FR| TF1 FHD", "TF1.fr", "2"),
                stream(104, "UK: Sky Sports Premier League", "SkySportsPL.uk", "3"),
            )
            val fixtures = TestAliases.fixtures(
                TestAliases.fixture(1, "CL", "2026-10-22T11:15:00Z", TestAliases.team("FC Bayern München", "Bayern"), TestAliases.team("Arsenal FC", "Arsenal")),
                from = "2026-10-22T00:00:00Z",
                to = "2026-10-23T03:00:00Z",
            )
            val pipeline = MatchPipeline(XmltvParser(kxmlFactory))
            val fallback = ChannelEpgFallback(fast)
            val selected = SportChannels.select(streams, categories)
            val result = fallback.fetch(selected, pipeline.filterFor(fixtures), { s -> client.getShortEpg(account, s.streamId, fallback.limit) })
            val report = pipeline.runOnProgrammes(
                fixtures, result.data, streams, categories, Instant.parse("2026-10-22T11:30:00Z"),
                result.elapsedMillis, result.channelsQueried, result.channelsFailed,
            )

            assertEquals(2, shortEpgCalls.get()) // CANAL+ FOOT (une seule fois) + Sky Sports ; TF1 non interrogee
            assertEquals(EpgSource.CHANNELS, report.epgSource)
            assertEquals(2, report.stats.epgChannelsQueried)
            val match = report.matches.single()
            val channel = match.channels.single()
            assertEquals("|FR| CANAL+ FOOT", channel.name)
            assertEquals(Score.FORT, channel.score)
            assertEquals(setOf(101L, 102L), channel.variants.map { it.streamId }.toSet())
        }
    }
}
