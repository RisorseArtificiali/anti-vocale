package com.antivocale.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * TASK-767: the two ONE-CALL resolutions for a string that may format the
 * catalog language count. The count argument (or null) comes from the
 * policy owners in CatalogVariantUi (CatalogEntry.titleCountArg /
 * descriptionCountArg, ModelVariant.titleFormatArg / descriptionFormatArg);
 * these wrappers exist because Compose's stringResource and
 * Context.getString are different APIs, and they make the forgotten-arg
 * single-armed form unrepresentable at a render site.
 */
@Composable
fun countAwareStringResource(resId: Int, countArg: Int?): String =
    countArg?.let { stringResource(resId, it) } ?: stringResource(resId)

/** The Context twin for non-composable sites (registry, orchestrator). */
fun android.content.Context.countAwareString(resId: Int, countArg: Int?): String =
    countArg?.let { getString(resId, it) } ?: getString(resId)

/**
 * TASK-942: the size-aware member of the same family. A sizePlaceholder
 * description formats the catalog download size (%1$d) and, when it also
 * carries countPlaceholder, the language count (%2$d); a count-only
 * description falls back to the TASK-767 single-arg resolution. Both
 * arguments come from the policy owners in CatalogVariantUi
 * (descriptionSizeArg / descriptionFormatArg), never from literals.
 */
@Composable
fun sizeAwareStringResource(resId: Int, sizeMB: Long?, countArg: Int?): String = when {
    sizeMB != null && countArg != null -> stringResource(resId, sizeMB, countArg)
    sizeMB != null -> stringResource(resId, sizeMB)
    else -> countAwareStringResource(resId, countArg)
}
