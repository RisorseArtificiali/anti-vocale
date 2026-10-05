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
