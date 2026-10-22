package fr.footballrama.core

import fr.footballrama.core.epg.Broadcasters
import fr.footballrama.core.epg.ChannelEpgFallback
import fr.footballrama.core.xtream.XtreamAccount
import fr.footballrama.core.xtream.XtreamClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
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
        val selected = Broadcasters.select(streams, categories)
        val budget = ChannelEpgFallback.Config().maxRequests
        val tiers = selected.map { Broadcasters.rank(it, it.categoryId?.let(catName::get))!! / 1_000_000 }
        val inBudget = tiers.take(budget)
        println("=== Repli chaine par chaine (liste reelle) ===")
        println("flux=${streams.size} chaines EPG candidates (diffuseurs listes, dedoublonnees)=${selected.size} budget=$budget")
        println("candidates par niveau (0=principales FR/UK/BE/CH, 1=principales DE/IT/ES/NL/PT, 2=variantes FR/UK/BE/CH, 3-4=pays inconnu, 5=variantes DE/IT/ES/NL/PT, 6-7=autres)=" +
            tiers.groupingBy { it }.eachCount().toSortedMap())
        println("dans le budget, par niveau=" + inBudget.groupingBy { it }.eachCount().toSortedMap())
        // Lignes principales de la table trouvees dans la liste (noms issus de la table, pas de la liste).
        val mains = Broadcasters.entries.filter { it.exact && it.country != "*" }
        val found = mains.filter { e -> selected.any { Broadcasters.channelKey(it.name) == e.compact } }
        println("lignes principales de la table presentes=${found.size}/${mains.size} : " + found.joinToString { "${it.country} ${it.name}" })
    }
}
