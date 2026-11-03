package com.antivocale.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.antivocale.app.transcription.CatalogVariantUi
import com.antivocale.app.transcription.ModelVariant

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

/**
 * TASK-947: the ONE title resolve for a [CatalogVariantUi]. Slotted titles
 * (whistle, qwen3) format their catalog args here; the correct form is this
 * greppable call, never a bare getString(titleResId) (the qwen3 leak class).
 */
fun Context.variantTitle(ui: CatalogVariantUi): String =
    placeholderAwareString(ui.titleResId, ui.titleFormatArgs)

/** The composable twin for title render sites. */
@Composable
fun CatalogVariantUi.titleRes(): String =
    placeholderAwareStringResource(titleResId, titleFormatArgs)

/** The ModelVariant member: same resolve, the variant's own title slots. */
fun Context.variantTitle(v: ModelVariant): String =
    placeholderAwareString(v.titleResId, v.titleFormatArgs)

/** The composable twin. */
@Composable
fun ModelVariant.titleRes(): String =
    placeholderAwareStringResource(titleResId, titleFormatArgs)
