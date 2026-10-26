package tv.frostline.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** The icons of [FrostEmptyState], drawn in the muted colour. */
object StateIcons {
    @DrawableRes val Tv = R.drawable.ic_state_tv
    @DrawableRes val Search = R.drawable.ic_state_search
    @DrawableRes val Error = R.drawable.ic_state_error
    @DrawableRes val Person = R.drawable.ic_state_person
    @DrawableRes val Video = R.drawable.ic_state_video
}

/**
 * Empty and error state of a screen or list: an icon, a one-line message and (when there is something to do) a
 * [FrostButton]. [actionModifier] is applied to the button, so a caller can make it the focus target.
 */
@Composable
fun FrostEmptyState(
    @DrawableRes icon: Int,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionModifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = FrostColors.Muted, modifier = Modifier.size(48.dp))
        Text(message, style = MaterialTheme.typography.titleMedium, color = FrostColors.OnSurface, textAlign = TextAlign.Center, maxLines = 2)
        if (actionLabel != null && onAction != null) FrostButton(actionLabel, onAction, actionModifier)
    }
}
