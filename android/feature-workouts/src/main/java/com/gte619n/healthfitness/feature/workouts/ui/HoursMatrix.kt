package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.HoursSlot
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * 7-row hours editor (or read-only display). Each row has the day
 * label, open + close text fields, and a "Closed" toggle.
 *
 * The fields are plain `TextField`s with `HH:mm` placeholders rather
 * than the full Material3 `TimePickerDialog`. The dialog felt heavy
 * for what is essentially a 5-character entry, and the spec calls out
 * the dialog as the fallback if the simpler form proves clumsy.
 */
@Composable
fun HoursMatrix(
    hours: Map<DayOfWeek, HoursSlot?>,
    onChange: (Map<DayOfWeek, HoursSlot?>) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DayOfWeek.entries.forEachIndexed { index, day ->
            val previousDay = if (index > 0) DayOfWeek.entries[index - 1] else null
            HoursRow(
                day = day,
                slot = hours[day],
                readOnly = readOnly,
                canCopyFromPrevious = previousDay != null && hours[previousDay] != null,
                onCopyFromPrevious = {
                    val prev = previousDay?.let { hours[it] } ?: return@HoursRow
                    onChange(hours.toMutableMap().apply { this[day] = prev })
                },
                onChange = { newSlot ->
                    val next = hours.toMutableMap().apply { this[day] = newSlot }
                    onChange(next)
                },
            )
            if (day != DayOfWeek.SUN) {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HoursRow(
    day: DayOfWeek,
    slot: HoursSlot?,
    readOnly: Boolean,
    canCopyFromPrevious: Boolean = false,
    onCopyFromPrevious: () -> Unit = {},
    onChange: (HoursSlot?) -> Unit,
) {
    var openText by remember(slot?.open) { mutableStateOf(slot?.open.orEmpty()) }
    var closeText by remember(slot?.close) { mutableStateOf(slot?.close.orEmpty()) }
    val closed = slot == null

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = day.label(),
            style = Hf.type.bodyMd,
            color = Hf.colors.textSecondary,
            modifier = Modifier.width(40.dp),
        )
        if (readOnly) {
            if (closed) {
                Text(
                    "Closed",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
            } else {
                Text(
                    "${slot?.open} - ${slot?.close}",
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textPrimary,
                )
            }
        } else {
            if (canCopyFromPrevious) {
                TextButton(
                    onClick = onCopyFromPrevious,
                    modifier = Modifier.width(36.dp),
                ) {
                    Text("↑", style = Hf.type.bodyMd, color = Hf.colors.accent)
                }
            } else {
                Spacer(Modifier.width(36.dp))
            }
            Spacer(Modifier.width(4.dp))
            CompactTimeField(
                value = if (closed) "" else openText,
                enabled = !closed,
                placeholder = "HH:mm",
                onChange = {
                    openText = it
                    syncSlot(it, closeText, onChange)
                },
                modifier = Modifier.width(80.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("-", color = Hf.colors.textTertiary, style = Hf.type.bodyMd)
            Spacer(Modifier.width(6.dp))
            CompactTimeField(
                value = if (closed) "" else closeText,
                enabled = !closed,
                placeholder = "HH:mm",
                onChange = {
                    closeText = it
                    syncSlot(openText, it, onChange)
                },
                modifier = Modifier.width(80.dp),
            )
            Spacer(Modifier.width(10.dp))
            ClosedToggle(
                closed = closed,
                onToggle = {
                    if (closed) {
                        openText = ""
                        closeText = ""
                        onChange(null)
                    } else {
                        onChange(null) // Becomes "Closed"
                        openText = ""
                        closeText = ""
                    }
                },
            )
        }
    }
}

private fun syncSlot(
    open: String,
    close: String,
    onChange: (HoursSlot?) -> Unit,
) {
    val o = open.trim()
    val c = close.trim()
    // Best-effort live-sync: emit only when both look like HH:mm; the
    // form validation on submit catches anything weird.
    if (o.length >= 4 && c.length >= 4) {
        onChange(HoursSlot(o, c))
    }
}

@Composable
private fun CompactTimeField(
    value: String,
    enabled: Boolean,
    placeholder: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        placeholder = { Text(placeholder, style = Hf.type.bodySm, color = Hf.colors.textQuaternary) },
        textStyle = Hf.type.bodyMd,
        singleLine = true,
        modifier = modifier.height(42.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Hf.colors.surface,
            unfocusedContainerColor = Hf.colors.surface,
            disabledContainerColor = Hf.colors.canvasMuted,
            focusedIndicatorColor = Hf.colors.accent,
            unfocusedIndicatorColor = Hf.colors.borderDefault,
            focusedTextColor = Hf.colors.textPrimary,
            unfocusedTextColor = Hf.colors.textPrimary,
            disabledTextColor = Hf.colors.textTertiary,
        ),
    )
}

@Composable
private fun ClosedToggle(closed: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (closed) Hf.colors.canvasMuted else Hf.colors.accentBg,
                RoundedCornerShape(12.dp),
            )
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = if (closed) Hf.colors.textTertiary else Hf.colors.accentDim,
                modifier = Modifier.height(12.dp).width(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (closed) "Closed" else "Open",
                style = Hf.type.bodySm,
                color = if (closed) Hf.colors.textTertiary else Hf.colors.accentDim,
            )
        }
    }
}

@Suppress("DEPRECATION_ERROR")
private fun DayOfWeek.label(): String = when (this) {
    DayOfWeek.MON -> "Mon"
    DayOfWeek.TUE -> "Tue"
    DayOfWeek.WED -> "Wed"
    DayOfWeek.THU -> "Thu"
    DayOfWeek.FRI -> "Fri"
    DayOfWeek.SAT -> "Sat"
    DayOfWeek.SUN -> "Sun"
}

// Hack to keep Color imported (used by some IDEs as marker); harmless.
@Suppress("unused")
private val keep: Color = Color.Transparent
