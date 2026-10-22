package fr.footballrama.app.ui.login

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.footballrama.app.AppContainer
import fr.footballrama.app.R
import fr.footballrama.app.settings.UserAgentRules
import fr.footballrama.app.ui.Messages
import fr.footballrama.app.ui.logic.ServerUrlParser
import fr.footballrama.core.xtream.XtreamAccount
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Etat de l'ecran de connexion. Volontairement PAS de SavedStateHandle : le mot de passe (et les autres
 * champs) ne sont jamais ecrits dans le Bundle d'etat de l'Activity ; ils vivent en memoire uniquement.
 */
class LoginViewModel(private val container: AppContainer) : ViewModel() {

    var server by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var passwordVisible by mutableStateOf(false)
        private set
    var advancedOpen by mutableStateOf(false)
        private set
    var userAgent by mutableStateOf(container.uiPreferences.userAgent.orEmpty())
        private set
    var loading by mutableStateOf(false)
        private set

    @get:StringRes
    var error by mutableStateOf<Int?>(null)
        private set

    @get:StringRes
    var info by mutableStateOf<Int?>(null)
        private set

    /** Avertissement HTTP en clair, calcule a la volee sur l'adresse saisie. */
    val isCleartext: Boolean
        get() = (ServerUrlParser.parse(server) as? ServerUrlParser.Result.Valid)?.isCleartext == true

    private var job: Job? = null
    private val service = LoginService(container)

    fun onServerChange(v: String) { server = v; error = null; info = null }
    fun onUsernameChange(v: String) { username = v; error = null }
    fun onPasswordChange(v: String) { password = v; error = null }
    fun onUserAgentChange(v: String) { userAgent = v; error = null }
    fun togglePasswordVisible() { passwordVisible = !passwordVisible }
    fun toggleAdvanced() { advancedOpen = !advancedOpen }
    fun closeAdvanced() { advancedOpen = false }

    /**
     * Normalise l'adresse quand on quitte le champ (touche "Suivant") : si une URL Xtream complete a ete
     * collee, l'adresse est ramenee a sa base et les identifiants extraits remplissent les champs vides.
     */
    fun normalizeServer() {
        val r = ServerUrlParser.parse(server) as? ServerUrlParser.Result.Valid ?: return
        applyExtracted(r)
    }

    private fun applyExtracted(r: ServerUrlParser.Result.Valid) {
        server = r.serverUrl
        if (r.extractedUsername != null && r.extractedPassword != null) {
            var used = false
            if (username.isBlank()) { username = r.extractedUsername; used = true }
            if (password.isEmpty()) { password = r.extractedPassword; used = true }
            if (used) info = R.string.login_credentials_extracted
        }
    }

    fun submit() {
        if (loading) return
        error = null
        val parsed = ServerUrlParser.parse(server)
        if (parsed is ServerUrlParser.Result.Invalid) {
            error = Messages.urlError(parsed.reason)
            return
        }
        applyExtracted(parsed as ServerUrlParser.Result.Valid)
        when {
            username.isBlank() -> { error = R.string.login_error_username; return }
            password.isEmpty() -> { error = R.string.login_error_password; return }
        }
        val ua = UserAgentRules.validate(userAgent)
        if (ua is UserAgentRules.Result.Invalid) {
            advancedOpen = true
            error = Messages.userAgentError(ua.reason)
            return
        }
        val account = XtreamAccount.create(server, username, password)
        if (account == null) {
            error = R.string.login_error_invalid
            return
        }
        container.uiPreferences.setUserAgent(userAgent)

        loading = true
        job = viewModelScope.launch {
            try {
                // Meme chemin que la connexion depuis le telephone (LoginService).
                when (val outcome = service.verifyAndSignIn(server, username, password)) {
                    is LoginService.Outcome.Error -> error = outcome.message
                    LoginService.Outcome.Success -> {
                        // L'ecran va disparaitre : rien ne reste en memoire (et formulaire vide apres deconnexion).
                        server = ""
                        username = ""
                        password = ""
                        passwordVisible = false
                        advancedOpen = false
                        info = null
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
        password = ""
    }
}
