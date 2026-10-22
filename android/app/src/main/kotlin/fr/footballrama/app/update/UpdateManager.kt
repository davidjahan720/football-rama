package fr.footballrama.app.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import fr.footballrama.core.update.ApkDownloader
import fr.footballrama.core.update.AppRelease
import fr.footballrama.core.update.FileIntegrity
import fr.footballrama.core.update.UpdateCheckResult
import fr.footballrama.core.update.UpdateDownloadException
import fr.footballrama.core.update.UpdateManifestClient
import fr.footballrama.core.update.UpdateManifestException
import fr.footballrama.core.update.UpdateManifestVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Mise a jour de l'application hors Play Store (APK publie sur GitHub Releases).
 *
 * Chaine de confiance :
 * 1. update.json est signe en Ed25519 (meme cle hors ligne que le calendrier) : verifie avant tout parsing ;
 * 2. apk_url doit commencer par le prefixe strict des releases GitHub du projet, HTTPS a chaque saut ;
 * 3. taille et SHA-256 du fichier telecharge = valeurs signees, verifies AVANT l'installation, puis une seconde
 *    fois sur les octets exacts ecrits dans la session PackageInstaller ;
 * 4. Android refuse en plus toute mise a jour signee par un autre certificat que l'app installee, et tout
 *    versionCode inferieur (pas de retour arriere).
 * L'installation est toujours declenchee par l'utilisateur et confirmee sur l'ecran systeme.
 */
