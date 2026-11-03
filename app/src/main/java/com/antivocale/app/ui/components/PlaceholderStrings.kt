package com.antivocale.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.antivocale.app.transcription.CatalogVariantUi
import com.antivocale.app.transcription.ModelVariant

/**
 * TASK-946: the ONE composable resolution for a display string whose
 * placeholders (if any) come from the catalog; see transcription/
 * CatalogPlaceholders.kt for the policy (args come from the CatalogVariantUi.kt
 * owners; an empty list resolves the string plainly). The non-composable
 * Context twins live there since TASK-949; only the Compose-bound forms
 * stay here.
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
