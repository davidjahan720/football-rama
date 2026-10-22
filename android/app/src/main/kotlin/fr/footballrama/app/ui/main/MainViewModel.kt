package fr.footballrama.app.ui.main

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.footballrama.app.AppContainer
import fr.footballrama.app.R
import fr.footballrama.app.data.RefreshState
import fr.footballrama.app.settings.UserAgentRules
import fr.footballrama.app.ui.Messages
import fr.footballrama.app.ui.logic.shouldAutoRefresh
import fr.footballrama.core.matching.MatchReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** Etat de l'ecran principal (matchs, detail, reglages). */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.matchRepository

    val refreshState: StateFlow<RefreshState> = repository.state
    val attribution: String = repository.attribution

    /** Dernieres donnees valides : conservees a l'ecran pendant un chargement ou apres une erreur. */
    private val _report = MutableStateFlow<MatchReport?>(null)
    val report: StateFlow<MatchReport?> = _report.asStateFlow()

    /** Horloge de l'ecran ("il y a X min", passage a minuit). */
    val now: StateFlow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(TICK_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Instant.now())

    private val _countryFilter = MutableStateFlow(container.uiPreferences.countryFilter)
    val countryFilter: StateFlow<String?> = _countryFilter.asStateFlow()

    private val _competitionFilter = MutableStateFlow(container.uiPreferences.competitionFilter)
    val competitionFilter: StateFlow<String?> = _competitionFilter.asStateFlow()

    private val _userAgent = MutableStateFlow(container.uiPreferences.userAgent.orEmpty())
    val userAgent: StateFlow<String> = _userAgent.asStateFlow()

    /** Message (StringRes) apres enregistrement du User-Agent : succes ou erreur de validation. */
    private val _userAgentMessage = MutableStateFlow<Pair<Int, Boolean>?>(null)
    val userAgentMessage: StateFlow<Pair<Int, Boolean>?> = _userAgentMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.state.collect { s ->
                when (s) {
                    is RefreshState.Success -> _report.value = s.data
                    is RefreshState.Error -> s.lastData?.let { _report.value = it }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            val cached = repository.cachedReport()
            if (_report.value == null) _report.value = cached
            val last = cached?.updatedAtMillis?.let(Instant::ofEpochMilli)
            if (repository.state.value !is RefreshState.Loading && shouldAutoRefresh(last, Instant.now())) {
                container.appScope.launch { repository.refresh() } // automatique : repli limite a 1 fois / 6 h
            }
        }
    }

    /**
     * Lance un rafraichissement dans la portee de l'application (il continue si l'ecran change).
     * Le depot ignore la demande si un resultat de moins d'une minute existe (protection du serveur IPTV).
     * Demande explicite (bouton) : le repli chaine par chaine peut tourner, dans le respect du "pas avant".
     */
    fun refresh() {
        container.appScope.launch { repository.refresh(manual = true) }
    }

    fun setCountryFilter(key: String?) {
        _countryFilter.value = key
        container.uiPreferences.countryFilter = key
    }

    fun setCompetitionFilter(code: String?) {
        _competitionFilter.value = code
        container.uiPreferences.competitionFilter = code
    }

    fun onUserAgentChange(value: String) {
        _userAgent.value = value
        _userAgentMessage.value = null
    }

    fun saveUserAgent() {
        val r = UserAgentRules.validate(_userAgent.value)
        _userAgentMessage.value = if (r is UserAgentRules.Result.Invalid) {
            Messages.userAgentError(r.reason) to false
        } else {
            container.uiPreferences.setUserAgent(_userAgent.value)
            _userAgent.value = container.uiPreferences.userAgent.orEmpty()
            R.string.ua_saved to true
        }
    }

    fun resetUserAgent() {
        container.uiPreferences.setUserAgent("")
        _userAgent.value = ""
        _userAgentMessage.value = R.string.ua_reset_done to true
    }

    /** Efface identifiants, resultats et reglages ; l'ecran de connexion s'affiche ensuite. */
    fun signOut() {
        container.appScope.launch {
            container.signOut()
            _report.value = null
            _countryFilter.value = null
            _competitionFilter.value = null
            _userAgent.value = ""
            _userAgentMessage.value = null
        }
    }

    @StringRes
    fun errorMessage(state: RefreshState.Error): Int = Messages.refreshError(state.type)

    private companion object {
        const val TICK_MS = 30_000L
    }
}
