package fr.footballrama.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import fr.footballrama.app.ui.theme.FrColors
import fr.footballrama.app.ui.theme.FrText

private val Shape = RoundedCornerShape(8.dp)
private const val FOCUS_SCALE = 1.04f
private const val ROW_FOCUS_SCALE = 1.015f

/** Bouton TV : bordure ambre 3 dp + leger agrandissement au focus. */
@Composable
fun FrButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(Shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) FrColors.Accent else FrColors.Surface,
            contentColor = if (primary) FrColors.OnAccent else FrColors.Text,
            focusedContainerColor = if (primary) FrColors.AccentFocused else FrColors.SurfaceFocused,
            focusedContentColor = if (primary) FrColors.OnAccent else FrColors.Text,
            pressedContainerColor = if (primary) FrColors.AccentFocused else FrColors.SurfaceFocused,
            pressedContentColor = if (primary) FrColors.OnAccent else FrColors.Text,
            disabledContainerColor = FrColors.Surface,
            disabledContentColor = FrColors.TextMuted,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, FrColors.Outline), shape = Shape),
            focusedBorder = Border(BorderStroke(3.dp, if (primary) FrColors.Text else FrColors.Accent), shape = Shape),
            pressedBorder = Border(BorderStroke(3.dp, if (primary) FrColors.Text else FrColors.Accent), shape = Shape),
        ),
    ) {
        Text(
            text = text,
            style = FrText.Label,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }
}

/** Carte / ligne cliquable pleine largeur (match). */
@Composable
fun FocusRowCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(Shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FrColors.Surface,
            contentColor = FrColors.Text,
            focusedContainerColor = FrColors.SurfaceFocused,
            focusedContentColor = FrColors.Text,
            pressedContainerColor = FrColors.SurfaceFocused,
            pressedContentColor = FrColors.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ROW_FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, FrColors.Accent), shape = Shape),
            pressedBorder = Border(BorderStroke(3.dp, FrColors.Accent), shape = Shape),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * Ligne focusable non cliquable (chaine dans le detail) : permet de parcourir et faire defiler la liste
 * a la telecommande, avec le meme indicateur de focus.
 */
@Composable
fun FocusableInfoRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = if (focused) ROW_FOCUS_SCALE else 1f
                scaleX = s
                scaleY = s
            }
            .clip(Shape)
            .background(if (focused) FrColors.SurfaceFocused else FrColors.Surface)
            .border(if (focused) 3.dp else 1.dp, if (focused) FrColors.Accent else Color.Transparent, Shape)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
fun Badge(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = FrText.Label, color = foreground, maxLines = 1)
    }
}

enum class BannerKind { INFO, WARNING, ERROR }

/** Bandeau d'etat sobre ; [action] optionnelle (ex. "Reessayer"). */
@Composable
fun Banner(
    kind: BannerKind,
    text: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val (bg, fg) = when (kind) {
        BannerKind.INFO -> FrColors.InfoBg to FrColors.InfoText
        BannerKind.WARNING -> FrColors.WarningBg to FrColors.WarningText
        BannerKind.ERROR -> FrColors.ErrorBg to FrColors.ErrorText
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Shape,
        colors = SurfaceDefaults.colors(containerColor = bg, contentColor = fg),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = text, style = FrText.Body, color = fg)
                if (secondary != null) Text(text = secondary, style = FrText.Small, color = fg)
            }
            if (action != null) {
                Spacer(Modifier.width(16.dp))
                action()
            }
        }
    }
}

/** Barre de progression simple ; [progress] null = indeterminee (barre partielle fixe). */
@Composable
fun ProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(FrColors.Outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress?.coerceIn(0f, 1f) ?: 0.3f)
                .height(6.dp)
                .background(FrColors.Accent),
        )
    }
}

/** Mention de licence football-data.org : texte exact, non traduit, toujours visible. */
@Composable
fun AttributionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = FrText.Small,
        color = FrColors.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/**
 * Champ de saisie TV. Le clavier virtuel ne s'ouvre pas au simple passage du focus (navigation D-pad
 * fluide) mais a l'appui sur OK/Entree. Bordure ambre 3 dp au focus.
 */
@Composable
fun FrTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier = modifier) {
        Text(text = label, style = FrText.Label, color = if (focused) FrColors.Accent else FrColors.TextMuted)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = FrText.Body.copy(color = FrColors.Text),
            cursorBrush = SolidColor(FrColors.Accent),
            keyboardOptions = keyboardOptions.copy(showKeyboardOnFocus = false),
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .onPreviewKeyEvent { event ->
                    // OK / Entree de la telecommande : ouvre le clavier virtuel (sans consommer l'evenement).
                    if (event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                    ) {
                        keyboard?.show()
                    }
                    false
                },
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Shape)
                        .background(if (focused) FrColors.SurfaceFocused else FrColors.Surface)
                        .border(if (focused) 3.dp else 1.dp, if (focused) FrColors.Accent else FrColors.Outline, Shape)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(text = placeholder, style = FrText.Body, color = FrColors.TextMuted, maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
}
