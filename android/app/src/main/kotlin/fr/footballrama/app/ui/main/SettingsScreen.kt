package fr.footballrama.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import fr.footballrama.app.BuildConfig
import fr.footballrama.app.R
import fr.footballrama.app.data.RefreshState
import fr.footballrama.app.ui.Banner
import fr.footballrama.app.ui.BannerKind
import fr.footballrama.app.ui.FrButton
import fr.footballrama.app.ui.FrTextField
import fr.footballrama.app.ui.Messages
import fr.footballrama.app.ui.theme.FrColors
import fr.footballrama.app.ui.theme.FrText
import fr.footballrama.app.ui.theme.Safe
import fr.footballrama.core.matching.MatchReport
import java.time.Instant

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    state: RefreshState,
    report: MatchReport?,
    now: Instant,
    onBack: () -> Unit,
) {
    val userAgent by vm.userAgent.collectAsStateWithLifecycle()
    val uaMessage by vm.userAgentMessage.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    val refreshFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { refreshFocus.requestFocus() } }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(FrColors.Background)
            .padding(horizontal = Safe.Horizontal, vertical = Safe.Vertical),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // Actions
        Column(
            modifier = Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_title), style = FrText.Title, color = FrColors.Text, modifier = Modifier.weight(1f))
                FrButton(stringResource(R.string.back), onClick = onBack)
            }

            Text(stringResource(R.string.settings_last_update), style = FrText.Label, color = FrColors.TextMuted)
            Text(
                report?.let { Formats.dateTime(Instant.ofEpochMilli(it.updatedAtMillis)) } ?: stringResource(R.string.settings_never),
                style = FrText.Body,
                color = FrColors.Text,
            )
            report?.let {
                Text(
                    stringResource(R.string.settings_epg_source, stringResource(Messages.epgSource(it.epgSource))),
                    style = FrText.Small,
                    color = FrColors.TextMuted,
                )
            }
            when (state) {
                is RefreshState.Loading -> Text(
                    stringResource(R.string.loading_step, stringResource(Messages.step(state.step))),
                    style = FrText.Label,
                    color = FrColors.InfoText,
                )
                is RefreshState.Error -> Text(refreshErrorText(state), style = FrText.Label, color = FrColors.ErrorText)
                else -> Unit
            }
            FrButton(
                text = stringResource(R.string.settings_refresh_now),
                onClick = vm::refresh,
                enabled = state !is RefreshState.Loading,
                modifier = Modifier.focusRequester(refreshFocus),
            )

            Spacer(Modifier.height(4.dp))
            FrTextField(
                value = userAgent,
                onValueChange = vm::onUserAgentChange,
                label = stringResource(R.string.ua_label),
                placeholder = stringResource(R.string.ua_placeholder),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.saveUserAgent() }),
            )
            Text(stringResource(R.string.ua_help), style = FrText.Small, color = FrColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FrButton(stringResource(R.string.ua_save), onClick = vm::saveUserAgent)
                FrButton(stringResource(R.string.ua_reset), onClick = vm::resetUserAgent)
            }
            uaMessage?.let { (res, ok) -> Banner(kind = if (ok) BannerKind.INFO else BannerKind.ERROR, text = stringResource(res)) }

            Spacer(Modifier.height(4.dp))
            UpdateSection()

            Spacer(Modifier.height(4.dp))
            FrButton(stringResource(R.string.sign_out), onClick = { confirmSignOut = true })
            Spacer(Modifier.height(8.dp))
        }

        // Informations et mentions
        Column(
            modifier = Modifier.weight(0.45f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.settings_about), style = FrText.Section, color = FrColors.Text)
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = FrText.Body, color = FrColors.Text)
            Text(stringResource(R.string.mention_neutral), style = FrText.Small, color = FrColors.TextMuted)
            Text(stringResource(R.string.mention_privacy), style = FrText.Small, color = FrColors.TextMuted)
            Text(stringResource(R.string.mention_attribution_intro), style = FrText.Small, color = FrColors.TextMuted)
            Text(vm.attribution, style = FrText.Small, color = FrColors.Text)
        }
    }

    if (confirmSignOut) {
        SignOutDialog(
            onConfirm = {
                confirmSignOut = false
                vm.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

@Composable
private fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 620.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(containerColor = FrColors.Surface, contentColor = FrColors.Text),
        ) {
            Column(modifier = Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.sign_out_confirm_title), style = FrText.Title, color = FrColors.Text)
                Text(stringResource(R.string.sign_out_confirm_body), style = FrText.Body, color = FrColors.TextMuted)
                Box(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Focus initial sur "Annuler" : une validation accidentelle n'efface rien.
                    FrButton(stringResource(R.string.cancel), onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus))
                    FrButton(stringResource(R.string.sign_out_confirm), onClick = onConfirm, primary = true)
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    }
}
