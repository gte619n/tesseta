package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.ui.graphics.vector.ImageVector
import com.gte619n.healthfitness.domain.dashboard.RecentActivityEntry
import com.gte619n.healthfitness.domain.dashboard.RecentActivityKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Maps the backend's uniform RecentActivityEntry rows to the dashboard feed's
// LogEntry view model. The cross-source merge/sort/cap lives on the backend; the
// only client-local decisions are icon, tone, the relative-time label, and how
// title/subtitle collapse on the narrow (foldable) layout.

private fun iconFor(kind: RecentActivityKind): ImageVector = when (kind) {
    RecentActivityKind.WORKOUT -> DashboardIcons.Barbell
    RecentActivityKind.WEIGH_IN -> DashboardIcons.Scale
    RecentActivityKind.SLEEP -> DashboardIcons.Moon
    RecentActivityKind.FOOD -> DashboardIcons.Bowl
    RecentActivityKind.MEDICATION -> DashboardIcons.Pill
    RecentActivityKind.UNKNOWN -> DashboardIcons.MoreHoriz
}

// "Activity" rows (things the user did) get the green-accented badge; passive
// readings stay neutral — matching the web feed's tone split.
private fun toneFor(kind: RecentActivityKind): Tone = when (kind) {
    RecentActivityKind.WORKOUT,
    RecentActivityKind.FOOD,
    RecentActivityKind.MEDICATION -> Tone.Good
    RecentActivityKind.WEIGH_IN,
    RecentActivityKind.SLEEP,
    RecentActivityKind.UNKNOWN -> Tone.Neutral
}

private val SHORT_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US)

/**
 * Compact "time ago" label for the right column: now / 12m / 3h / 2d, then a
 * short date once it's older than a week. Mirrors the web feed's relTime.
 */
internal fun relativeTime(ts: Instant, now: Instant = Instant.now()): String {
    val minutes = ChronoUnit.MINUTES.between(ts, now)
    if (minutes < 1) return "now"
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h"
    val days = hours / 24
    if (days < 7) return "${days}d"
    return SHORT_DATE.format(ts.atZone(ZoneId.systemDefault())).uppercase(Locale.US)
}

/**
 * Convert live activity rows into feed entries. On the foldable layout the
 * subtitle is folded into the title (single dense line, no meta) to match the
 * existing foldable feed style; on phone it stays as the secondary line.
 */
fun List<RecentActivityEntry>.toLogEntries(
    foldable: Boolean = false,
    now: Instant = Instant.now(),
): List<DashboardFallbacks.LogEntry> = map { e ->
    val title = if (foldable && !e.subtitle.isNullOrBlank()) "${e.title} · ${e.subtitle}" else e.title
    DashboardFallbacks.LogEntry(
        icon = iconFor(e.kind),
        tone = toneFor(e.kind),
        title = title,
        meta = if (foldable) null else e.subtitle,
        time = relativeTime(e.timestamp, now),
    )
}
