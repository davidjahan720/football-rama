package fr.footballrama.core

import fr.footballrama.core.epg.SportChannels
import fr.footballrama.core.xtream.XtreamAccount
import fr.footballrama.core.xtream.XtreamClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Selection du repli chaine par chaine sur la liste reelle de l'utilisateur (cache/, jamais committe).
 * N'affiche que des comptes (aucun nom de chaine). Ignore si le cache est absent.
 */
@Tag("integration")
class RealChannelSelectionTest {

    private val cacheDir = File(System.getProperty("footballrama.cacheDir") ?: "../../cache")

    @Test
    fun `nombre de requetes du repli sur la liste reelle`() {
        val cats = File(cacheDir, "live_categories.json")
        val streamsFile = File(cacheDir, "live_streams.json")
        assumeTrue(cats.isFile && streamsFile.isFile, "cache absent : test ignore")
        val (categories, streams) = MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(Buffer().write(cats.readBytes())).build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(streamsFile.readBytes())).build())
            val client = XtreamClient()
            val account = XtreamAccount.create(server.url("/").toString(), "test", "test")!!
            client.getLiveCategories(account) to client.getLiveStreams(account)
        }
        val catName = categories.associate { it.id to it.name }
        val withEpg = streams.filter { !it.epgChannelId.isNullOrBlank() }
        val sport = withEpg.filter { SportChannels.isSport(it.name, it.categoryId?.let(catName::get)) }
        val uniqueSport = sport.map { it.epgChannelId!!.lowercase() }.toSet().size
        val selected = SportChannels.select(streams, categories)
        val byRank = selected.groupingBy { SportChannels.countryRank(it.name, it.categoryId?.let(catName::get)) }.eachCount().toSortedMap()
        println("=== Repli chaine par chaine (liste reelle) ===")
        println("flux=${streams.size} categories=${categories.size} flux avec epg_channel_id=${withEpg.size}")
        println("flux sport=${sport.size} chaines EPG sport distinctes=$uniqueSport requetes (plafond ${SportChannels.DEFAULT_MAX_CHANNELS})=${selected.size}")
        println("requetes par rang pays (0=FR,1=UK,2=BE,3=CH,4=autres)=$byRank")
        assertTrue(selected.size <= SportChannels.DEFAULT_MAX_CHANNELS)
    }
}
