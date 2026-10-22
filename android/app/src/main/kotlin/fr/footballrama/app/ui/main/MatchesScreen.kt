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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import fr.footballrama.app.R
import fr.footballrama.app.data.RefreshError
import fr.footballrama.app.data.RefreshState
import fr.footballrama.app.ui.AttributionFooter
import fr.footballrama.app.ui.Badge
import fr.footballrama.app.ui.Banner
import fr.footballrama.app.ui.BannerKind
import fr.footballrama.app.ui.FocusRowCard
import fr.footballrama.app.ui.FrButton
import fr.footballrama.app.ui.Messages
import fr.footballrama.app.ui.ProgressBar
import fr.footballrama.app.ui.logic.DaySections
import fr.footballrama.app.ui.logic.ageOf
import fr.footballrama.app.ui.logic.bestScore
import fr.footballrama.app.ui.logic.cardTeamName
import fr.footballrama.app.ui.logic.filterChannels
import fr.footballrama.app.ui.theme.FrColors
import fr.footballrama.app.ui.theme.FrText
import fr.footballrama.app.ui.theme.Safe
import fr.footballrama.core.matching.MatchReport
import fr.footballrama.core.matching.MatchResult
import fr.footballrama.core.matching.Score
import java.time.Instant

@Composable
fun MatchesScreen(
    state: RefreshState,
    report: MatchReport?,
    sections: DaySections,
    now: Instant,
    countries: List<String>,
    filter: String?,
    labeler: (String) -> String,
    attribution: String,
    listState: LazyListState,
    restoreFocus: String?,
    onFocusRestored: () -> Unit,
    onFilter: (String?) -> Unit,
    onRetry: () -> Unit,
    onOpenMatch: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Cible de focus a l'entree : element restaure, sinon premiere carte, sinon bouton Reglages.
    val firstCard = (sections.today + sections.tomorrow).firstOrNull()?.let { matchFocusKey(it.fixtureId) }
    val allKeys = remember(sections) { (sections.today + sections.tomorrow).map { matchFocusKey(it.fixtureId) }.toSet() }
    val target = when {
        restoreFocus != null && (restoreFocus == FOCUS_SETTINGS || restoreFocus in allKeys) -> restoreFocus
        firstCard != null && restoreFocus == null -> firstCard
        else -> FOCUS_SETTINGS
    }
    val pending = remember { mutableFocusOnce() }

    val settingsFocus = remember { FocusRequester() }
    if (target == FOCUS_SETTINGS) {
        LaunchedEffect(Unit) {
            if (pending.consume()) {
                runCatching { settingsFocus.requestFocus() }
                onFocusRestored()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FrColors.Background)
            .padding(horizontal = Safe.Horizontal, vertical = Safe.Vertical),
    ) {
        // En-tete
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = FrText.Title, color = FrColors.Text, modifier = Modifier.weight(1f))
            FrButton(
                text = stringResource(R.string.settings_title),
                onClick = onOpenSettings,
                modifier = Modifier.focusRequester(settingsFocus),
            )
        }
        Spacer(Modifier.height(10.dp))

        // Filtre pays
        if (countries.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                item(key = "all") {
                    CountryChip(stringResource(R.string.filter_all), selected = filter == null) { onFilter(null) }
                }
                items(countries, key = { "c:$it" }) { key ->
                    CountryChip(labeler(key), selected = filter == key) { onFilter(key) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        StatusArea(state = state, report = report, now = now, onRetry = onRetry)

        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            when {
                report == null -> NoDataState(state, onRetry)
                sections.isEmpty -> CenterMessage(stringResource(R.string.empty_no_match))
                else -> LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 6.dp),
                ) {
                    daySection(R.string.section_today, "today", sections.today, filter, target, pending, onFocusRestored, onOpenMatch)
                    daySection(R.string.section_tomorrow, "tomorrow", sections.tomorrow, filter, target, pending, onFocusRestored, onOpenMatch)
                }
            }
        }

        AttributionFooter(attribution)
    }
}

/** Demande de focus a usage unique (evite de voler le focus quand une carte reapparait au defilement). */
internal class FocusOnce {
    private var done = false
    fun consume(): Boolean = if (done) false else { done = true; true }
}

internal fun mutableFocusOnce() = FocusOnce()

private fun androidx.compose.foundation.lazy.LazyListScope.daySection(
    titleRes: Int,
    key: String,
    matches: List<MatchResult>,
    filter: String?,
    target: String,
    pending: FocusOnce,
    onFocusRestored: () -> Unit,
    onOpenMatch: (Long) -> Unit,
) {
    item(key = "h:$key") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
            Text(stringResource(titleRes), style = FrText.Section, color = FrColors.Text)
            Spacer(Modifier.width(12.dp))
            Text(
                pluralStringResource(R.plurals.match_count, matches.size, matches.size),
                style = FrText.Label,
                color = FrColors.TextMuted,
            )
        }
    }
    if (matches.isEmpty()) {
        item(key = "e:$key") {
            Text(stringResource(R.string.section_empty), style = FrText.Body, color = FrColors.TextMuted)
        }
    }
    items(matches, key = { it.fixtureId }) { m ->
        val focusKey = matchFocusKey(m.fixtureId)
        val requester = remember { FocusRequester() }
        if (focusKey == target) {
            LaunchedEffect(Unit) {
                if (pending.consume()) {
                    runCatching { requester.requestFocus() }
                    onFocusRestored()
                }
            }
        }
        MatchCard(m, filter, Modifier.focusRequester(requester)) { onOpenMatch(m.fixtureId) }
    }
}

