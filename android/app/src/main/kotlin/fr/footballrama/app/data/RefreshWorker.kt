package fr.footballrama.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.footballrama.app.FootballRamaApp
import java.util.concurrent.TimeUnit

/** Rafraichissement en arriere-plan (WorkManager). Le Mutex du depot evite tout doublon avec l'UI. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FootballRamaApp).container
        // Mise a jour de l'app : au plus 1 verification / 12 h ; un echec n'affecte pas le rafraichissement.
        try {
            container.updateManager.checkIfDue()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return when (val state = container.matchRepository.refresh()) {
            is RefreshState.Error ->
                if (state.type.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

object RefreshScheduler {
    private const val PERIODIC = "football_rama_refresh_periodic"
    private const val ONE_SHOT = "football_rama_refresh_now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Rafraichissement toutes les 4 h, reseau requis, backoff exponentiel ; KEEP = jamais de doublon. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Rafraichissement immediat en arriere-plan (survit a la fermeture de l'ecran). */
    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(ONE_SHOT)
        }
    }
}
