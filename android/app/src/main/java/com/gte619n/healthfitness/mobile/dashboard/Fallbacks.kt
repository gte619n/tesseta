package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Battery6Bar
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.RamenDining
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SignalCellular4Bar
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

// Icons used across the dashboard. The mockups use Tabler glyphs; on Android
// we mirror them with the closest Material Outlined equivalents. Layout and
// sizing match the spec exactly; the glyph silhouettes are the only place
// the implementations diverge.
object DashboardIcons {
    val Scale: ImageVector = Icons.Outlined.MonitorWeight
    val ActivityHeartbeat: ImageVector = Icons.Outlined.MonitorHeart
    val Heart: ImageVector = Icons.Outlined.FavoriteBorder
    val Flame: ImageVector = Icons.Outlined.LocalFireDepartment
    val Barbell: ImageVector = Icons.Outlined.FitnessCenter
    val Bowl: ImageVector = Icons.Outlined.RamenDining
    val Pill: ImageVector = Icons.Outlined.Medication
    val Moon: ImageVector = Icons.Outlined.DarkMode
    val Calendar: ImageVector = Icons.Outlined.CalendarMonth
    val ChevronDown: ImageVector = Icons.Outlined.KeyboardArrowDown
    val Search: ImageVector = Icons.Outlined.Search
    val Bell: ImageVector = Icons.Outlined.NotificationsNone
    val Sparkles: ImageVector = Icons.Outlined.AutoAwesome
    val BodyScan: ImageVector = Icons.Outlined.AccountTree
    val Droplet: ImageVector = Icons.Outlined.WaterDrop
    val Dashboard: ImageVector = Icons.Outlined.Dashboard
    val Settings: ImageVector = Icons.Outlined.Settings
    val Selector: ImageVector = Icons.Outlined.UnfoldMore
    val Signal4G: ImageVector = Icons.Outlined.SignalCellular4Bar
    val Wifi: ImageVector = Icons.Outlined.Wifi
    val Battery3: ImageVector = Icons.Outlined.Battery6Bar
    val Add: ImageVector = Icons.Outlined.Add
    val ChartLine: ImageVector = Icons.AutoMirrored.Outlined.ShowChart
    val MoreHoriz: ImageVector = Icons.Outlined.MoreHoriz
    val ArrowRight: ImageVector = Icons.AutoMirrored.Outlined.ArrowForward
    val Home: ImageVector = Icons.Outlined.Home
    val Route: ImageVector = Icons.Outlined.Route
}

data class VitalDelta(val direction: ArrowDir, val value: String, val window: String, val tone: Tone)

enum class ArrowDir { Up, Down }

enum class Tone { Good, Warn, Alert, Neutral }

data class Vital(
    val label: String,
    val icon: ImageVector,
    val value: String,
    val unit: String? = null,
    val delta: VitalDelta? = null,
    val pill: Pair<String, Tone>? = null,
    /** Nine y-values 0..20, x evenly spaced. Matches the mockup polyline. */
    val sparkline: List<Float>,
)

// IMPL-AND-01: feature flags gating the dashboard regions still backed by
// fixtures while their live data sources are wired up. Flip a flag off once a
// region is fully live.
object DashboardFlags {
    const val showVitalsFixtures = true       // HRV / RHR / Readiness
    const val showRecentFeedFixtures = true
    const val showTodayCardFixtures = true    // calories / macros / workout line
}

// IMPL-AND-01: remaining static content the dashboard falls back to for the
// regions that are not yet wired to live data. Weight/blood/doses now come
// from DashboardViewModel; everything below is fixture-backed placeholder.
object DashboardFallbacks {
    const val GREETING = "Good morning, Evan"
    const val DATE_WEEKDAY = "TUE"
    const val DATE_MONTH_DAY = "MAY 20"
    const val TIME = "07:42"
    const val TZ = "ATL"
    const val USER_INITIALS = "EG"
    const val USER_NAME = "Evan Glazier"
    const val USER_ROLE = "CEO"