@Composable
private fun MatchCard(match: MatchResult, filter: String?, modifier: Modifier, onClick: () -> Unit) {
    val channels = filterChannels(match.channels, filter)
    val best = bestScore(channels)
    FocusRowCard(onClick = onClick, modifier = modifier) {
        Text(Formats.time(match.kickoffUtcMillis), style = FrText.BodyStrong, color = FrColors.Text, modifier = Modifier.width(72.dp))
        Badge(match.competitionName, FrColors.BadgeBg, FrColors.BadgeText, modifier = Modifier.widthIn(max = 200.dp))
        // Noms courts du calendrier ("Dortmund", "Slavia Praha") ; nom complet dans le detail.
        // Points de suspension seulement si meme le nom court ne tient pas.
        Text(
            stringResource(
                R.string.match_teams,
                cardTeamName(match.home, match.homeShort),
                cardTeamName(match.away, match.awayShort),
            ),
            style = FrText.BodyStrong,
            color = FrColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (channels.isEmpty()) {
            Text(stringResource(R.string.not_found_in_guide), style = FrText.Label, color = FrColors.TextMuted)
        } else {
            Text(
                pluralStringResource(R.plurals.channel_count, channels.size, channels.size),
                style = FrText.Label,
                color = FrColors.TextMuted,
            )
            best?.let { ScoreBadge(it) }
        }
    }
}

@Composable
fun ScoreBadge(score: Score) {
    if (score == Score.FORT) {
        Badge(scoreLabel(score), FrColors.FortBg, FrColors.FortText)
    } else {
        Badge(scoreLabel(score), FrColors.MoyenBg, FrColors.MoyenText)
    }
}

/** Puce de filtre pays (Surface TV stable, pas d'API experimentale). Selection = fond ambre. */
@Composable
private fun CountryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = Modifier.semantics { this.selected = selected },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) FrColors.Accent else FrColors.Surface,
            contentColor = if (selected) FrColors.OnAccent else FrColors.Text,
            focusedContainerColor = if (selected) FrColors.AccentFocused else FrColors.SurfaceFocused,
            focusedContentColor = if (selected) FrColors.OnAccent else FrColors.Text,
            pressedContainerColor = if (selected) FrColors.AccentFocused else FrColors.SurfaceFocused,
            pressedContentColor = if (selected) FrColors.OnAccent else FrColors.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) FrColors.Accent else FrColors.Outline), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, if (selected) FrColors.Text else FrColors.Accent), shape = shape),
            pressedBorder = Border(BorderStroke(3.dp, if (selected) FrColors.Text else FrColors.Accent), shape = shape),
        ),
    ) {
        Text(label, style = FrText.Label, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
    }
}

/** Bandeau d'etat : chargement, avertissement, erreur ; ligne "Mis a jour il y a..." sinon. */
@Composable
private fun StatusArea(state: RefreshState, report: MatchReport?, now: Instant, onRetry: () -> Unit) {
    val updated = report?.let { updatedText(ageOf(Instant.ofEpochMilli(it.updatedAtMillis), now)) }
    when (state) {
        is RefreshState.Loading -> {
            val step = stringResource(Messages.step(state.step))
            val pct = state.progress?.let { (it * 100).toInt().coerceIn(0, 100) }
            Banner(
                kind = BannerKind.INFO,
                text = if (pct != null) stringResource(R.string.loading_step_percent, step, pct) else stringResource(R.string.loading_step, step),
                secondary = updated,
            )
            ProgressBar(state.progress, Modifier.padding(top = 4.dp))
        }
        is RefreshState.Error -> {
            // Sans donnees, l'erreur est affichee au centre (NoDataState).
            if (report != null) {
                Banner(
                    kind = BannerKind.ERROR,
                    text = stringResource(Messages.refreshError(state.type)),
                    secondary = updated?.let { stringResource(R.string.showing_previous_data, it) },
                    action = if (state.type.canRetry()) {
                        { FrButton(stringResource(R.string.retry), onClick = onRetry) }
                    } else {
                        null
                    },
                )
            }
        }
        is RefreshState.Success -> {
            when {
                state.warning == RefreshState.Warning.FIXTURES_FROM_CACHE ->
                    Banner(kind = BannerKind.WARNING, text = stringResource(R.string.warning_fixtures_cache), secondary = updated)
                state.fromCache && updated != null ->
                    Text(stringResource(R.string.saved_data_line, updated), style = FrText.Label, color = FrColors.TextMuted)
                updated != null -> Text(updated, style = FrText.Label, color = FrColors.TextMuted)
            }
        }
        RefreshState.Idle -> if (updated != null) Text(updated, style = FrText.Label, color = FrColors.TextMuted)
    }
}

internal fun RefreshError.canRetry(): Boolean = this != RefreshError.NOT_CONFIGURED && this != RefreshError.NO_ACCOUNT

@Composable
private fun NoDataState(state: RefreshState, onRetry: () -> Unit) {
    when (state) {
        is RefreshState.Error -> {
            if (state.type == RefreshError.NOT_CONFIGURED) {
                CenterMessage(stringResource(R.string.not_configured_title), stringResource(R.string.not_configured_body))
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(Messages.refreshError(state.type)),
                        style = FrText.Body,
                        color = FrColors.ErrorText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 640.dp),
                    )
                    if (state.type.canRetry()) {
                        Spacer(Modifier.height(16.dp))
                        FrButton(stringResource(R.string.retry), onClick = onRetry, primary = true)
                    }
                }
            }
        }
        else -> CenterMessage(stringResource(R.string.loading_first))
    }
}

@Composable
private fun CenterMessage(title: String, body: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = FrText.Section, color = FrColors.Text, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 720.dp))
        if (body != null) {
            Spacer(Modifier.height(12.dp))
            Text(body, style = FrText.Body, color = FrColors.TextMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 720.dp))
        }
    }
}
