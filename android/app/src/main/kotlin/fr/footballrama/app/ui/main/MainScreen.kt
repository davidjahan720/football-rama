package fr.footballrama.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.footballrama.app.FootballRamaApp
import fr.footballrama.app.ui.logic.availableCompetitions
import fr.footballrama.app.ui.logic.availableCountries
import fr.footballrama.app.ui.logic.effectiveFilter
import fr.footballrama.app.ui.logic.filterByCompetition
import fr.footballrama.app.ui.logic.splitByDay
import java.time.ZoneId

private enum class Screen { MATCHES, DETAIL, SETTINGS }

/** Cible de focus a restaurer au retour sur la liste. */
internal const val FOCUS_SETTINGS = "settings"
internal fun matchFocusKey(fixtureId: Long) = "match:$fixtureId"

/**
 * Navigation par etat (3 ecrans) : Retour = detail/reglages -> liste ; depuis la liste, comportement systeme
 * (quitter). La position de defilement et la carte focalisee sont restaurees au retour.
 */
@Composable
fun MainScreen(vm: MainViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.MATCHES) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var restoreFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val state by vm.refreshState.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val storedFilter by vm.countryFilter.collectAsStateWithLifecycle()
    val storedCompetition by vm.competitionFilter.collectAsStateWithLifecycle()
    val labeler = rememberCountryLabeler()
    // Bandeau "mise a jour disponible" : l'installation se fait depuis les Reglages
    val updateState by (LocalContext.current.applicationContext as FootballRamaApp).container
        .updateManager.state.collectAsStateWithLifecycle()

    val zone = ZoneId.systemDefault()
    // Recalcul chaque minute : passage a minuit et matchs termines masques (2 h 30 apres le coup d'envoi).
    val minute = now.epochSecond / 60
    val allSections = remember(report, minute, zone) { splitByDay(report?.matches.orEmpty(), now, zone) }
    // Filtre competition (masque des matchs), puis filtre pays (masque des chaines) : combinables.
    val competitions = remember(allSections) { availableCompetitions(allSections.today + allSections.tomorrow) }
    val competition = effectiveFilter(storedCompetition, competitions)
    val sections = remember(allSections, competition) { filterByCompetition(allSections, competition) }
    val countries = remember(sections, labeler) { availableCountries(sections.today + sections.tomorrow, labeler) }
    val filter = effectiveFilter(storedFilter, countries)

    BackHandler(enabled = screen != Screen.MATCHES) {
        screen = Screen.MATCHES
    }

    when (screen) {
        Screen.MATCHES -> MatchesScreen(
            state = state,
            report = report,
            sections = sections,
            now = now,
            competitions = competitions,
            competition = competition,
            onCompetition = vm::setCompetitionFilter,
            countries = countries,
            filter = filter,
            labeler = labeler,
            attribution = vm.attribution,
            listState = listState,
            restoreFocus = restoreFocus,
            onFocusRestored = { restoreFocus = null },
            onFilter = vm::setCountryFilter,
            onRetry = vm::refresh,
            onOpenMatch = { id ->
                selectedId = id
                restoreFocus = matchFocusKey(id)
                screen = Screen.DETAIL
            },
            onOpenSettings = {
                restoreFocus = FOCUS_SETTINGS
                screen = Screen.SETTINGS
            },
            updateBanner = { UpdateBanner(updateState) },
            onChangeAccount = vm::signOut,
        )
        Screen.DETAIL -> DetailScreen(
            match = report?.matches?.firstOrNull { it.fixtureId == selectedId },
            filter = filter,
            labeler = labeler,
            attribution = vm.attribution,
            onBack = { screen = Screen.MATCHES },
        )
        Screen.SETTINGS -> SettingsScreen(vm = vm, state = state, report = report, now = now, onBack = { screen = Screen.MATCHES })
    }
}
