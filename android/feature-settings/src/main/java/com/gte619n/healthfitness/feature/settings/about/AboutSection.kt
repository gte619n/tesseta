package com.gte619n.healthfitness.feature.settings.about

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.gte619n.healthfitness.ui.components.HfCard

@Composable
fun AboutSection(
    versionName: String,
    versionCode: Int,
) {
    val context = LocalContext.current

    HfCard(transparent = true) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("About")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Version")
                Text("$versionName (#$versionCode)")
            }

            LinkRow(label = "Privacy Policy") {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, PRIVACY_URL.toUri()),
                )
            }
            LinkRow(label = "Terms of Service") {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, TERMS_URL.toUri()),
                )
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text("›")
    }
}

// Placeholder URLs; real URLs land in a follow-up content workstream.
private const val PRIVACY_URL = "https://placeholder.tesseta.app/privacy"
private const val TERMS_URL = "https://placeholder.tesseta.app/terms"
