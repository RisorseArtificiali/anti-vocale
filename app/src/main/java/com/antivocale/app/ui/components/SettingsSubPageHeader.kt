package com.antivocale.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
 * TASK-752: [SubPageScaffold] is this component's only caller and owns
 * the insets, the outer margin, and the PINNED placement (the header does
 * not scroll away with a page's content); new sub-pages go through the
 * scaffold, never hand-embed the header.
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

/**
 * TASK-752: the ONE sub-page root (settings pages and the ModelTab Gemma
 * page; navigation-bar inset + the
 * shared header at the standard 16dp margin + a free-form body slot),
 * replacing the seven hand-rolled root chains that had drifted into three
 * different header-padding value sets (and LauncherIcon's own 4/8
 * alignment). The one header-alignment rule (the 749 F2 question): the
 * back glyph sits at the page margin on every page alike; IconButton's
 * internal 12dp inset is the only extra indent, the tighter Launcher
 * override is gone.
 *
 * The body slot stays the page's concern: scroll Column (with or without a
 * captured scroll state), LazyColumn under a Box, plain Column. Body
 * containers pad themselves on start/end/bottom; the header's bottom
 * margin is the single header-to-content gap, so a body must NOT add a
 * top padding of its own. The status-bar inset is still owned one level
 * higher by the host MainScreen Column (see [SettingsSubPageHeader]).
 */
@Composable
fun SubPageScaffold(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        SettingsSubPageHeader(
            titleRes = titleRes,
            onBack = onBack,
            modifier = Modifier.padding(16.dp),
            actions = headerActions,
        )
        content()
    }
}
