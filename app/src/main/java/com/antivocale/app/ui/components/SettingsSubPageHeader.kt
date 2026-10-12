package com.antivocale.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.antivocale.app.R
import com.antivocale.app.ui.AppNavigation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.flow.first

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

/**
 * TASK-815: [SubPageScaffold] for the pages whose body is the standard
 * scroll Column. The scaffold owns the scroll state and the body wrapper
 * (fillMaxSize + verticalScroll + the start/end/bottom margin +
 * spacedBy(16)), so the page contributes only its cards, and it executes
 * the programmatic [AppNavigation.ScrollRequest] a nav broadcast may carry:
 * device trials scroll through the SPI instead of emulating touch swipes.
 *
 * The content receives the [ScrollState] because two pages (Automation,
 * Performance) run focus-flash math against it; pages that don't need it
 * ignore the parameter.
 *
 * Re-fire contract: the effect keys on the request itself, whose seq is
 * unique per broadcast, so scrolling "bottom" twice scrolls twice. ToTop
 * jumps (the top is one page away at most); the other directives animate,
 * which is what a dump taken right after the ack observes as settled state.
 */
@Composable
fun SubPageScrollScaffold(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    scrollRequest: AppNavigation.ScrollRequest?,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {},
    onScrollConsumed: () -> Unit = {},
    onBodyPositioned: ((rootY: Int) -> Unit)? = null,
    /** Pages whose old body had no Arrangement (Export, Gemma) pass Top. */
    bodySpacing: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    /** Pages whose focus-flash math runs outside the slot pass their own. */
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.(scrollState: ScrollState) -> Unit,
) {
    SubPageScaffold(
        titleRes = titleRes,
        onBack = onBack,
        modifier = modifier,
        headerActions = headerActions,
    ) {
        // The viewport height in px, captured at layout; ByViewports scrolls
        // in multiples of THIS, not of the total scrollable extent.
        var viewportPx by remember { mutableIntStateOf(1) }
        LaunchedEffect(scrollRequest) {
            val request = scrollRequest ?: return@LaunchedEffect
            android.util.Log.d("SubPageScroll", "executing $request")
            try {
                when (val directive = request.directive) {
                    AppNavigation.ScrollDirective.ToTop -> scrollState.scrollTo(0)
                    AppNavigation.ScrollDirective.ToBottom -> {
                        // maxValue is 0 until the first measure; first()
                        // (not first{it>0}) also releases on a page whose
                        // content fits the viewport, where the scroll is a
                        // no-op instead of an eternal wait.
                        snapshotFlow { scrollState.maxValue }.first()
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                    is AppNavigation.ScrollDirective.ByViewports -> {
                        snapshotFlow { scrollState.maxValue }.first()
                        scrollState.animateScrollBy(directive.viewports * viewportPx)
                    }
                }
            } finally {
                // ALWAYS: a user touch cancels animateScrollTo mid-flight,
                // and an unconsumed request would fire again on the page's
                // next manual open. Nulling the request first would relaunch
                // this effect with null and cancel the animation, so the
                // consume belongs after (or, on cancellation, instead of)
                // the scroll.
                onScrollConsumed()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                // AFTER verticalScroll on purpose: a node placed after the
                // scroll sees content coordinates that SHIFT with scrolling
                // (foundation's ScrollNode is a LayoutModifierNode), which
                // is what flashIn's rowY-minus-root invariance expects; the
                // old pages captured at this same chain position. The size
                // is the viewport either way.
                .onGloballyPositioned {
                    viewportPx = it.size.height
                    onBodyPositioned?.invoke(it.positionInRoot().y.toInt())
                }
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = bodySpacing,
        ) {
            content(scrollState)
        }
    }
}
