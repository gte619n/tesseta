package com.gte619n.healthfitness.feature.settings.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Phone-only overflow menu rendered behind the bottom-nav "More" tab
 * (the foldable sidebar exposes every primary destination directly,
 * so this screen is unreachable on medium / expanded width classes).
 *
 * The screen is a vertical list rather than a bottom sheet — Material
 * 3's `ModalBottomSheet` partially occludes the bar that opened it
 * which feels off for a permanent destination, and a full-height
 * column keeps every row inside thumb reach on tall phones.
 *
 * Layout:
 *
 *  - Header bar matches the other top-level screens ("More" title).
 *  - Identity card surfaces the signed-in account (loaded from
 *    [ProfileRepository] via [MoreViewModel]); skipped when the fetch
 *    is still in flight or fails so the menu rows always render.
 *  - Single rounded card holds the navigation rows. Sign out lives in
 *    its own card below a spacer, styled with the alert text colour
 *    and a leading logout icon to make the destructive action visually
 *    distinct from the navigation rows above.
 *
 * The screen is stateless w.r.t. navigation — all four destinations
 * are passed in as lambdas from [com.gte619n.healthfitness.mobile.navigation.AppNavHost].
 */
@Composable
fun MoreScreen(
    onNavigateToBlood: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: MoreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MoreScreenContent(
        state = state,
        onNavigateToBlood = onNavigateToBlood,
        onNavigateToWorkouts = onNavigateToWorkouts,
        onNavigateToSettings = onNavigateToSettings,
        onSignOut = { viewModel.signOut(onSignedOut) },
    )
}

/**
 * Stateless body — split out so Paparazzi can drive it with a fixed
 * [MoreViewModel.UiState] without needing a real Hilt graph.
 */
@Composable
internal fun MoreScreenContent(
    state: MoreViewModel.UiState,
    onNavigateToBlood: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Header()
        Spacer(Modifier.height(14.dp))

        if (state is MoreViewModel.UiState.Loaded) {
            IdentityHeader(
                displayName = state.profile.displayName,
                email = state.profile.email,
            )
            Spacer(Modifier.height(12.dp))
        }

        NavigationCard(
            onNavigateToBlood = onNavigateToBlood,
            onNavigateToWorkouts = onNavigateToWorkouts,
            onNavigateToSettings = onNavigateToSettings,
        )
        Spacer(Modifier.height(12.dp))

        SignOutCard(onClick = onSignOut)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Header() {
    Text(
        text = "More",
        style = Hf.type.headingLg,
        color = Hf.colors.textPrimary,
    )
}

@Composable
private fun IdentityHeader(displayName: String?, email: String?) {
    MoreCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Hf.colors.accentBg, RoundedCornerShape(50))
                    .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = Hf.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val primary = displayName?.takeIf { it.isNotBlank() }
                    ?: email
                    ?: "Signed in"
                Text(
                    text = primary,
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textPrimary,
                )
                if (!email.isNullOrBlank() && primary != email) {
                    Text(
                        text = email,
                        style = Hf.type.bodySm,
                        color = Hf.colors.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationCard(
    onNavigateToBlood: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    MoreCard {
        MoreRow(
            icon = Icons.Outlined.WaterDrop,
            label = "Blood",
            onClick = onNavigateToBlood,
        )
        RowDivider()
        MoreRow(
            icon = Icons.Outlined.FitnessCenter,
            label = "Workouts",
            onClick = onNavigateToWorkouts,
        )
        RowDivider()
        MoreRow(
            icon = Icons.Outlined.Settings,
            label = "Settings",
            onClick = onNavigateToSettings,
        )
    }
}

@Composable
private fun SignOutCard(onClick: () -> Unit) {
    MoreCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                contentDescription = null,
                tint = Hf.colors.alert,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = "Sign out",
                style = Hf.type.bodyMd,
                color = Hf.colors.alert,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MoreRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Hf.colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = label,
                style = Hf.type.bodyMd,
                color = Hf.colors.textPrimary,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = Hf.colors.textTertiary,
        )
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp)
            .height(0.5.dp)
            .background(Hf.colors.borderDefault),
    )
}

/**
 * Card surface for the More screen — visually matches the
 * `SettingsCard` (0.5 dp border, 10 dp radius, surface fill) but lives
 * here so rows can opt into their own padding (each row owns its
 * clickable-padding boundary, so the surrounding card carries no
 * vertical padding of its own).
 */
@Composable
private fun MoreCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(Hf.colors.surface, RoundedCornerShape(10.dp)),
    ) {
        Column { content() }
    }
}
