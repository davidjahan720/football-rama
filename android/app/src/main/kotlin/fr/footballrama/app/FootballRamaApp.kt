package fr.footballrama.app

import android.app.Application
import fr.footballrama.app.data.RefreshScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FootballRamaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            if (container.credentialStore.hasAccount.first()) RefreshScheduler.schedulePeriodic(this@FootballRamaApp)
        }
    }
}