class UpdateManager(
    private val context: Context,
    private val manifestClient: UpdateManifestClient,
    private val verifier: UpdateManifestVerifier,
    private val downloader: ApkDownloader,
    private val scope: CoroutineScope,
    private val currentVersionCode: Int,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val checkMutex = Mutex()
    private val updateDir = File(context.filesDir, "update")
    private val apkDir = File(context.cacheDir, "update-apk")

    /** Release verifiee correspondant a l'etat courant (jamais exposee a l'UI). */
    @Volatile
    private var release: AppRelease? = null

    @Volatile
    private var installJob: Job? = null

    @Volatile
    private var pendingSessionId: Int = -1

    init {
        scope.launch(Dispatchers.IO) {
            // Fichiers d'une installation interrompue (arret, crash) : jamais reutilises.
            apkDir.listFiles()?.forEach { it.delete() }
            restoreSavedManifest()
        }
    }

    /**
     * Verifie update.json au plus une fois toutes les 12 h (demarrage + RefreshWorker), sauf [force]
     * (bouton « Rechercher », limite a 1 fois par minute). Erreurs silencieuses : l'etat precedent est garde.
     */
    suspend fun checkIfDue(force: Boolean = false) {
        if (!manifestClient.isConfigured || !verifier.isConfigured) return
        checkMutex.withLock {
            if (isBusy()) return
            val now = System.currentTimeMillis()
            val next = prefs.getLong(KEY_NEXT_CHECK, 0L)
            val last = prefs.getLong(KEY_LAST_CHECK, 0L)
            // Horloge revenue en arriere (boitier sans RTC) : on ne reste pas bloque.
            val clockWentBack = now < last
            if (!clockWentBack) {
                if (force && now - last < MIN_FORCED_INTERVAL_MS) return
                if (!force && now < next) return
            }
            val previous = _state.value
            if (force) _state.value = UpdateState.Checking
            val outcome = withContext(Dispatchers.IO) { fetchAndVerify() }
            prefs.edit {
                putLong(KEY_LAST_CHECK, now)
                putLong(KEY_NEXT_CHECK, now + if (outcome == null) RETRY_AFTER_ERROR_MS else CHECK_INTERVAL_MS)
            }
            if (isBusy()) return
            _state.value = when (outcome) {
                null -> if (previous == UpdateState.Checking) UpdateState.Idle else previous
                is UpdateCheckResult.Available -> {
                    release = outcome.release
                    outcome.release.toAvailable()
                }
                else -> {
                    release = null
                    UpdateState.UpToDate
                }
            }
        }
    }

    /** null = echec (reseau, signature, manifeste invalide) : rien n'est change. */
    private fun fetchAndVerify(): UpdateCheckResult? {
        val signed = try {
            manifestClient.download()
        } catch (e: UpdateDownloadException) {
            // 404 : aucune release publiee (update.json absent du site) -> a jour.
            return if (e.kind == UpdateDownloadException.Kind.HTTP && e.httpCode == 404) {
                clearSavedManifest()
                UpdateCheckResult.UpToDate
            } else {
                null
            }
        }
        return try {
            val result = verifier.check(signed.json, signed.signature, currentVersionCode, Build.VERSION.SDK_INT)
            if (result is UpdateCheckResult.Available) saveManifest(signed.json, signed.signature) else clearSavedManifest()
            result
        } catch (_: UpdateManifestException) {
            null
        } catch (_: RuntimeException) {
            // Garde-fou : une verification ratee ne doit jamais faire planter l'app (appele au demarrage).
            null
        }
    }

    /** Installe la release disponible (action de l'utilisateur). */
    fun install() {
        val r = release ?: return
        if (isBusy()) return
        installJob = scope.launch(Dispatchers.IO) {
            try {
                doInstall(r)
            } catch (e: CancellationException) {
                _state.value = r.toAvailable()
                throw e
            } catch (_: Throwable) {
                // Aucune exception ne doit laisser l'UI bloquee sur « Telechargement ».
                _state.value = UpdateState.Failed(r.versionName, UpdateError.INSTALL_FAILED)
            } finally {
                installJob = null
            }
        }
    }

    private fun doInstall(r: AppRelease) {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(r.versionName)
            if (!openUnknownSourcesSettings()) {
                _state.value = UpdateState.Failed(r.versionName, UpdateError.PERMISSION_SCREEN_UNAVAILABLE)
            }
            return
        }

        apkDir.mkdirs()
        val apk = File(apkDir, "update.apk")
        apk.delete()
        try {
            _state.value = UpdateState.Downloading(r.versionName, 0f)
            var lastPercent = -1
            try {
                downloader.download(r, apk) { received ->
                    val percent = (received * 100 / r.size).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _state.value = UpdateState.Downloading(r.versionName, percent / 100f)
                    }
                }
            } catch (e: UpdateDownloadException) {
                _state.value = UpdateState.Failed(r.versionName, e.toUpdateError())
                return
            }

            _state.value = UpdateState.Verifying(r.versionName)
            if (!FileIntegrity.matches(apk, r.size, r.sha256)) {
                _state.value = UpdateState.Failed(r.versionName, UpdateError.INTEGRITY)
                return
            }
            commitSession(r, apk)
        } finally {
            // La session PackageInstaller a sa propre copie : le fichier temporaire n'est jamais conserve.
            apk.delete()
        }
    }

    private fun commitSession(r: AppRelease, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // La session ne peut installer QUE notre propre paquet.
            setAppPackageName(context.packageName)
            setSize(r.size)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (_: IOException) {
            _state.value = UpdateState.Failed(r.versionName, UpdateError.STORAGE)
            return
        }
        var committed = false
        val session = installer.openSession(sessionId)
        try {
            // Copie avec un second calcul du SHA-256 : on valide les octets exacts remis au systeme.
            val digest = MessageDigest.getInstance("SHA-256")
            session.openWrite("base.apk", 0, r.size).use { out ->
                apk.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                session.fsync(out)
            }
            if (!FileIntegrity.digestMatches(digest.digest(), r.sha256)) {
                _state.value = UpdateState.Failed(r.versionName, UpdateError.INTEGRITY)
                return
            }
            pendingSessionId = sessionId
            _state.value = UpdateState.Installing(r.versionName)
            session.commit(statusIntent(sessionId).intentSender)
            committed = true
        } catch (_: IOException) {
            _state.value = UpdateState.Failed(r.versionName, UpdateError.STORAGE)
        } finally {
            session.close()
            if (!committed) runCatching { installer.abandonSession(sessionId) }
        }
    }

    private fun statusIntent(sessionId: Int): PendingIntent {
        // Intent EXPLICITE vers un recepteur non exporte. FLAG_MUTABLE requis (API 31+) : le systeme y ajoute le statut.
        val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            .setAction(UpdateInstallReceiver.ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    /** Appele par [UpdateInstallReceiver] (statut de la session renvoye par le systeme). */
    internal fun onInstallStatus(sessionId: Int, status: Int, confirmIntent: Intent?) {
        if (sessionId != pendingSessionId) return
        val r = release ?: return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Ecran systeme de confirmation (« Voulez-vous installer cette mise a jour ? »).
                val ok = confirmIntent != null && runCatching {
                    context.startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!ok) _state.value = UpdateState.Failed(r.versionName, UpdateError.INSTALL_FAILED)
            }
            // En cas de succes, Android remplace l'app et arrete ce processus.
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                pendingSessionId = -1
                _state.value = r.toAvailable()
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> fail(r, UpdateError.INSTALL_BLOCKED)
            PackageInstaller.STATUS_FAILURE_STORAGE -> fail(r, UpdateError.STORAGE)
            else -> fail(r, UpdateError.INSTALL_FAILED)
        }
    }

    private fun fail(r: AppRelease, error: UpdateError) {
        pendingSessionId = -1
        _state.value = UpdateState.Failed(r.versionName, error)
    }

    /** Ecran « Installer des applis inconnues » pour CETTE app ; repli sur les parametres de securite. */
    private fun openUnknownSourcesSettings(): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        return intents.any { intent ->
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private fun isBusy(): Boolean = installJob?.isActive == true

    // --- Manifeste verifie memorise : le bandeau « mise a jour disponible » survit a un redemarrage. ---

    private fun saveManifest(json: ByteArray, sig: ByteArray) {
        runCatching {
            updateDir.mkdirs()
            File(updateDir, "update.json").writeBytes(json)
            File(updateDir, "update.json.sig").writeBytes(sig)
        }
    }

    private fun clearSavedManifest() {
        File(updateDir, "update.json").delete()
        File(updateDir, "update.json.sig").delete()
    }

    private fun restoreSavedManifest() {
        val json = File(updateDir, "update.json")
        val sig = File(updateDir, "update.json.sig")
        if (!json.isFile || !sig.isFile) return
        // Reverifie a chaque lecture (signature + version) : le fichier local n'est pas une source de confiance.
        val result = runCatching {
            verifier.check(json.readBytes(), sig.readBytes(), currentVersionCode, Build.VERSION.SDK_INT)
        }.getOrNull()
        if (result is UpdateCheckResult.Available) {
            if (_state.value == UpdateState.Idle && release == null) {
                release = result.release
                _state.value = result.release.toAvailable()
            }
        } else {
            clearSavedManifest()
        }
    }

    private fun AppRelease.toAvailable() = UpdateState.Available(versionName, notes, size)

    private fun UpdateDownloadException.toUpdateError(): UpdateError = when (kind) {
        UpdateDownloadException.Kind.SIZE_MISMATCH,
        UpdateDownloadException.Kind.HASH_MISMATCH,
        UpdateDownloadException.Kind.TOO_LARGE,
        UpdateDownloadException.Kind.NOT_HTTPS,
        -> UpdateError.INTEGRITY
        UpdateDownloadException.Kind.STORAGE -> UpdateError.STORAGE
        UpdateDownloadException.Kind.NETWORK, UpdateDownloadException.Kind.HTTP -> UpdateError.NETWORK
    }

    private companion object {
        const val PREFS = "app_update"
        const val KEY_LAST_CHECK = "last_check_ms"
        const val KEY_NEXT_CHECK = "next_check_ms"
        const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
        const val RETRY_AFTER_ERROR_MS = 60L * 60 * 1000
        const val MIN_FORCED_INTERVAL_MS = 60L * 1000
    }
}
