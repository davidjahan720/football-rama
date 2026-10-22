package fr.footballrama.app.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.footballrama.app.BuildConfig
import fr.footballrama.app.FootballRamaApp
import fr.footballrama.app.R
import fr.footballrama.app.ui.Banner
import fr.footballrama.app.ui.BannerKind
import fr.footballrama.app.ui.FrButton
import fr.footballrama.app.ui.ProgressBar
import fr.footballrama.app.ui.theme.FrColors
import fr.footballrama.app.ui.theme.FrText
import fr.footballrama.app.update.UpdateError
import fr.footballrama.app.update.UpdateState
import kotlinx.coroutines.launch
import java.util.Locale

/** Section « Mise a jour de l'application » des reglages (version installee, recherche, installation). */
@Composable
fun UpdateSection(modifier: Modifier = Modifier) {
    val container = (LocalContext.current.applicationContext as FootballRamaApp).container
    val manager = container.updateManager
    val state by manager.state.collectAsStateWithLifecycle()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.app_update_title), style = FrText.Label, color = FrColors.TextMuted)
        Text(stringResource(R.string.app_update_current, BuildConfig.VERSION_NAME), style = FrText.Body, color = FrColors.Text)

        when (val s = state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Text(stringResource(R.string.app_update_checking), style = FrText.Label, color = FrColors.InfoText)
            UpdateState.UpToDate -> Text(stringResource(R.string.app_update_up_to_date), style = FrText.Label, color = FrColors.TextMuted)
            is UpdateState.Available -> {
                Banner(
                    kind = BannerKind.INFO,
                    text = stringResource(R.string.app_update_available, s.versionName),
                    secondary = listOfNotNull(
                        stringResource(R.string.app_update_size, megabytes(s.sizeBytes)),
                        s.notes.takeIf { it.isNotBlank() },
                    ).joinToString("\n"),
                )
            }
            is UpdateState.Downloading -> {
                Text(
                    stringResource(R.string.app_update_downloading, (s.progress * 100).toInt()),
                    style = FrText.Label,
                    color = FrColors.InfoText,
                )
                ProgressBar(progress = s.progress)
            }
            is UpdateState.Verifying -> Text(stringResource(R.string.app_update_verifying), style = FrText.Label, color = FrColors.InfoText)
            is UpdateState.Installing -> Text(stringResource(R.string.app_update_installing), style = FrText.Label, color = FrColors.InfoText)
            is UpdateState.NeedsPermission -> Banner(kind = BannerKind.WARNING, text = stringResource(R.string.app_update_needs_permission))
            is UpdateState.Failed -> Banner(kind = BannerKind.ERROR, text = stringResource(errorText(s.error)))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is UpdateState.Available, is UpdateState.NeedsPermission, is UpdateState.Installing ->
                    FrButton(stringResource(R.string.app_update_install), onClick = manager::install, primary = true)
                is UpdateState.Failed -> FrButton(stringResource(R.string.app_update_retry), onClick = manager::install, primary = true)
                else -> Unit
            }
            val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Verifying
            FrButton(
                text = stringResource(R.string.app_update_check),
                onClick = { container.appScope.launch { manager.checkIfDue(force = true) } },
                enabled = !busy,
            )
        }
    }
}

/**
 * Bandeau discret « Mise a jour X disponible » pour l'ecran principal (a brancher par l'UI) :
 * n'affiche rien hors de l'etat [UpdateState.Available].
 */
@Composable
fun UpdateBanner(state: UpdateState, modifier: Modifier = Modifier) {
    if (state is UpdateState.Available) {
        Banner(kind = BannerKind.INFO, text = stringResource(R.string.app_update_banner, state.versionName), modifier = modifier)
    }
}

@StringRes
private fun errorText(error: UpdateError): Int = when (error) {
    UpdateError.NETWORK -> R.string.app_update_error_network
    UpdateError.INTEGRITY -> R.string.app_update_error_integrity
    UpdateError.STORAGE -> R.string.app_update_error_storage
    UpdateError.INSTALL_FAILED -> R.string.app_update_error_install
    UpdateError.INSTALL_BLOCKED -> R.string.app_update_error_blocked
    UpdateError.PERMISSION_SCREEN_UNAVAILABLE -> R.string.app_update_error_permission_screen
}

private fun megabytes(bytes: Long): String = String.format(Locale.FRANCE, "%.1f", bytes / (1024.0 * 1024.0))
