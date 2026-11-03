package com.antivocale.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * TASK-946: the ONE resolution for a display string whose placeholders (if
 * any) come from the catalog. The argument list comes from the policy
 * owners in CatalogVariantUi (CatalogEntry.titleFormatArgs /
 * descriptionFormatArgs, ModelVariant.titleFormatArgs /
 * descriptionFormatArgs) and is empty when the display declares no slots,
 * so a site resolving through these wrappers always formats every slot the
 * string carries. The Context twin exists because stringResource and
 * Context.getString are different APIs.
 */
@Composable
fun placeholderAwareStringResource(resId: Int, formatArgs: List<Any>): String =
    if (formatArgs.isEmpty()) stringResource(resId)
    else stringResource(resId, *formatArgs.toTypedArray())

/** The Context twin for non-composable sites (registry, orchestrator). */
fun android.content.Context.placeholderAwareString(resId: Int, formatArgs: List<Any>): String =
    if (formatArgs.isEmpty()) getString(resId)
    else getString(resId, *formatArgs.toTypedArray())
