package com.gte619n.healthfitness.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gte619n.healthfitness.feature.settings.about.AboutSection
import com.gte619n.healthfitness.feature.settings.coach.CoachAudioSection
import com.gte619n.healthfitness.feature.settings.googlehealth.GoogleHealthSection
import com.gte619n.healthfitness.feature.settings.biometrics.BiometricsSection
import com.gte619n.healthfitness.feature.settings.units.UnitsSection
import com.gte619n.healthfitness.feature.settings.withings.WithingsSection
import com.gte619n.healthfitness.feature.settings.workout.WorkoutPreferencesSection
import com.gte619n.healthfitness.feature.settings.workout.WorkoutStreakSection
import com.gte619n.healthfitness.ui.components.ConfirmDialog
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.components.SettingsContentMaxWidth
import com.gte619n.healthfitness.ui.components.SettingsNavRow
import com.gte619n.healthfitness.ui.theme.Hf

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToDrinks: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(title = "Settings", subtitle = "App preferences and account", onBack = onNavigateBack)

        // Header stays pinned; content scrolls beneath it. The column is capped
        // at SettingsContentMaxWidth and centered so tablet / unfolded widths
        // don't stretch cards edge-to-edge.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsGroup("Account") {
                    NavCard {
                        SettingsNavRow(
                            label = "Profile",
                            subtitle = "Height, biological sex, date of birth",
                            onClick = onNavigateToProfile,
                        )
                    }
                }

                SettingsGroup("Preferences") {
                    UnitsSection()
                    BiometricsSection()
                    CoachAudioSection()
                    WorkoutStreakSection()
                    WorkoutPreferencesSection()
                    NavCard {
                        SettingsNavRow(
                            label = "Drinks",
                            subtitle = "Manage your drink catalog",
                            onClick = onNavigateToDrinks,
                        )
                    }
                }

                SettingsGroup("Connections") {
                    GoogleHealthSection()
                    WithingsSection()
                }

                SettingsGroup("About") {
                    AboutSection(
                        versionName = viewModel.versionName,
                        versionCode = viewModel.versionCode,
                    )
                    OutlinedButton(
                        onClick = { confirmSignOut = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Hf.colors.alert),
                        border = BorderStroke(1.dp, Hf.colors.alert),
                    ) {
                        Text("Sign out")
                    }
                }
            }
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            message = "You can sign back in with your Google account at any time.",
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                confirmSignOut = false
                viewModel.signOut(onSignedOut)
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/** A section-titled group of settings cards. */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title)
        content()
    }
}

/** A card holding only navigation rows (tighter padding than [com.gte619n.healthfitness.ui.components.SettingsCard]). */
@Composable
private fun NavCard(content: @Composable ColumnScope.() -> Unit) {
    HfCard(transparent = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            content()
        }
    }
}
