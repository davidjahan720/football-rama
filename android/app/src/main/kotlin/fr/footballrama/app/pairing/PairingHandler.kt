package fr.footballrama.app.pairing

import fr.footballrama.app.ui.logic.ServerUrlParser
import java.util.Locale

/** Verification + enregistrement des identifiants (meme chemin que la saisie manuelle). Appel BLOQUANT. */
fun interface CredentialSubmitter {
    fun submit(serverInput: String, username: String, password: String): SubmitResult
}

sealed interface SubmitResult {
    data object Success : SubmitResult
    /** [message] : texte francais lisible, sans detail technique ni identifiant. */
    data class Failure(val message: String) : SubmitResult
}

/** Evenements pour l'ecran TV. Ne contiennent jamais d'identifiant. Appeles depuis les threads du serveur. */
sealed interface PairingEvent {
    data object Validating : PairingEvent
    data class WrongCode(val remaining: Int) : PairingEvent
    data class Failed(val message: String) : PairingEvent
    data object Locked : PairingEvent
    data object Succeeded : PairingEvent
    data object Expired : PairingEvent
}

fun interface PairingListener {
    fun onEvent(event: PairingEvent)
}

/**
 * Routage et regles de securite d'une requete (pur, sans socket). Ordre des controles :
 * Host exact (anti DNS rebinding) -> chemin /c/<jeton> en temps constant (sinon 404 sans detail) -> methode
 * -> Origin / Sec-Fetch-Site -> Content-Type -> formulaire strict -> code (5 essais) -> verrou -> verification.
 */
