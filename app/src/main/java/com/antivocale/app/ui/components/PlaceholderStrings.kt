package com.antivocale.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.antivocale.app.transcription.CatalogVariantUi
import com.antivocale.app.transcription.ModelVariant

/**
 * TASK-946: the ONE resolution for a display string whose placeholders (if
 * any) come from the catalog. The argument list comes from the policy
 * owners in the transcription package (CatalogEntry.titleFormatArgs /
 * descriptionFormatArgs, ModelVariant.titleFormatArgs /
 * descriptionFormatArgs) and is empty when the display declares no slots,
 * so a site resolving through these wrappers always formats every slot the
 * string carries.
 *
 * The non-composable Context twins (placeholderAwareString, variantTitle)
 * live in transcription/CatalogPlaceholders.kt since TASK-949, beside
 * CatalogVariantUi; only the Compose-bound forms stay here.
 */
@Composable
fun placeholderAwareStringResource(resId: Int, formatArgs: List<Any>): String =
    if (formatArgs.isEmpty()) stringResource(resId)
    else stringResource(resId, *formatArgs.toTypedArray())

/** The composable twin for title render sites. */
@Composable
fun CatalogVariantUi.titleRes(): String =
    placeholderAwareStringResource(titleResId, titleFormatArgs)

/** The composable twin. */
@Composable
fun ModelVariant.titleRes(): String =
    placeholderAwareStringResource(titleResId, titleFormatArgs)
