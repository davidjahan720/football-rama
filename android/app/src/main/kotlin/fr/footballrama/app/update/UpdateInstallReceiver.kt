package fr.footballrama.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import fr.footballrama.app.FootballRamaApp

/**
 * Recoit le statut de la session PackageInstaller. NON exporte (android:exported="false") : seul le
 * PendingIntent explicite cree par [UpdateManager] (rempli par le systeme) peut l'atteindre.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as? FootballRamaApp ?: return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm: Intent? = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
        } else {
            null
        }
        app.container.updateManager.onInstallStatus(sessionId, status, confirm)
    }

    companion object {
        const val ACTION = "fr.footballrama.app.action.UPDATE_INSTALL_STATUS"
    }
}