class PairingHandler(
    private val session: PairingSession,
    /** Valeur exacte attendue de l'en-tete Host, ex. "192.168.1.11:8095". */
    private val expectedHost: String,
    private val submitter: CredentialSubmitter,
    private val listener: PairingListener,
    /** Adresse reaffichee apres une erreur : base normalisee SANS identifiants (un lien M3U en contient). */
    private val serverEcho: (String) -> String = ::safeServerEcho,
) {
    val path: String = PATH_PREFIX + session.token
    private val expectedOrigin = "http://$expectedHost"

    fun handle(req: HttpRequest): HttpResponse = try {
        route(req)
    } catch (e: HttpException) {
        HttpResponse.empty(e.status)
    }

    private fun route(req: HttpRequest): HttpResponse {
        val host = req.singleHeader("host") ?: return HttpResponse.empty(400)
        if (host != expectedHost) return HttpResponse.empty(400)

        val target = req.target
        if (!target.startsWith(PATH_PREFIX) || !session.matchesToken(target.substring(PATH_PREFIX.length))) {
            return HttpResponse.empty(404)
        }
        return when (req.method) {
            "GET" -> {
                if (req.body.isNotEmpty()) return HttpResponse.empty(400)
                when (session.state) {
                    PairingSession.State.ACTIVE, PairingSession.State.BUSY -> HttpResponse.html(200, PairingPage.form(path))
                    PairingSession.State.LOCKED -> HttpResponse.html(410, lockedPage())
                    else -> HttpResponse.html(410, closedPage())
                }
            }
            "POST" -> post(req)
            else -> HttpResponse.empty(405, listOf("Allow" to "GET, POST"))
        }
    }

    private fun post(req: HttpRequest): HttpResponse {
        // Une page d'un autre site (ou un DNS rebinding deja bloque par Host) ne peut pas soumettre le formulaire.
        val origin = req.singleHeader("origin")
        val fetchSite = req.singleHeader("sec-fetch-site")
        // Certains navigateurs envoient "Origin: null" pour un formulaire de la meme page (politique de referrer
        // stricte, constate avec Chrome Android) : accepte seulement si Sec-Fetch-Site (non falsifiable par une page)
        // confirme la meme origine.
        val sameOriginNull = origin == "null" && fetchSite == "same-origin"
        if (origin != null && origin != expectedOrigin && !sameOriginNull) return HttpResponse.empty(403)
        if (fetchSite != null && fetchSite != "same-origin" && fetchSite != "none") return HttpResponse.empty(403)

        val contentType = req.singleHeader("content-type") ?: return HttpResponse.empty(415)
        if (!isFormUrlEncoded(contentType)) return HttpResponse.empty(415)

        val form = FormDecoder.decode(req.body, FIELDS)
        val code = form["code"].orEmpty().trim()
        val server = form["server"].orEmpty().trim()
        val username = form["username"].orEmpty().trim()
        val password = form["password"].orEmpty()
        if (server.length > MAX_SERVER || username.length > MAX_FIELD || password.length > MAX_FIELD) {
            return HttpResponse.html(400, PairingPage.form(path, "Un des champs est trop long."))
        }

        val echoServer = serverEcho(server)
        return when (val begin = session.begin(code)) {
            PairingSession.Begin.Closed -> HttpResponse.html(410, closedPage())
            PairingSession.Begin.Busy -> HttpResponse.html(
                409,
                PairingPage.form(path, "Une vérification est déjà en cours. Patientez quelques secondes puis réessayez.", echoServer, username),
            )
            is PairingSession.Begin.Locked -> {
                if (begin.justLocked) listener.onEvent(PairingEvent.Locked)
                HttpResponse.html(403, lockedPage())
            }
            is PairingSession.Begin.WrongCode -> {
                listener.onEvent(PairingEvent.WrongCode(begin.remaining))
                val msg = if (begin.remaining == 1) {
                    "Code incorrect. Il vous reste 1 essai."
                } else {
                    "Code incorrect. Il vous reste ${begin.remaining} essais."
                }
                HttpResponse.html(403, PairingPage.form(path, msg, echoServer, username))
            }
            PairingSession.Begin.Ok -> verify(server, username, password, echoServer)
        }
    }

    private fun verify(server: String, username: String, password: String, echoServer: String): HttpResponse {
        listener.onEvent(PairingEvent.Validating)
        val result = try {
            submitter.submit(server, username, password)
        } catch (_: Exception) {
            SubmitResult.Failure(GENERIC_ERROR)
        }
        return when (result) {
            SubmitResult.Success -> {
                session.finish(success = true)
                listener.onEvent(PairingEvent.Succeeded)
                HttpResponse.html(200, PairingPage.success())
            }
            is SubmitResult.Failure -> {
                val state = session.finish(success = false)
                listener.onEvent(PairingEvent.Failed(result.message))
                if (state == PairingSession.State.LOCKED) {
                    listener.onEvent(PairingEvent.Locked)
                    HttpResponse.html(403, lockedPage())
                } else {
                    HttpResponse.html(200, PairingPage.form(path, result.message, echoServer, username))
                }
            }
        }
    }

    private fun lockedPage() = PairingPage.message(
        "Session bloquée",
        "Trop d’essais. Sur le boîtier, choisissez « Nouveau QR code » pour recommencer.",
    )

    private fun closedPage() = PairingPage.message(
        "Session terminée",
        "Cette page n’est plus valide. Sur le boîtier, choisissez « Se connecter avec mon téléphone » pour obtenir un nouveau QR code.",
    )

    companion object {
        const val PATH_PREFIX = "/c/"
        const val MAX_SERVER = 2048
        const val MAX_FIELD = 256
        const val GENERIC_ERROR = "Une erreur inattendue s’est produite. Réessayez."
        val FIELDS = setOf("code", "server", "username", "password")

        fun isFormUrlEncoded(contentType: String): Boolean {
            val parts = contentType.split(';').map { it.trim().lowercase(Locale.ROOT) }
            if (parts.first() != "application/x-www-form-urlencoded") return false
            return parts.drop(1).all { it.isEmpty() || it == "charset=utf-8" || it == "charset=\"utf-8\"" }
        }

        fun safeServerEcho(input: String): String =
            (ServerUrlParser.parse(input) as? ServerUrlParser.Result.Valid)?.serverUrl.orEmpty()
    }
}
