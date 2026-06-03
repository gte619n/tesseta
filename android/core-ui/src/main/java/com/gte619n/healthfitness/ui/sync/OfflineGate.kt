package com.gte619n.healthfitness.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * IMPL-AND-20 (Phase 6) — the offline AI affordance (D17).
 *
 * The online-only AI / upload entry points (DEXA & blood-report PDF upload,
 * meal-photo capture, drug lookup, goals chat) are **disabled when offline** with
 * a clear "needs connection" message — nothing is queued (D17). Wrap such an
 * entry point's interactive content in this gate: when [online] is false it
 * replaces the content with [OfflineNotice]; when true it renders [content]
 * unchanged.
 *
 * The CRUD records these flows eventually produce are still fully offline-capable
 * — only the AI/streaming/multipart step is gated.
 */
@Composable
fun OfflineGate(
    online: Boolean,
    modifier: Modifier = Modifier,
    message: String = DEFAULT_OFFLINE_MESSAGE,
    content: @Composable () -> Unit,
) {
    if (online) {
        content()
    } else {
        OfflineNotice(message = message, modifier = modifier)
    }
}

/** The standalone "needs connection" banner used by [OfflineGate]. */
@Composable
fun OfflineNotice(
    message: String = DEFAULT_OFFLINE_MESSAGE,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = Hf.colors.textSecondary,
            modifier = Modifier.size(24.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Needs a connection",
                style = Hf.type.bodyMd,
                color = Hf.colors.textPrimary,
            )
            Text(
                message,
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
        }
    }
}

const val DEFAULT_OFFLINE_MESSAGE =
    "This uses AI and needs an internet connection. Reconnect to continue."
