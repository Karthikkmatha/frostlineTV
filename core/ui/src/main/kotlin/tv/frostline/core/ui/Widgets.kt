package tv.frostline.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val ring = LocalFocusRingEnabled.current
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        colors = ButtonDefaults.colors(
            containerColor = FrostColors.Primary,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.Primary,
            focusedContentColor = FrostColors.OnSurface,
            pressedContainerColor = FrostColors.Primary,
            pressedContentColor = FrostColors.OnSurface,
            disabledContainerColor = FrostColors.SurfaceAlt,
            disabledContentColor = FrostColors.Muted,
        ),
        border = ButtonDefaults.border(
            focusedBorder = if (ring) Border(BorderStroke(3.dp, FrostColors.Violet)) else Border.None,
        ),
    ) {
        Text(text, fontSize = 14.sp)
    }
}

/**
 * A single focus stop that doubles as a text field. OK (center/enter) opens the keyboard, which on
 * Google TV is the phone keyboard. The ring follows [LocalFocusRingEnabled].
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    /** Applied to the focusable text field itself (focus requesters, focus observers). */
    fieldModifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val ringEnabled = LocalFocusRingEnabled.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .fillMaxWidth()
            .background(if (focused) FrostColors.SurfaceFocused else FrostColors.Surface, shape)
            .border(if (focused && ringEnabled) 3.dp else 0.dp, FrostColors.Violet, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = FrostColors.Muted, fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = FrostColors.OnSurface, fontSize = 14.sp),
            cursorBrush = SolidColor(FrostColors.Violet),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            modifier = fieldModifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (ok && e.type == KeyEventType.KeyUp) {
                        keyboard?.show()
                        true
                    } else {
                        ok && e.type == KeyEventType.KeyDown
                    }
                },
        )
    }
}

/** A settings row that toggles on OK. Shows On/Off as text so state never relies on colour alone. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.tv.material3.Surface(
        onClick = { onToggle(!checked) },
        modifier = modifier.fillMaxWidth(),
        shape = cardShape(RoundedCornerShape(10.dp)),
        border = focusRingBorder(RoundedCornerShape(10.dp)),
        scale = cardScale(1f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = FrostColors.Surface,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.SurfaceFocused,
            focusedContentColor = FrostColors.OnSurface,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(label, fontSize = 15.sp)
                if (description != null) Text(description, fontSize = 12.sp, color = FrostColors.Muted)
            }
            Text(
                if (checked) "On" else "Off",
                fontSize = 13.sp,
                color = FrostColors.OnSurface,
                modifier = Modifier
                    .background(if (checked) FrostColors.Primary else FrostColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
    }
}
