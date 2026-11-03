package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.catalog.CatalogDisplay
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogStringKeys

/**
 * TASK-949: the non-composable Context resolvers of the placeholder
 * pipeline (TASK-946/947), relocated beside [CatalogVariantUi] so the
 * transcription layer no longer imports from ui.components (the composable
 * twins stay there: they need Compose). The argument list comes from the
 * policy owners in CatalogVariantUi.kt ([CatalogEntry.titleFormatArgs] /
 * [CatalogEntry.descriptionFormatArgs], [ModelVariant.titleFormatArgs] /
 * [ModelVariant.descriptionFormatArgs]) and is empty when the display
 * declares no slots, so a site resolving through these wrappers always
 * formats every slot the string carries. The correct form is this
 * greppable call, never a bare getString(titleResId) (the qwen3 leak
 * class; the bash guard's call-site section enforces the grep).
 */
fun Context.placeholderAwareString(resId: Int, formatArgs: List<Any>): String =
    if (formatArgs.isEmpty()) getString(resId)
    else getString(resId, *formatArgs.toTypedArray())

/**
 * TASK-947: the ONE title resolve for a [CatalogVariantUi]. Slotted titles
 * (whistle, qwen3) format their catalog args here.
 */
fun Context.variantTitle(ui: CatalogVariantUi): String =
    placeholderAwareString(ui.titleResId, ui.titleFormatArgs)

/** The [ModelVariant] member: same resolve, the variant's own title slots. */
fun Context.variantTitle(v: ModelVariant): String =
    placeholderAwareString(v.titleResId, v.titleFormatArgs)

/**
 * TASK-949: the ONE entry-level title resolve. The resId and the format args
 * come back PAIRED so a call site cannot split them (the mismatch class the
 * ModelTab fallback carried before this task). Null when the entry display is
 * a literal or absent: the caller owns its fallback title and must pair the
 * fallback with its OWN args (e.g. [CatalogVariantUi.titleFormatArgs]), never
 * with the entry's.
 */
fun CatalogEntry.resourceTitlePair(): Pair<Int, List<Any>>? =
    (display as? CatalogDisplay.Resource)?.let {
        CatalogStringKeys.resolve(it.key) to titleFormatArgs()
    }
