package fr.footballrama.core.matching

import fr.footballrama.core.epg.EpgData
import fr.footballrama.core.epg.ProgrammeFilter
import fr.footballrama.core.epg.XmltvParser
import fr.footballrama.core.fixtures.Fixtures
import fr.footballrama.core.xtream.LiveCategory
import fr.footballrama.core.xtream.LiveStream
import java.io.InputStream
import java.time.Instant

/** EPG (flux) + calendrier verifie + flux Xtream -> [MatchReport]. Partage par l'app et les tests JVM. */
class MatchPipeline(private val parser: XmltvParser) {

    fun run(
        fixtures: Fixtures,
        epg: InputStream,
        streams: List<LiveStream>,
        categories: List<LiveCategory>,
        now: Instant,
    ): MatchReport {
        val t0 = System.nanoTime()
        val data = parser.parse(epg, filterFor(fixtures))
        val parseMillis = (System.nanoTime() - t0) / 1_000_000
        return build(fixtures, data, streams, categories, now, parseMillis, EpgSource.XMLTV)
    }

    /** Programmes deja prefiltres par [filterFor] (repli chaine par chaine, voir ChannelEpgFallback). */
    fun runOnProgrammes(
        fixtures: Fixtures,
        data: EpgData,
        streams: List<LiveStream>,
        categories: List<LiveCategory>,
        now: Instant,
        elapsedMillis: Long,
        channelsQueried: Int,
        channelsFailed: Int,
    ): MatchReport = build(fixtures, data, streams, categories, now, elapsedMillis, EpgSource.CHANNELS)
        .let { it.copy(stats = it.stats.copy(epgChannelsQueried = channelsQueried, epgChannelsFailed = channelsFailed)) }

    /** Prefiltre commun aux deux sources (fenetre du calendrier + alias/tokens d'equipes). */
    fun filterFor(fixtures: Fixtures) = ProgrammeFilter(fixtures.windowFrom, fixtures.windowTo, fixtures.allTeamTerms)

    private fun build(
        fixtures: Fixtures,
        data: EpgData,
        streams: List<LiveStream>,
        categories: List<LiveCategory>,
        now: Instant,
        parseMillis: Long,
        source: EpgSource,
    ): MatchReport {
        val matcher = Matcher(fixtures)
        val results = matcher.buildResults(matcher.findHits(data.programmes), streams, categories)
        return MatchReport(
            updatedAtMillis = now.toEpochMilli(),
            fixturesGeneratedAtMillis = fixtures.generatedAt.toEpochMilli(),
            matches = results,
            stats = ReportStats(
                liveStreams = streams.size,
                programmesInWindow = data.stats.programmesInWindow,
                programmesKept = data.stats.programmesKept,
                matchesWithChannels = results.count { it.channels.isNotEmpty() },
                parseMillis = parseMillis,
            ),
            epgSource = source,
        )
    }
}
