package com.antivocale.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.antivocale.app.R

/**
 * TASK-749: the ONE settings sub-page header (back button + page title +
 * optional trailing actions). Extracted from seven hand-rolled copies that
 * had drifted (spacings, title styles, an explicit tint, a doubled status
 * inset); the shared tokens are the TASK-748 family's: spacedBy(8),
 * headlineSmall Bold, [R.string.back] on the arrow. Being its own
 * composable also gives the header a recompose scope separate from the
 * screen root (PerApp re-rendered its header on every list-row tap).
 *
 * The caller owns insets and outer padding: most pages embed this as the
 * first child of their padded scroll Column with the default
 * fillMaxWidth; PerApp, whose body is a LazyColumn, pads it itself.
 *
 * The STATUS-BAR inset is owned one level higher still: the host
 * MainScreen Column already applies statusBarsPadding for every tab
 * content, so a sub-page root must NOT add it again (LauncherIcon did
 * exactly that and rendered double-insetted until TASK-749).
 */
@Composable
fun SettingsSubPageHeader(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.weight(1f))
        actions()
    }
}
