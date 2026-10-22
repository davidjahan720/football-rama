package fr.footballrama.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.footballrama.app.R
import fr.footballrama.app.ui.AttributionFooter
import fr.footballrama.app.ui.FocusableInfoRow
import fr.footballrama.app.ui.FrButton
import fr.footballrama.app.ui.logic.filterChannels
import fr.footballrama.app.ui.logic.groupByCountry
import fr.footballrama.app.ui.logic.qualityTags
import fr.footballrama.app.ui.theme.FrColors
import fr.footballrama.app.ui.theme.FrText
import fr.footballrama.app.ui.theme.Safe
import fr.footballrama.core.matching.ChannelGroup
import fr.footballrama.core.matching.MatchResult

@Composable
fun DetailScreen(
    match: MatchResult?,
    filter: String?,
    labeler: (String) -> String,
    attribution: String,
    onBack: () -> Unit,
) {
    val backFocus = remember { FocusRequester() }
    val firstChannelFocus = remember { FocusRequester() }
    val channels = match?.let { filterChannels(it.channels, filter) }.orEmpty()
    val sections = remember(match, filter, labeler) { groupByCountry(channels, labeler) }

    LaunchedEffect(match?.fixtureId) {
        runCatching { if (sections.isNotEmpty()) firstChannelFocus.requestFocus() else backFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FrColors.Background)
            .padding(horizontal = Safe.Horizontal, vertical = Safe.Vertical),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FrButton(stringResource(R.string.back), onClick = onBack, modifier = Modifier.focusRequester(backFocus))
            Spacer(Modifier.width(20.dp))
            if (match != null) {
                Text(
                    stringResource(R.string.detail_when, Formats.day(match.kickoffUtcMillis), Formats.time(match.kickoffUtcMillis)),
                    style = FrText.Label,
                    color = FrColors.TextMuted,
                )
                Spacer(Modifier.width(16.dp))
                CompetitionBadge(match)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (match == null) {
            Text(stringResource(R.string.detail_missing), style = FrText.Body, color = FrColors.TextMuted)
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                stringResource(R.string.match_teams, match.home, match.away),
                style = FrText.Display,
                color = FrColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (filter != null) {
                Text(stringResource(R.string.detail_filter_active, labeler(filter)), style = FrText.Label, color = FrColors.TextMuted)
            }
            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (sections.isEmpty()) {
                    Column {
                        Text(
                            stringResource(if (filter != null && match.channels.isNotEmpty()) R.string.detail_none_for_country else R.string.not_found_in_guide),
                            style = FrText.Section,
                            color = FrColors.Text,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.detail_not_found_help), style = FrText.Body, color = FrColors.TextMuted)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 6.dp),
                    ) {
                        sections.forEachIndexed { sectionIndex, section ->
                            item(key = "h:${section.key}") {
                                Text(
                                    stringResource(R.string.detail_country_header, labeler(section.key), section.channels.size),
                                    style = FrText.Section,
                                    color = FrColors.Text,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            items(section.channels, key = { "c:${section.key}:${it.name}" }) { ch ->
                                val first = sectionIndex == 0 && ch === section.channels.first()
                                ChannelRow(ch, if (first) Modifier.focusRequester(firstChannelFocus) else Modifier)
                            }
                        }
                    }
                }
            }
        }
        AttributionFooter(attribution)
    }
}

@Composable
private fun ChannelRow(channel: ChannelGroup, modifier: Modifier) {
    val tags = qualityTags(channel.variants.map { it.name })
    FocusableInfoRow(modifier = modifier) {
        Column(modifier = Modifier.weight(1f)) {
            Text(channel.displayName, style = FrText.BodyStrong, color = FrColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(channel.epgTitle, style = FrText.Small, color = FrColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val count = channel.variants.size
        val variantsText = pluralStringResource(R.plurals.variant_count, count, count)
        Text(
            if (tags.isEmpty()) variantsText else stringResource(R.string.variants_with_tags, variantsText, tags.joinToString(" · ")),
            style = FrText.Label,
            color = FrColors.TextMuted,
        )
        ScoreBadge(channel.score)
    }
}
