package fr.footballrama.core

import fr.footballrama.core.epg.XmltvParser
import fr.footballrama.core.fixtures.Fixtures
import fr.footballrama.core.matching.MatchPipeline
import fr.footballrama.core.matching.Score
import fr.footballrama.core.xtream.XtreamAccount
import fr.footballrama.core.xtream.XtreamClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Test d'integration sur les donnees reelles locales (cache/, jamais committe). Ignore si cache/epg.xml absent.
 * Calendrier football-data converti au format du contrat (fenetre J/J+1 du 2026-10-20) ;
 * streams/categories servis par MockWebServer pour passer par le vrai XtreamClient. Aucun appel reseau externe.
 */
@Tag("integration")
class RealEpgIntegrationTest {

    private val cacheDir = File(System.getProperty("footballrama.cacheDir") ?: "../../cache")

    @Test
    fun `EPG reel - matchs du 20 et 21 octobre`() {
        val epg = File(cacheDir, "epg.xml")
        val fx = File(cacheDir, "fixtures_2026-10-20_7.json")
        assumeTrue(epg.isFile && fx.isFile && epg.length() > 1_000_000, "cache/epg.xml absent ou vide (fournisseur sans guide) : test d integration ignore")

        val fixtures = convertFootballData(fx.readText())
        val (categories, streams) = MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(Buffer().write(File(cacheDir, "live_categories.json").readBytes())).build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(File(cacheDir, "live_streams.json").readBytes())).build())
            val client = XtreamClient()
            val account = XtreamAccount.create(server.url("/").toString(), "test", "test")!!
            client.getLiveCategories(account) to client.getLiveStreams(account)
        }

        val rt = Runtime.getRuntime()
        System.gc()
        val baseline = rt.totalMemory() - rt.freeMemory()
        val peak = AtomicLong(baseline)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), ::maxOf)
                Thread.sleep(5)
            }
        }.apply { isDaemon = true; start() }

        val t0 = System.nanoTime()
        val report = epg.inputStream().buffered(1 shl 16).use {
            MatchPipeline(XmltvParser(kxmlFactory)).run(fixtures, it, streams, categories, Instant.parse("2026-10-20T08:00:00Z"))
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        running.set(false)
        sampler.join()
        System.gc()
        val retained = rt.totalMemory() - rt.freeMemory() - baseline

        println("=== Integration EPG reel (${epg.length() / 1_000_000} Mo) ===")
        println("streams=${streams.size} categories=${categories.size}")
        println("parsing EPG=${report.stats.parseMillis} ms, pipeline total=$totalMs ms")
        println("programmes dans fenetre=${report.stats.programmesInWindow}, gardes apres prefiltre=${report.stats.programmesKept}")
        println("pic heap pendant parsing ~ ${(peak.get() - baseline) / 1_048_576} Mo au-dessus de la base, retenu apres GC ~ ${retained / 1_048_576} Mo")
        for (m in report.matches) {
            val forts = m.channels.count { it.score == Score.FORT }
            println("  ${Instant.ofEpochMilli(m.kickoffUtcMillis)} ${m.home} - ${m.away} : ${m.channels.size} chaines ($forts fortes) " +
                m.channels.filter { "CANAL+" in it.name }.take(3).map { it.name })
        }

        assertEquals(18, fixtures.matches.size)
        assertEquals(18, report.stats.matchesWithChannels, "le prototype trouvait 18/18")
        fun channelsOf(home: String) = report.matches.first { it.home.startsWith(home) }.channels.map { it.name }
        assertTrue("|FR| CANAL+ LIVE 2" in channelsOf("Fenerbah"))
        assertTrue("|FR| CANAL+ LIVE 1" in channelsOf("Paris Saint-Germain"))
        assertTrue("|FR| CANAL+ LIVE 2" in channelsOf("Liverpool"))
        assertTrue(report.matches.all { m -> m.channels.isEmpty() || m.channels.first().score == Score.FORT })
    }

    /** football-data v4 -> calendrier du contrat (meme logique d'alias que le publisher). */
    private fun convertFootballData(text: String): Fixtures {
        val from = Instant.parse("2026-10-20T00:00:00Z")
        val to = Instant.parse("2026-10-22T03:00:00Z")
        val matches = Json.parseToJsonElement(text).jsonObject["matches"]!!.jsonArray.map { it.jsonObject }
            .filter { it["competition"]!!.jsonObject["code"]!!.jsonPrimitive.content in TestAliases.COMPETITIONS }
            .map { m ->
                fun team(o: JsonObject) = TestAliases.team(o["name"]!!.jsonPrimitive.content, o["shortName"]?.jsonPrimitive?.content)
                TestAliases.fixture(
                    m["id"]!!.jsonPrimitive.long,
                    m["competition"]!!.jsonObject["code"]!!.jsonPrimitive.content,
                    m["utcDate"]!!.jsonPrimitive.content,
                    team(m["homeTeam"]!!.jsonObject),
                    team(m["awayTeam"]!!.jsonObject),
                )
            }
            .filter { !it.kickoff.isBefore(from) && !it.kickoff.isAfter(to) }
        return TestAliases.fixtures(*matches.toTypedArray())
    }
}
