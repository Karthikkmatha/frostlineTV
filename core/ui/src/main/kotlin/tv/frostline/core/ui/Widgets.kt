package tv.frostline.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * A single focus stop that doubles as a text field. Focusing it never opens the keyboard: the field is read-only
 * until OK (center/enter) is pressed, which makes it editable and shows the keyboard (on Google TV, the phone
 * keyboard). Moving focus away makes it read-only again. The ring follows [LocalFocusRingEnabled].
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
    /** True while the keyboard is allowed (after OK, until focus leaves). */
    onEditingChange: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = FrostTokens.RowShape
    LaunchedEffect(editing) {
        onEditingChange(editing)
        if (editing) keyboard?.show()
    }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = FrostTokens.RowMinHeight)
            .background(if (focused) FrostColors.ContainerFocused else FrostColors.Container, shape)
            .frostFocusBorder(focused, shape)
            .padding(horizontal = FrostTokens.HorizontalPadding, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = FrostColors.Muted, fontSize = 15.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            // Read-only fields do not start an input session, so focus alone cannot raise the IME.
            readOnly = !editing,
            singleLine = true,
            textStyle = TextStyle(color = FrostColors.OnSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(FrostColors.Violet),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            modifier = fieldModifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) { editing = false; okDown = false }
                }
                .onPreviewKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    when {
                        !ok -> false
                        e.type == KeyEventType.KeyDown -> { okDown = true; true }
                        // Only a press that started on this field opens the keyboard; the release of the
                        // OK that moved focus here (from the list) must not.
                        else -> {
                            if (okDown) { okDown = false; if (editing) keyboard?.show() else editing = true }
                            true
                        }
                    }
                },
        )
    }
}

/** A settings row with a trailing switch; OK toggles. The switch is a display of the state, the row is the focus target. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FrostListItem(
        onClick = { onToggle(!checked) },
        modifier = modifier.fillMaxWidth(),
        supporting = description?.let { d -> { Text(d, fontSize = 12.sp, color = FrostColors.Muted) } },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // The word as well as the switch, so state never relies on colour or shape alone.
                Text(if (checked) "On" else "Off", fontSize = 13.sp, color = FrostColors.Lavender)
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FrostColors.OnSurface,
                        checkedTrackColor = FrostColors.PrimaryFocused,
                        uncheckedThumbColor = FrostColors.Muted,
                        uncheckedTrackColor = FrostColors.SurfaceAlt,
                    ),
                )
            }
        },
    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

/** A settings row that cycles through choices on OK, showing the current one as text. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvChoiceRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    FrostListItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        supporting = description?.let { d -> { Text(d, fontSize = 12.sp, color = FrostColors.Muted) } },
        trailing = { Text(value, fontSize = 14.sp, color = FrostColors.Lavender, maxLines = 1) },
    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

/**
 * A settings row with a value that Left/Right change (stepper). OK enters adjust mode (Left/Right change the value;
 * OK or Back leaves it), otherwise Left/Right move focus as usual, so the row is never a trap. The hold-to-repeat
 * count is passed on so callers can accelerate.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvStepperRow(
    label: String,
    value: String,
    onStep: (dir: Int, repeat: Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    var adjusting by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = adjusting) { adjusting = false }
    FrostListItem(
        onClick = { adjusting = !adjusting },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.hasFocus) adjusting = false }
            .onPreviewKeyEvent { e ->
                if (!adjusting || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onStep(-1, e.nativeKeyEvent.repeatCount); true }
                    Key.DirectionRight -> { onStep(+1, e.nativeKeyEvent.repeatCount); true }
                    else -> false
                }
            },
        selected = adjusting,
        supporting = description?.let { d -> { Text(d, fontSize = 12.sp, color = FrostColors.Muted) } },
        trailing = {
            Text(
                if (adjusting) "\u25C0  $value  \u25B6" else "$value  (OK to adjust)",
                fontSize = 14.sp, color = FrostColors.OnSurface, maxLines = 1,
            )
        },
    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

/** A static, non-focusable placeholder shaped like a card; shown while a grid loads. Not animated, so it costs nothing. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(FrostColors.SurfaceAlt, FrostTokens.CardShape),
    )
}
