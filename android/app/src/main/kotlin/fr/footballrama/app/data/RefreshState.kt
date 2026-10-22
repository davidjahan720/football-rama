package fr.footballrama.app.data

import fr.footballrama.core.matching.MatchReport
import java.time.Instant

/** Etat expose a l'UI. Aucun type ne contient d'URL, d'identifiant ni de message d'exception. */
sealed interface RefreshState {
    data object Idle : RefreshState

    /** [progress] global entre 0 et 1, null si indetermine (taille de l'EPG inconnue). */
    data class Loading(val step: Step, val progress: Float?) : RefreshState

    data class Success(
        val data: MatchReport,
        val updatedAt: Instant,
        /** true = resultat relu depuis le disque (pas encore rafraichi dans cette session). */
        val fromCache: Boolean,
        val warning: Warning? = null,
    ) : RefreshState

    /** [lastData] = dernier resultat valide, a afficher avec un bandeau d'erreur. */
    data class Error(val type: RefreshError, val lastData: MatchReport?) : RefreshState

    enum class Step { FIXTURES, CHANNELS, EPG_DOWNLOAD, EPG_PARSE, MATCHING }

    enum class Warning {
        /** Le calendrier distant n'a pas pu etre verifie (reseau, CDN incoherent) : calendrier en cache utilise. */
        FIXTURES_FROM_CACHE,
    }
}

enum class RefreshError(val retryable: Boolean) {
    /** Build sans cle publique / URL de calendrier valide. */
    NOT_CONFIGURED(false),
    /** Aucun identifiant Xtream enregistre. */
    NO_ACCOUNT(false),
    NETWORK(true),
    TIMEOUT(true),
    /** Echec TLS (certificat invalide, redirection HTTPS -> HTTP refusee...). */
    TLS(false),
    /** Identifiants Xtream refuses. */
    AUTH(false),
    SERVER(true),
    /** Reponse du serveur IPTV inexploitable. */
    INVALID_RESPONSE(true),
    /** Reponse au-dela des tailles maximales autorisees. */
    TOO_LARGE(false),
    /** EPG XMLTV illisible, tronque ou refuse (entites XML). */
    EPG_INVALID(true),
    /** Le fournisseur renvoie un guide TV vide (maintenance, limitation) : reessai automatique plus tard. */
    EPG_EMPTY(true),
    /** Calendrier injoignable ou non verifiable et aucun cache valide. */
    FIXTURES_UNAVAILABLE(true),
    /** Calendrier expire (publisher arrete ?) et aucun cache valide. */
    FIXTURES_EXPIRED(true),
    /** Stockage local (espace disque...). */
    STORAGE(true),
    UNKNOWN(true),
}
