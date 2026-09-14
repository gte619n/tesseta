package com.gte619n.healthfitness.feature.settings.about

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.gte619n.healthfitness.ui.components.SettingsCard
import com.gte619n.healthfitness.ui.components.SettingsNavRow
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun AboutSection(
    versionName: String,
    versionCode: Int,
) {
    val context = LocalContext.current

    SettingsCard(title = "About") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Version", style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            Text(
                "$versionName (#$versionCode)",
                style = Hf.type.monoMd,
                color = Hf.colors.textSecondary,
            )
        }

        SettingsNavRow(label = "Privacy Policy", onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_URL.toUri()))
        })
        SettingsNavRow(label = "Terms of Service", onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, TERMS_URL.toUri()))
        })
    }
}

// COMP-002: the real published policies served by the marketing site
// (website/public/privacy.html + terms.html, canonical host tesseta.com; the
// site's own nav links to these exact clean paths). Replaces the dead
// placeholder.tesseta.app domain the audit flagged.
private const val PRIVACY_URL = "https://tesseta.com/privacy"
private const val TERMS_URL = "https://tesseta.com/terms"
