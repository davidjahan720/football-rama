package fr.footballrama.app.update

/** Etat de la mise a jour de l'application expose a l'UI (aucune URL ni detail technique). */
sealed interface UpdateState {
    /** Aucune information (pas encore verifie, ou verification impossible : silencieux). */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val versionName: String, val notes: String, val sizeBytes: Long) : UpdateState

    /** [progress] entre 0 et 1. */
    data class Downloading(val versionName: String, val progress: Float) : UpdateState

    data class Verifying(val versionName: String) : UpdateState

    /** Session d'installation soumise : l'ecran systeme d'installation prend le relais. */
    data class Installing(val versionName: String) : UpdateState

    /** L'utilisateur doit autoriser l'app a installer des applications (sources inconnues). */
    data class NeedsPermission(val versionName: String) : UpdateState

    data class Failed(val versionName: String, val error: UpdateError) : UpdateState
}

enum class UpdateError {
    NETWORK,
    /** Taille ou SHA-256 differents du manifeste signe : fichier supprime, rien n'est installe. */
    INTEGRITY,
    STORAGE,
    /** Echec signale par le systeme (certificat different, paquet invalide...). */
    INSTALL_FAILED,
    /** Installation bloquee par l'appareil (politique, sources inconnues refusees). */
    INSTALL_BLOCKED,
    /** L'ecran « sources inconnues » n'existe pas sur cet appareil. */
    PERMISSION_SCREEN_UNAVAILABLE,
}
