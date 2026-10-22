package fr.footballrama.app.ui.login

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.footballrama.app.R
import fr.footballrama.app.ui.Banner
import fr.footballrama.app.ui.BannerKind
import fr.footballrama.app.ui.FrButton
import fr.footballrama.app.ui.FrTextField
import fr.footballrama.app.ui.theme.FrColors
import fr.footballrama.app.ui.theme.FrText
import fr.footballrama.app.ui.theme.Safe
import kotlinx.coroutines.delay

private const val EXIT_CONFIRM_WINDOW_MS = 3_000L

@Composable
fun LoginScreen(vm: LoginViewModel, phoneVm: PhoneLoginViewModel) {
    val activity = LocalActivity.current

    // Empeche captures d'ecran, enregistrement et apercu dans les applications recentes.
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Retour : ferme d'abord les options avancees, puis demande un second appui pour quitter.
    var lastBack by remember { mutableLongStateOf(0L) }
    var exitHint by remember { mutableStateOf(false) }
    // Connexion depuis le telephone : panneau plein ecran (serveur local actif uniquement pendant son affichage).
    var phoneMode by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        val now = System.currentTimeMillis()
        when {
            phoneMode -> phoneMode = false
            vm.advancedOpen -> vm.closeAdvanced()
            now - lastBack < EXIT_CONFIRM_WINDOW_MS -> activity?.finish()
            else -> { lastBack = now; exitHint = true }
        }
    }
    if (exitHint) {
        LaunchedEffect(lastBack) {
            delay(EXIT_CONFIRM_WINDOW_MS)
            exitHint = false
        }
    }

    if (phoneMode) {
        PhoneLoginPanel(phoneVm, onManual = { phoneMode = false })
        return
    }

    val phoneFocus = remember { FocusRequester() }
    val serverFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val uaFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }

    // Focus initial sur "Se connecter avec mon telephone" : evite la saisie fastidieuse a la telecommande.
    LaunchedEffect(Unit) { runCatching { phoneFocus.requestFocus() } }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(FrColors.Background)
            .padding(horizontal = Safe.Horizontal, vertical = Safe.Vertical),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // Colonne gauche : presentation, mentions, avertissement HTTP.
        Column(modifier = Modifier.weight(0.42f).fillMaxHeight()) {
            Text(stringResource(R.string.app_name), style = FrText.Display, color = FrColors.Text)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.login_subtitle), style = FrText.Body, color = FrColors.TextMuted)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.login_disclaimer), style = FrText.Body, color = FrColors.Text)
            if (vm.isCleartext) {
                Spacer(Modifier.height(20.dp))
                Banner(kind = BannerKind.WARNING, text = stringResource(R.string.login_cleartext_warning))
            }
            Spacer(Modifier.weight(1f))
            if (exitHint) {
                Text(stringResource(R.string.login_press_back_again), style = FrText.Body, color = FrColors.WarningText)
            }
        }

        // Colonne droite : formulaire (defilable : le champ focalise reste visible).
        Column(
            modifier = Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FrButton(
                text = stringResource(R.string.login_with_phone),
                onClick = { phoneMode = true },
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth().focusRequester(phoneFocus),
            )
            FrTextField(
                value = vm.server,
                onValueChange = vm::onServerChange,
                label = stringResource(R.string.login_server_label),
                placeholder = stringResource(R.string.login_server_placeholder),
                enabled = !vm.loading,
                modifier = Modifier.focusRequester(serverFocus),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = {
                    vm.normalizeServer()
                    runCatching { userFocus.requestFocus() }
                }),
            )
            FrTextField(
                value = vm.username,
                onValueChange = vm::onUsernameChange,
                label = stringResource(R.string.login_username_label),
                enabled = !vm.loading,
                modifier = Modifier.focusRequester(userFocus),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { runCatching { passFocus.requestFocus() } }),
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FrTextField(
                    value = vm.password,
                    onValueChange = vm::onPasswordChange,
                    label = stringResource(R.string.login_password_label),
                    enabled = !vm.loading,
                    modifier = Modifier.weight(1f).focusRequester(passFocus),
                    visualTransformation = if (vm.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        // Password : pas de suggestions, pas d'apprentissage du clavier, pas d'autocorrection.
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        runCatching { submitFocus.requestFocus() }
                        vm.submit()
                    }),
                )
                FrButton(
                    text = stringResource(if (vm.passwordVisible) R.string.login_hide_password else R.string.login_show_password),
                    onClick = vm::togglePasswordVisible,
                )
            }
            FrButton(
                text = stringResource(if (vm.advancedOpen) R.string.login_advanced_hide else R.string.login_advanced_show),
                onClick = vm::toggleAdvanced,
            )
            if (vm.advancedOpen) {
                FrTextField(
                    value = vm.userAgent,
                    onValueChange = vm::onUserAgentChange,
                    label = stringResource(R.string.ua_label),
                    placeholder = stringResource(R.string.ua_placeholder),
                    enabled = !vm.loading,
                    modifier = Modifier.focusRequester(uaFocus),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { runCatching { submitFocus.requestFocus() } }),
                )
                Text(stringResource(R.string.ua_help), style = FrText.Small, color = FrColors.TextMuted)
            }
            FrButton(
                text = stringResource(if (vm.loading) R.string.login_connecting else R.string.login_submit),
                onClick = vm::submit,
                primary = true,
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth().focusRequester(submitFocus),
            )
            vm.error?.let { Banner(kind = BannerKind.ERROR, text = stringResource(it)) }
            vm.info?.let { Banner(kind = BannerKind.INFO, text = stringResource(it)) }
            Spacer(Modifier.width(1.dp).height(8.dp))
        }
    }
}
