package com.antivocale.app.ui.components

import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.data.VoiceNoteSource
import com.antivocale.app.data.voiceNotesRoot
import com.antivocale.app.util.TreeUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TASK-272, layout v2 (maintainer pick 2026-10-14 over three rendered
 * variants: the v1 narrow left column was rejected): full-width rows. The
 * header is an icon tile plus the title with the state UNDER it; then the
 * description (missing state only), the path chip, and the actions
 * right-aligned on their own row. State + granted path always visible;
 * choose/change always available; remove only while a grant is held. The
 * breadcrumb shows the SAF display path when granted, the canonical
 * expected path when missing; only the storage-root LABEL is translated,
 * the directory names are derived in code from [voiceNotesRoot] (the one
 * owner of the path), so a translation can never corrupt the segments.
 * The tile header is the app's first state-header of this shape; a second
 * one should extract it instead of copying. One card per
 * [VoiceNoteSource] (WhatsApp Business support): the source picks the
 * expected path and the client-specific description.
 */
@Composable
fun VoiceNoteFolderGrantCard(
    granted: Boolean,
    uriString: String?,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
    source: VoiceNoteSource = VoiceNoteSource.WHATSAPP,
) {
    val segments = voiceNotePathSegments(granted, uriString, source)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CardCornerShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.voice_note_folder_card_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            if (granted) R.string.voice_note_folder_card_state_granted
                            else R.string.voice_note_folder_card_state_missing),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!granted) {
                Spacer(Modifier.height(10.dp))
                CardDescription(text = stringResource(
                    when (source) {
                        VoiceNoteSource.WHATSAPP -> R.string.voice_note_folder_card_desc
                        VoiceNoteSource.WHATSAPP_BUSINESS -> R.string.voice_note_business_folder_card_desc
                    }))
            }
            Spacer(Modifier.height(12.dp))
            MonospacePathChip(breadcrumbAnnotated(segments, boldFrom = if (granted) segments.size - 1 else 1))
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (granted) {
                    TextButton(onClick = onRemove) {
                        Text(
                            text = stringResource(R.string.voice_note_folder_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    FilledTonalButton(onClick = onChoose) {
                        Text(stringResource(R.string.voice_note_folder_change))
                    }
                } else {
                    Button(onClick = onChoose) {
                        Text(stringResource(R.string.voice_note_folder_choose))
                    }
                }
            }
        }
    }
}

/**
 * TASK-272: the 3-step guidance sheet shown before the system folder
 * picker (maintainer: "guidare l'utente alla cartella corretta"). The
 * picker itself opens only from the sheet's explicit action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceNoteFolderGrantSheet(
    onDismiss: () -> Unit,
    onOpenPicker: () -> Unit,
    source: VoiceNoteSource = VoiceNoteSource.WHATSAPP,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.voice_note_folder_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(14.dp))
            GrantSheetStep(1, stringResource(R.string.voice_note_folder_sheet_step1))
            GrantSheetStep(2, stringResource(R.string.voice_note_folder_sheet_step2)) {
                MonospacePathChip(
                    AnnotatedString(
                        voiceNotePathSegments(granted = false, uriString = null, source = source)
                            .joinToString(" › ")))
            }
            GrantSheetStep(3, stringResource(
                when (source) {
                    VoiceNoteSource.WHATSAPP -> R.string.voice_note_folder_sheet_step3
                    VoiceNoteSource.WHATSAPP_BUSINESS -> R.string.voice_note_business_folder_sheet_step3
                }))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onOpenPicker) {
                    Text(stringResource(R.string.voice_note_folder_sheet_open))
                }
            }
        }
    }
}

@Composable
private fun GrantSheetStep(number: Int, text: String, extraContent: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$number",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Column {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            extraContent()
        }
    }
}

/** The header tile and the path chip share one corner radius (quality
 *  review: the duplicated 10dp literal could drift). */
private val CardCornerShape = RoundedCornerShape(10.dp)

/** The card and sheet share one path chip: monospace on surfaceVariant,
 *  full width, rounded. */
@Composable
private fun MonospacePathChip(text: AnnotatedString) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardCornerShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** The path line: segments joined with the breadcrumb chevron, the
 *  segments from [boldFrom] on semibold. */
private fun breadcrumbAnnotated(segments: List<String>, boldFrom: Int): AnnotatedString =
    buildAnnotatedString {
        segments.forEachIndexed { index, segment ->
            if (index > 0) append(" › ")
            if (index >= boldFrom) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(segment) }
            } else {
                append(segment)
            }
        }
    }

/**
 * The breadcrumb segments: the localized storage-root label plus the real
 * directory names. Granted: the SAF display path of the picked tree;
 * missing: the canonical expected path from [voiceNotesRoot]. The
 * expected path is the synchronous initial value (pure string work), and
 * the granted display path resolves on Dispatchers.IO because its
 * cloud-tree fallback is a documents-provider query (review F4: never on
 * the composition thread).
 */
@Composable
private fun voiceNotePathSegments(
    granted: Boolean,
    uriString: String?,
    source: VoiceNoteSource,
): List<String> {
    val context = LocalContext.current
    val storageRootLabel = stringResource(R.string.voice_note_folder_storage_root)
    return produceState(
        initialValue = segmentsUnderLabel(storageRootLabel, voiceNotesRoot(source).absolutePath),
        granted,
        uriString,
        // The label is an input too: a locale change with a live grant
        // must re-resolve the breadcrumb (efficiency review, 2026-10-14).
        storageRootLabel,
    ) {
        if (granted && uriString != null) {
            val path = withContext(Dispatchers.IO) {
                TreeUris.displayPath(context, Uri.parse(uriString))
            }
            value = segmentsUnderLabel(storageRootLabel, path)
        }
    }.value
}

/** The storage prefix is stripped so the localized label replaces it; a
 *  path that is NOT under device storage (a cloud-tree grant, which the
 *  name-based pick validation admits) keeps its own segments: the label
 *  would claim a false location. */
private fun segmentsUnderLabel(storageRootLabel: String, path: String): List<String> {
    val storageRoot = Environment.getExternalStorageDirectory().absolutePath
    if (!path.startsWith(storageRoot)) {
        return path.split('/').filter { it.isNotBlank() }
    }
    val tail = path.removePrefix(storageRoot).split('/').filter { it.isNotBlank() }
    return listOf(storageRootLabel) + tail
}
