package fr.footballrama.app.ui.logic

import androidx.annotation.StringRes
import fr.footballrama.app.R
import fr.footballrama.app.ui.Messages
import fr.footballrama.core.xtream.XtreamAccount

/**
 * Validation locale des identifiants saisis (a la TV ou depuis le telephone) avant toute requete reseau.
 * Un lien M3U/Xtream complet colle dans l'adresse fournit identifiant et mot de passe si les champs sont vides.
 */
object LoginInput {

    sealed interface Result {
        class Ready(val serverUrl: String, val username: String, val password: String, val account: XtreamAccount) : Result {
            override fun toString() = "Ready(serverUrl=$serverUrl, credentials=<redacted>)"
        }

        data class Invalid(@StringRes val message: Int) : Result
    }

    fun prepare(serverInput: String, username: String, password: String): Result {
        val parsed = ServerUrlParser.parse(serverInput)
        if (parsed is ServerUrlParser.Result.Invalid) return Result.Invalid(Messages.urlError(parsed.reason))
        parsed as ServerUrlParser.Result.Valid
        val user = if (username.isBlank()) parsed.extractedUsername.orEmpty() else username
        val pass = password.ifEmpty { parsed.extractedPassword.orEmpty() }
        if (user.isBlank()) return Result.Invalid(R.string.login_error_username)
        if (pass.isEmpty()) return Result.Invalid(R.string.login_error_password)
        val account = XtreamAccount.create(parsed.serverUrl, user, pass) ?: return Result.Invalid(R.string.login_error_invalid)
        return Result.Ready(parsed.serverUrl, user, pass, account)
    }
}
