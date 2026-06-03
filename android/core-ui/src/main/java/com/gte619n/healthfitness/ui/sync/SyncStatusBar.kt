package com.gte619n.healthfitness.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * IMPL-AND-20 (Phase 6) — the global sync-state indicator (D11).
 *
 * A thin status pill placed at the top of in-scope screens (or app-wide). It is
 * pure/parameter-driven: the feature/app layer collects
 * [com.gte619n.healthfitness.data.sync.OutboxRepository.pendingCount],
 * connectivity, and the "updated elsewhere" signal, maps them to a [SyncUiState]
 * via [syncUiStateOf], and passes it here. Keeping the mapping a pure function
 * (testable on the JVM with no Compose) is the point.
 *
 * The bar self-hides when everything is synced and online and there is no note —
 * the steady state shows no chrome.
 */
@Composable
fun SyncStatusBar(
    state: SyncUiState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val visible = state.kind != SyncIndicatorKind.IDLE || state.updatedElsewhere
    AnimatedVisibility(visible = visible, modifier = modifier) {
        val palette = state.kind.palette()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bg, RoundedCornerShape(0.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = state.kind.icon(),
                contentDescription = null,
                tint = palette.fg,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = state.message(),
                style = Hf.type.bodySm,
                color = palette.fg,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null && state.kind == SyncIndicatorKind.FAILED) {
                TextButton(onClick = onRetry) {
                    Text("Retry", style = Hf.type.capsSm, color = palette.fg)
                }
            }
        }
    }
}

/**
 * Overlay sync indicator — drawn *on top of* the screen content (place it in a
 * [Box] over the nav graph) so it never shifts the layout. Unlike [SyncStatusBar]
 * it adds no height in the common case:
 *
 *  - **FAILED** → the full banner, floated at the top (the one state worth
 *    interrupting for; carries the Retry action).
 *  - **SYNCING / PENDING / OFFLINE** (or an "updated elsewhere" note) → a small,
 *    semi-transparent status icon tucked in the top-right corner.
 *  - **IDLE** → nothing.
 */
@Composable
fun BoxScope.SyncStatusOverlay(
    state: SyncUiState,
    onRetry: (() -> Unit)? = null,
) {
    if (state.kind == SyncIndicatorKind.FAILED) {
        SyncStatusBar(
            state = state,
            onRetry = onRetry,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        return
    }
    val showIcon = state.kind != SyncIndicatorKind.IDLE || state.updatedElsewhere
    AnimatedVisibility(
        visible = showIcon,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopEnd),
    ) {
        val palette = state.kind.palette()
        Icon(
            imageVector = state.kind.icon(),
            contentDescription = state.message(),
            tint = palette.fg.copy(alpha = 0.55f),
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 6.dp, end = 12.dp)
                .background(palette.bg.copy(alpha = 0.6f), CircleShape)
                .padding(5.dp)
                .size(16.dp),
        )
    }
}

/** The four visual states of the global indicator. */
enum class SyncIndicatorKind { IDLE, SYNCING, PENDING, FAILED, OFFLINE }

/** Pure UI model the bar renders; built by [syncUiStateOf]. */
data class SyncUiState(
    val kind: SyncIndicatorKind,
    val pendingCount: Int = 0,
    val updatedElsewhere: Boolean = false,
)

/**
 * Pure state-derivation (the primary unit-test target). Priority order:
 *  1. **OFFLINE** — no network: surface it even if there are pending writes
 *     (they'll drain on reconnect).
 *  2. **FAILED** — at least one write failed and needs a retry.
 *  3. **SYNCING** — a drain is actively in flight.
 *  4. **PENDING** — writes are queued (online, not yet draining).
 *  5. **IDLE** — everything synced.
 */
fun syncUiStateOf(
    online: Boolean,
    pendingCount: Int,
    failedCount: Int,
    syncing: Boolean,
    updatedElsewhere: Boolean = false,
): SyncUiState {
    val kind = when {
        !online -> SyncIndicatorKind.OFFLINE
        failedCount > 0 -> SyncIndicatorKind.FAILED
        syncing -> SyncIndicatorKind.SYNCING
        pendingCount > 0 -> SyncIndicatorKind.PENDING
        else -> SyncIndicatorKind.IDLE
    }
    return SyncUiState(kind = kind, pendingCount = pendingCount, updatedElsewhere = updatedElsewhere)
}

private fun SyncUiState.message(): String = when (kind) {
    SyncIndicatorKind.OFFLINE ->
        if (pendingCount > 0) "Offline — $pendingCount change${plural(pendingCount)} will sync when reconnected"
        else "Offline — showing saved data"
    SyncIndicatorKind.FAILED -> "Some changes didn't sync"
    SyncIndicatorKind.SYNCING -> "Syncing…"
    SyncIndicatorKind.PENDING -> "$pendingCount change${plural(pendingCount)} waiting to sync"
    SyncIndicatorKind.IDLE -> if (updatedElsewhere) "Updated elsewhere" else "All changes synced"
}

private fun plural(n: Int) = if (n == 1) "" else "s"

private data class Palette(val fg: Color, val bg: Color)

@Composable
private fun SyncIndicatorKind.palette(): Palette = when (this) {
    SyncIndicatorKind.OFFLINE -> Palette(Hf.colors.textSecondary, Hf.colors.canvasMuted)
    SyncIndicatorKind.FAILED -> Palette(Hf.colors.alert, Hf.colors.alertBg)
    SyncIndicatorKind.SYNCING -> Palette(Hf.colors.neutral, Hf.colors.accentBg)
    SyncIndicatorKind.PENDING -> Palette(Hf.colors.warn, Hf.colors.warnBg)
    SyncIndicatorKind.IDLE -> Palette(Hf.colors.good, Hf.colors.goodBg)
}

private fun SyncIndicatorKind.icon(): ImageVector = when (this) {
    SyncIndicatorKind.OFFLINE -> Icons.Filled.CloudOff
    SyncIndicatorKind.FAILED -> Icons.Filled.ErrorOutline
    SyncIndicatorKind.SYNCING -> Icons.Filled.Sync
    SyncIndicatorKind.PENDING -> Icons.Filled.CloudQueue
    SyncIndicatorKind.IDLE -> Icons.Filled.CloudDone
}
