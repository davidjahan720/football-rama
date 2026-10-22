package fr.footballrama.app.ui.login

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.footballrama.app.R
import fr.footballrama.app.pairing.QrMatrix
import fr.footballrama.app.ui.Banner
import fr.footballrama.app.ui.BannerKind
import fr.footballrama.app.ui.FrButton
import fr.footballrama.app.ui.theme.FrColors
import fr.footballrama.app.ui.theme.FrText
import fr.footballrama.app.ui.theme.Safe
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Ecran "Se connecter avec mon telephone". Le serveur local ne tourne QUE pendant que ce panneau est affiche
 * et que l'application est au premier plan (ON_START -> start, ON_STOP / sortie -> stop).
 * FLAG_SECURE (pose par LoginScreen) reste actif : le QR code et le code ne peuvent pas etre captures.
 */
@Composable
fun PhoneLoginPanel(vm: PhoneLoginViewModel, onManual: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.start()
                Lifecycle.Event.ON_STOP -> vm.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer) // rejoue ON_START si deja au premier plan
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stop()
        }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(state::class) { runCatching { primaryFocus.requestFocus() } }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(FrColors.Background)
            .padding(horizontal = Safe.Horizontal, vertical = Safe.Vertical),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(modifier = Modifier.weight(0.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.phone_title), style = FrText.Title, color = FrColors.Text)
            when (val s = state) {
                is PhoneLoginViewModel.UiState.Ready -> ReadyInstructions(s)
                PhoneLoginViewModel.UiState.Idle, PhoneLoginViewModel.UiState.Starting ->
                    Text(stringResource(R.string.phone_starting), style = FrText.Body, color = FrColors.TextMuted)
                PhoneLoginViewModel.UiState.Success ->
                    Banner(kind = BannerKind.INFO, text = stringResource(R.string.phone_success))
                PhoneLoginViewModel.UiState.NoNetwork -> Banner(kind = BannerKind.ERROR, text = stringResource(R.string.phone_no_network))
                PhoneLoginViewModel.UiState.StartFailed -> Banner(kind = BannerKind.ERROR, text = stringResource(R.string.phone_start_failed))
                PhoneLoginViewModel.UiState.Expired -> Banner(kind = BannerKind.WARNING, text = stringResource(R.string.phone_expired))
                PhoneLoginViewModel.UiState.Locked -> Banner(kind = BannerKind.ERROR, text = stringResource(R.string.phone_locked))
            }
            Text(stringResource(R.string.phone_home_network), style = FrText.Small, color = FrColors.WarningText)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val canRestart = state is PhoneLoginViewModel.UiState.Expired || state is PhoneLoginViewModel.UiState.Locked ||
                    state is PhoneLoginViewModel.UiState.NoNetwork || state is PhoneLoginViewModel.UiState.StartFailed
                if (canRestart) {
                    FrButton(
                        text = stringResource(R.string.phone_new_qr),
                        onClick = vm::start,
                        primary = true,
                        modifier = Modifier.focusRequester(primaryFocus),
                    )
                }
                FrButton(
                    text = stringResource(R.string.phone_manual),
                    onClick = onManual,
                    modifier = if (canRestart) Modifier else Modifier.focusRequester(primaryFocus),
                )
            }
        }

        Box(modifier = Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val ready = state as? PhoneLoginViewModel.UiState.Ready
            if (ready != null) {
                val qr = remember(ready.url) { qrBitmap(ready.url) }
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.phone_qr_description),
                    filterQuality = FilterQuality.None, // modules nets, sans flou
                    modifier = Modifier.size(340.dp).clip(RoundedCornerShape(8.dp)).background(Color.White),
                )
            }
        }
    }
}

@Composable
private fun ReadyInstructions(s: PhoneLoginViewModel.UiState.Ready) {
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(s.expiresAtElapsed) {
        while (true) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val left = ((s.expiresAtElapsed - nowElapsed) / 1000).coerceAtLeast(0)

    Text(stringResource(R.string.phone_step_scan), style = FrText.Body, color = FrColors.Text)
    Text(stringResource(R.string.phone_step_address, s.url), style = FrText.Label, color = FrColors.TextMuted)
    Text(stringResource(R.string.phone_step_code), style = FrText.Body, color = FrColors.Text)
    Text(
        s.code.toCharArray().joinToString(" "),
        style = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, lineHeight = 64.sp),
        color = FrColors.Accent,
    )
    Text(stringResource(R.string.phone_step_fill), style = FrText.Body, color = FrColors.Text)
    Text(
        stringResource(R.string.phone_expires_in, String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)),
        style = FrText.Label,
        color = FrColors.TextMuted,
    )
    when (val st = s.status) {
        PhoneLoginViewModel.Status.Waiting ->
            Banner(kind = BannerKind.INFO, text = stringResource(R.string.phone_status_waiting))
        PhoneLoginViewModel.Status.Validating ->
            Banner(kind = BannerKind.INFO, text = stringResource(R.string.phone_status_validating))
        is PhoneLoginViewModel.Status.WrongCode ->
            Banner(kind = BannerKind.WARNING, text = pluralStringResource(R.plurals.phone_status_wrong_code, st.remaining, st.remaining))
        is PhoneLoginViewModel.Status.Failed ->
            Banner(kind = BannerKind.ERROR, text = stringResource(R.string.phone_status_failed, st.message))
    }
}

private fun qrBitmap(url: String): ImageBitmap {
    val m = QrMatrix.encode(url)
    return Bitmap.createBitmap(m.toArgb(), m.size, m.size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
