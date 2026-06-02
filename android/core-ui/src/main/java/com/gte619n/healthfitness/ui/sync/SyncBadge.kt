package com.gte619n.healthfitness.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * IMPL-AND-20 (Phase 6) — the per-row PENDING / FAILED badge (D11).
 *
 * A small reusable chip rendered next to a list/detail row whose underlying
 * mirror row is not yet SYNCED. It reads the row's `syncState`
 * (`SYNCED | PENDING | FAILED`, the value carried on every mirror row) and shows
 * nothing for SYNCED — so the steady state has no chrome.
 *
 * Wire it by passing the row's `syncState` from the repository's exposed mirror
 * row. Reference integrations: blood readings, medications, nutrition entries.
 * Other in-scope list rows should add the same `SyncBadge(row.syncState)` next to
 * their primary label.
 */
@Composable
fun SyncBadge(
    syncState: String?,
    modifier: Modifier = Modifier,
) {
    val spec = badgeSpecOf(syncState) ?: return
    Text(
        text = spec.label,
        style = Hf.type.capsSm,
        color = spec.fg(),
        modifier = modifier
            .background(spec.bg(), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Pure mapping (unit-testable): null ⇒ render nothing. */
fun badgeSpecOf(syncState: String?): BadgeSpec? = when (syncState?.uppercase()) {
    "PENDING" -> BadgeSpec.PENDING
    "FAILED" -> BadgeSpec.FAILED
    else -> null // SYNCED, null, or unknown → no badge
}

enum class BadgeSpec(val label: String) {
    PENDING("Pending"),
    FAILED("Failed"),
    ;

    @Composable
    fun fg(): Color = when (this) {
        PENDING -> Hf.colors.warn
        FAILED -> Hf.colors.alert
    }

    @Composable
    fun bg(): Color = when (this) {
        PENDING -> Hf.colors.warnBg
        FAILED -> Hf.colors.alertBg
    }
}
