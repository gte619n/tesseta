package com.gte619n.healthfitness.feature.medical.reminders

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * IMPL-STAB Workstream F (item 3): a PERSISTENT, non-dismissible warning shown
 * wherever reminders are surfaced (medications list, settings) when a required
 * system permission is missing, so the "reminders won't fire" state is
 * unmistakable. Two distinct gaps, each with a one-tap remedy:
 *
 *  - **Notifications** (Android 13+ runtime grant) — without it a fired alarm
 *    posts nothing. One tap requests the runtime permission (or, if permanently
 *    denied, opens app notification settings).
 *  - **Exact alarms** (Android 12+ special access) — without it reminders
 *    silently degrade to a ~15-minute window. One tap opens the system grant
 *    screen.
 *
 * Re-checks on every resume so the banner clears the moment the user grants in
 * Settings and returns. Renders nothing when everything required is granted.
 */
@Composable
fun ReminderPermissionWarning(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var notificationsGranted by remember { mutableStateOf(notificationsGranted(context)) }
    var exactAlarmsGranted by remember { mutableStateOf(exactAlarmsGranted(context)) }

    // Re-probe on every resume — the user may have just toggled the grant in
    // system Settings and swiped back.
    LifecycleResumeEffect(Unit) {
        notificationsGranted = notificationsGranted(context)
        exactAlarmsGranted = exactAlarmsGranted(context)
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
        if (!granted) openAppNotificationSettings(context)
    }

    if (notificationsGranted && exactAlarmsGranted) return

    Column(modifier = modifier.fillMaxWidth()) {
        if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            WarningRow(
                text = "Notifications are off — dose reminders can't appear. Tap to allow.",
                onClick = {
                    notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            Spacer(Modifier.height(8.dp))
        }
        if (!exactAlarmsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WarningRow(
                text = "Exact alarms are off — reminders may arrive late. Tap to allow.",
                onClick = { openExactAlarmSettings(context) },
            )
        }
    }
}

@Composable
private fun WarningRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.alert, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = Hf.colors.alert,
            modifier = Modifier.height(18.dp),
        )
        Text(
            text,
            style = Hf.type.bodySm,
            color = Hf.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun exactAlarmsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private fun openAppNotificationSettings(context: Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
