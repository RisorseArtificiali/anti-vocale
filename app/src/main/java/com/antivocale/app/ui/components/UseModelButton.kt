package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antivocale.app.R

/**
 * TASK-786: the labelled activate button shared by the catalog and external
 * model cards (icon plus word, the Download button's pattern). TASK-381:
 * the 48dp minimum touch target is enforced here for every caller. The
 * label ellipsizes: long locales share a row with the Update and Delete
 * actions, and the row must never push a sibling off the card.
 */
@Composable
fun UseModelButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            stringResource(R.string.use_model),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
