package com.gte619n.healthfitness.mobile.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavDestination.Companion.hasRoute
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Compact-width bottom navigation. The active item is derived from the
 * `NavController`'s current back-stack entry rather than being passed in,
 * so the bar stays in sync if navigation happens from anywhere — taps,
 * deep links, or programmatic `navigate()` calls.
 */
@Composable
fun BottomNavBar(navController: NavHostController) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val destination = currentEntry?.destination

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface),
    ) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Hf.colors.borderDefault),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BottomNavDestinations.forEach { dest ->
                    val active = destination?.hasRoute(dest.route::class) == true
                    BottomNavItem(
                        icon = dest.icon,
                        label = dest.label,
                        active = active,
                        onClick = {
                            if (!active) {
                                navController.navigate(dest.route) {
                                    popUpTo(Route.Today) {
                                        saveState = true
                                        inclusive = false
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .width(24.dp)
                    .background(Hf.colors.accent, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)),
            )
            Spacer(Modifier.height(10.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) Hf.colors.accent else Hf.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label.uppercase(),
            style = Hf.type.capsSm.copy(fontSize = 10.sp),
            color = if (active) Hf.colors.accent else Hf.colors.textTertiary,
        )
    }
}
