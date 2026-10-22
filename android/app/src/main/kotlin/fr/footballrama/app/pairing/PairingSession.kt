package fr.footballrama.app.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Session de jumelage a usage unique : jeton 128 bits (dans l'URL du QR code) + code a 4 chiffres (affiche
 * UNIQUEMENT sur la TV). Etat protege par verrou : une seule verification a la fois, un seul succes.
 */
class PairingSession(
    random: SecureRandom = SecureRandom(),
    private val maxCodeAttempts: Int = MAX_CODE_ATTEMPTS,
    private val maxSubmissions: Int = MAX_SUBMISSIONS,
) {
    enum class State { ACTIVE, BUSY, SUCCEEDED, LOCKED, CLOSED }

    sealed interface Begin {
        /** Code correct : la verification des identifiants peut commencer (etat BUSY). */
        data object Ok : Begin
        data class WrongCode(val remaining: Int) : Begin
        /** Trop d'essais : session invalidee (nouveau QR code requis). [justLocked] = verrouillee par cet essai. */
        data class Locked(val justLocked: Boolean) : Begin
        /** Une verification est deja en cours. */
        data object Busy : Begin
        /** Session terminee (succes, arret, expiration). */
        data object Closed : Begin
    }

    /** 16 octets aleatoires en base64url sans remplissage (22 caracteres). */
    val token: String
    /** 4 chiffres, 0000 a 9999. */
    val code: String

    private val tokenBytes: ByteArray
    private val codeBytes: ByteArray

    init {
        val raw = ByteArray(16).also(random::nextBytes)
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        tokenBytes = token.toByteArray(Charsets.US_ASCII)
        code = random.nextInt(10_000).toString().padStart(4, '0')
        codeBytes = code.toByteArray(Charsets.US_ASCII)
    }

    @get:Synchronized
    var state: State = State.ACTIVE
        private set

    private var codeFailures = 0
    private var submissions = 0

    /** Comparaison en temps constant (la longueur n'est pas secrete). */
    fun matchesToken(candidate: String): Boolean =
        MessageDigest.isEqual(tokenBytes, candidate.toByteArray(Charsets.US_ASCII))

    @Synchronized
    fun begin(codeCandidate: String): Begin {
        when (state) {
            State.SUCCEEDED, State.CLOSED -> return Begin.Closed
            State.LOCKED -> return Begin.Locked(justLocked = false)
            State.BUSY -> return Begin.Busy
            State.ACTIVE -> Unit
        }
        val ok = MessageDigest.isEqual(codeBytes, codeCandidate.toByteArray(Charsets.UTF_8))
        if (!ok) {
            codeFailures++
            if (codeFailures >= maxCodeAttempts) {
                state = State.LOCKED
                return Begin.Locked(justLocked = true)
            }
            return Begin.WrongCode(maxCodeAttempts - codeFailures)
        }
        submissions++
        state = State.BUSY
        return Begin.Ok
    }

    /** Fin de la verification lancee par [begin]. Retourne l'etat resultant. */
    @Synchronized
    fun finish(success: Boolean): State {
        if (state != State.BUSY) return state
        state = when {
            success -> State.SUCCEEDED
            submissions >= maxSubmissions -> State.LOCKED
            else -> State.ACTIVE
        }
        return state
    }

    /** Arret (sortie d'ecran, expiration...) : toute requete ulterieure est refusee. Un succes reste un succes. */
    @Synchronized
    fun close() {
        if (state != State.SUCCEEDED && state != State.LOCKED) state = State.CLOSED
    }

    /** Vrai si le serveur doit s'arreter (succes ou session invalidee). */
    val isFinished: Boolean
        get() = state.let { it == State.SUCCEEDED || it == State.LOCKED || it == State.CLOSED }

    override fun toString() = "PairingSession(state=$state, token=<redacted>, code=<redacted>)"

    companion object {
        const val MAX_CODE_ATTEMPTS = 5
        /** Verifications d'identifiants (code correct) par session : evite d'utiliser le boitier comme oracle. */
        const val MAX_SUBMISSIONS = 10
    }
}
