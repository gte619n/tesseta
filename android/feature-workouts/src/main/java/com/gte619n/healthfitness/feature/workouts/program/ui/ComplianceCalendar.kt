package com.gte619n.healthfitness.feature.workouts.program.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.feature.workouts.program.ComplianceCellKind
import com.gte619n.healthfitness.feature.workouts.program.cellKind
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Month grid of the active program's schedule compliance. Each scheduled
 * training day is marked by outcome: completed (filled), missed (past & not
 * done), or upcoming (today/future). Non-scheduled days render plainly. The
 * classification is the pure [cellKind]; this composable is display-only.
 *
 * Rendered as a fixed [Column] of week [Row]s (never a nested lazy grid, which
 * would fight the enclosing scroll). [onPrevMonth]/[onNextMonth] shift the
 * visible month (the caller widens its calendar read to match).
 */
@Composable
fun ComplianceCalendar(
    month: YearMonth,
    scheduledByDate: Map<LocalDate, ScheduledStatus>,
    today: LocalDate,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(12.dp))
            .background(Hf.colors.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        // Month header with prev/next navigation.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ChevronLeft,
                contentDescription = "Previous month",
                tint = Hf.colors.textSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onPrevMonth() }
                    .padding(4.dp),
            )
            Text(
                "${month.month.getDisplayName(TextStyle.FULL, Locale.US)} ${month.year}",
                style = Hf.type.headingMd.copy(fontSize = 14.sp),
                color = Hf.colors.textPrimary,
            )
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = "Next month",
                tint = Hf.colors.textSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onNextMonth() }
                    .padding(4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        // Weekday header (Sunday-start).
        Row(modifier = Modifier.fillMaxWidth()) {
            WEEK_DAYS.forEach { dow ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CapsLabel(
                        dow.getDisplayName(TextStyle.NARROW, Locale.US),
                        color = Hf.colors.textTertiary,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        // Build the padded cell list: leading blanks + each day + trailing blanks.
        val firstOfMonth = month.atDay(1)
        val lead = firstOfMonth.dayOfWeek.value % 7
        val cells = buildList<LocalDate?> {
            repeat(lead) { add(null) }
            for (d in 1..month.lengthOfMonth()) add(month.atDay(d))
        }
        val trailing = (7 - cells.size % 7) % 7
        val padded = cells + List(trailing) { null }

        padded.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    DayCell(
                        date = date,
                        kind = date?.let { cellKind(it, scheduledByDate[it], today) },
                        isToday = date == today,
                    )
                }
            }
        }
    }
}

private val WEEK_DAYS = listOf(
    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
)

@Composable
private fun RowScope.DayCell(
    date: LocalDate?,
    kind: ComplianceCellKind?,
    isToday: Boolean,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (date == null || kind == null) return@Box
        val fill: Color = when (kind) {
            ComplianceCellKind.COMPLETED -> Hf.colors.accent
            ComplianceCellKind.MISSED -> Hf.colors.alert.copy(alpha = 0.15f)
            ComplianceCellKind.UPCOMING, ComplianceCellKind.REST -> Color.Transparent
        }
        val ring: Color? = when {
            isToday -> Hf.colors.borderStrong
            kind == ComplianceCellKind.UPCOMING -> Hf.colors.accent.copy(alpha = 0.4f)
            else -> null
        }
        val textColor: Color = when (kind) {
            ComplianceCellKind.COMPLETED -> Hf.colors.textInverse
            ComplianceCellKind.MISSED -> Hf.colors.alert
            ComplianceCellKind.UPCOMING -> Hf.colors.textSecondary
            ComplianceCellKind.REST -> Hf.colors.textTertiary
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(fill, CircleShape)
                .then(if (ring != null) Modifier.border(1.dp, ring, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = Hf.type.bodySm.copy(fontSize = 11.sp),
                color = textColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}