    val vitals = listOf(
        Vital(
            label = "Weight",
            icon = DashboardIcons.Scale,
            value = "189.2",
            unit = "lb",
            delta = VitalDelta(ArrowDir.Down, "0.4", "7d", Tone.Good),
            sparkline = listOf(12f, 10f, 13f, 9f, 11f, 8f, 9f, 6f, 7f),
        ),
        Vital(
            label = "HRV",
            icon = DashboardIcons.ActivityHeartbeat,
            value = "62",
            unit = "ms",
            delta = VitalDelta(ArrowDir.Up, "3", "7d", Tone.Good),
            sparkline = listOf(14f, 11f, 12f, 8f, 10f, 7f, 6f, 5f, 4f),
        ),
        Vital(
            label = "Resting HR",
            icon = DashboardIcons.Heart,
            value = "51",
            unit = "bpm",
            delta = VitalDelta(ArrowDir.Down, "1", "7d", Tone.Good),
            sparkline = listOf(7f, 9f, 8f, 10f, 9f, 11f, 10f, 12f, 13f),
        ),
        Vital(
            label = "Readiness",
            icon = DashboardIcons.Flame,
            value = "84",
            unit = "%",
            pill = "Primed" to Tone.Good,
            sparkline = listOf(9f, 8f, 11f, 7f, 8f, 6f, 5f, 7f, 5f),
        ),
    )

    val vitalsShortLabels = listOf("Weight", "HRV", "RHR", "Ready")

    data class Macro(val label: String, val value: String, val unit: String, val pct: Float)

    val macros = listOf(
        Macro("Protein", "112", "g", 0.56f),
        Macro("Carbs", "98", "g", 0.35f),
        Macro("Fat", "42", "g", 0.48f),
    )

    val caloriesCurrent = "1,247"
    val caloriesTarget = "2,800"
    val caloriesPct = 0.45f

    val workoutTitle = "Pull Day · 06:15"
    val workoutMetaPhone = "52 min · 14,200 lb"
    val workoutMetaDesktop = "52 min · 14,200 lb · 142 HR"

    data class LogEntry(
        val icon: ImageVector,
        val tone: Tone,
        val title: String,
        val meta: String?,
        val time: String,
    )

    val recentPhone = listOf(
        LogEntry(DashboardIcons.Barbell, Tone.Good, "Pull Day completed", "5 exercises · 18 sets", "07:08"),
        LogEntry(DashboardIcons.Scale, Tone.Neutral, "Weighed in · 189.2 lb", "Aria Air", "06:14"),
        LogEntry(DashboardIcons.Pill, Tone.Good, "Rosuvastatin · 10 mg", "94% · 30d", "05:58"),
    )

    val recentFoldable = listOf(
        LogEntry(DashboardIcons.Barbell, Tone.Good, "Pull Day completed · 5 exercises · 18 sets", null, "07:08"),
        LogEntry(DashboardIcons.Scale, Tone.Neutral, "Weighed in · 189.2 lb · Aria Air", null, "06:14"),
        LogEntry(DashboardIcons.Pill, Tone.Good, "Rosuvastatin · 10 mg · 94% adherence 30d", null, "05:58"),
        LogEntry(DashboardIcons.Moon, Tone.Neutral, "Sleep · 7h 42m · Score 87 · REM 1h 38m", null, "05:30"),
    )

    data class NavDest(val label: String, val icon: ImageVector, val active: Boolean = false, val alert: Boolean = false)

    val foldableNav = listOf(
        NavDest("Dashboard", DashboardIcons.Dashboard, active = true),
        NavDest("Body", DashboardIcons.BodyScan),
        NavDest("Blood", DashboardIcons.Droplet, alert = true),
        NavDest("Workouts", DashboardIcons.Barbell),
        NavDest("Nutrition", DashboardIcons.Bowl),
        NavDest("Meds", DashboardIcons.Pill),
        // IMPL-12: Goals is a top-level rail destination on foldable, placed
        // after Meds to mirror the web nav.
        NavDest("Goals", DashboardIcons.Route),
        NavDest("Insights", DashboardIcons.Sparkles),
    )

    data class BottomDest(val label: String, val icon: ImageVector, val active: Boolean = false)

    val phoneBottomNav = listOf(
        BottomDest("Today", DashboardIcons.Home, active = true),
        BottomDest("Log", DashboardIcons.Add),
        BottomDest("Trends", DashboardIcons.ChartLine),
        BottomDest("More", DashboardIcons.MoreHoriz),
    )
}
