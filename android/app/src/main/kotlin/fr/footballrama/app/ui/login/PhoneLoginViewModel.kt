package fr.footballrama.app.ui.login

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.footballrama.app.AppContainer
import fr.footballrama.app.R
import fr.footballrama.app.pairing.CredentialSubmitter
import fr.footballrama.app.pairing.LanAddress
import fr.footballrama.app.pairing.PairingEvent
import fr.footballrama.app.pairing.PairingListener
import fr.footballrama.app.pairing.PairingServer
import fr.footballrama.app.pairing.PairingSession
import fr.footballrama.app.pairing.SubmitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.SecureRandom

/**
 * Connexion depuis le telephone : pilote le serveur ephemere [PairingServer].
 * [start] a l'affichage de l'ecran (et au retour au premier plan), [stop] a la sortie, en arriere-plan
 * (ON_STOP), apres un succes, une session invalidee ou 5 minutes. Aucun identifiant n'est conserve ici :
 * ils transitent du serveur vers [LoginService] (meme chemin que la saisie a la TV) puis CredentialStore.
 */
class PhoneLoginViewModel(
    container: AppContainer,
    /** Texte francais d'une ressource (contexte application), pour la page du telephone. */
    private val strings: (Int) -> String,
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data object Starting : UiState
        data class Ready(val url: String, val code: String, val expiresAtElapsed: Long, val status: Status) : UiState {
            override fun toString() = "Ready(<redacted>, status=$status)"
        }
        data object NoNetwork : UiState
        data object StartFailed : UiState
        data object Expired : UiState
        data object Locked : UiState
        data object Success : UiState
    }

    sealed interface Status {
        data object Waiting : Status
        data object Validating : Status
        data class WrongCode(val remaining: Int) : Status
        data class Failed(val message: String) : Status
    }

    private val service = LoginService(container)
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val lock = Any()
    private var server: PairingServer? = null
    private var generation = 0
    @Volatile private var validation: Deferred<LoginService.Outcome>? = null

    /** Nouvelle session (nouveau jeton, nouveau code). Sans effet si une session tourne deja. */
    fun start() {
        val gen = synchronized(lock) {
            if (server != null || _state.value == UiState.Starting) return
            _state.value = UiState.Starting
            generation
        }
        viewModelScope.launch(Dispatchers.IO) {
            val address = LanAddress.find()
            if (address == null) {
                setIfCurrent(gen, UiState.NoNetwork)
                return@launch
            }
            val session = PairingSession(SecureRandom())
            val srv = PairingServer(address, session, submitter(), PairingListener { event -> onEvent(gen, event) })
            try {
                srv.start()
            } catch (_: IOException) {
                setIfCurrent(gen, UiState.StartFailed)
                return@launch
            }
            synchronized(lock) {
                if (gen != generation) { // stop() appele pendant le demarrage
                    srv.stop()
                    return@launch
                }
                server = srv
                _state.value = UiState.Ready(
                    url = srv.url,
                    code = session.code,
                    expiresAtElapsed = SystemClock.elapsedRealtime() + PairingServer.Config().lifetimeMs,
                    status = Status.Waiting,
                )
            }
        }
    }

    /** Arret immediat (sortie d'ecran, arriere-plan). Idempotent. */
    fun stop() {
        val srv = synchronized(lock) {
            generation++
            val s = server
            server = null
            if (_state.value is UiState.Ready || _state.value == UiState.Starting) _state.value = UiState.Idle
            s
        }
        // Pas d'annulation de la verification en cours : apres un succes, l'enregistrement des identifiants fait
        // quitter cet ecran (donc stop()) avant que la reponse "Connecte" parte vers le telephone, qui affichait
        // "connexion interrompue". La verification est bornee (VALIDATION_TIMEOUT_MS) et le serveur n'accepte
        // plus aucune connexion ; les reponses en cours ont le delai de grace du serveur.
        srv?.stop()
    }

    private fun setIfCurrent(gen: Int, s: UiState) = synchronized(lock) { if (gen == generation) _state.value = s }

    /** Appele depuis un thread du serveur : bloque jusqu'au resultat (au plus [VALIDATION_TIMEOUT_MS]). */
    private fun submitter() = CredentialSubmitter { serverInput, username, password ->
        val job = viewModelScope.async { service.verifyAndSignIn(serverInput, username, password) }
        validation = job
        val outcome = try {
            runBlocking { withTimeoutOrNull(VALIDATION_TIMEOUT_MS) { job.await() } }
        } catch (_: CancellationException) {
            LoginService.Outcome.Error(R.string.error_cancelled)
        } finally {
            validation = null
        }
        when (outcome) {
            null -> {
                job.cancel()
                SubmitResult.Failure(strings(R.string.error_timeout))
            }
            LoginService.Outcome.Success -> SubmitResult.Success
            is LoginService.Outcome.Error -> SubmitResult.Failure(strings(outcome.message))
        }
    }

    private fun onEvent(gen: Int, event: PairingEvent) {
        synchronized(lock) {
            if (gen != generation) return // session precedente, deja arretee
            val current = _state.value
            when (event) {
                PairingEvent.Succeeded -> { server = null; _state.value = UiState.Success }
                PairingEvent.Locked -> { server = null; _state.value = UiState.Locked }
                PairingEvent.Expired -> { server = null; _state.value = UiState.Expired }
                PairingEvent.Validating -> if (current is UiState.Ready) _state.value = current.copy(status = Status.Validating)
                is PairingEvent.WrongCode ->
                    if (current is UiState.Ready) _state.value = current.copy(status = Status.WrongCode(event.remaining))
                is PairingEvent.Failed ->
                    if (current is UiState.Ready) _state.value = current.copy(status = Status.Failed(event.message))
            }
        }
    }

    override fun onCleared() {
        stop()
    }

    companion object {
        const val VALIDATION_TIMEOUT_MS = 45_000L
    }
}
