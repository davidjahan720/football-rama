package fr.footballrama.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.footballrama.app.ui.login.LoginScreen
import fr.footballrama.app.ui.login.LoginViewModel
import fr.footballrama.app.ui.main.MainScreen
import fr.footballrama.app.ui.main.MainViewModel
import fr.footballrama.app.ui.theme.FootballRamaTheme
import fr.footballrama.app.ui.theme.FrColors

/** Activite unique (seule exportee, pour le lanceur). Aucun deep link, aucun Intent externe traite. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as FootballRamaApp).container
        setContent {
            FootballRamaTheme {
                AppRoot(container)
            }
        }
    }
}

@Composable
private fun AppRoot(container: AppContainer) {
    val hasAccount by container.credentialStore.hasAccount.collectAsStateWithLifecycle(initialValue = null)
    Box(Modifier.fillMaxSize().background(FrColors.Background)) {
        when (hasAccount) {
            null -> Unit // lecture du stockage en cours (quelques ms)
            false -> LoginScreen(viewModel(factory = viewModelFactory { initializer { LoginViewModel(container) } }))
            true -> MainScreen(viewModel(factory = viewModelFactory { initializer { MainViewModel(container) } }))
        }
    }
}
