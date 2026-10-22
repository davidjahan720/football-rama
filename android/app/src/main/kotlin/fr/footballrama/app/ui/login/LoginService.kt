package fr.footballrama.app.ui.login

import androidx.annotation.StringRes
import fr.footballrama.app.AppContainer
import fr.footballrama.app.R
import fr.footballrama.app.data.CredentialStoreException
import fr.footballrama.app.ui.Messages
import fr.footballrama.app.ui.logic.LoginInput
import fr.footballrama.core.xtream.XtreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chemin UNIQUE de connexion, partage par la saisie a la TV et la connexion depuis le telephone :
 * validation locale -> verification aupres du serveur Xtream de l'utilisateur (seule destination) ->
 * enregistrement chiffre via [fr.footballrama.app.data.CredentialStore] (dans [AppContainer.signIn]).
 */
class LoginService(private val container: AppContainer) {

    sealed interface Outcome {
        data object Success : Outcome
        data class Error(@StringRes val message: Int) : Outcome
    }

    suspend fun verifyAndSignIn(serverInput: String, username: String, password: String): Outcome {
        val ready = when (val r = LoginInput.prepare(serverInput, username, password)) {
            is LoginInput.Result.Invalid -> return Outcome.Error(r.message)
            is LoginInput.Result.Ready -> r
        }
        return try {
            withContext(Dispatchers.IO) { container.xtreamClient.getLiveCategories(ready.account) }
            container.signIn(ready.serverUrl, ready.username, ready.password)
            Outcome.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: XtreamException) {
            Outcome.Error(Messages.xtreamError(e.kind, e.httpCode))
        } catch (_: CredentialStoreException) {
            Outcome.Error(R.string.error_secure_storage)
        } catch (_: IllegalArgumentException) {
            Outcome.Error(R.string.login_error_invalid)
        } catch (_: Exception) {
            Outcome.Error(R.string.error_unknown)
        }
    }
}
