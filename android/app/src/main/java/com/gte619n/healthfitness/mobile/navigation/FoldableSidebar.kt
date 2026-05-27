package com.gte619n.healthfitness.mobile.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.gte619n.healthfitness.ui.TessetaMark
import com.gte619n.healthfitness.ui.TessetaMarkVariant
import com.gte619n.healthfitness.ui.theme.Hf

/**
 * Medium / expanded foldable sidebar — drives the same `navController` as
 * the bottom nav. Visual treatment matches the IMPL-02 dashboard sidebar
 * (icon-only, 38 dp Tesseta mark, active row gets an accent fill + edge
 * indicator).
 */
@Composable
fun FoldableSidebar(navController: NavHostController) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val destination = currentEntry?.destination

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(64.dp)
            .background(Hf.colors.canvasMuted)
            .border(0.5.dp, Hf.colors.borderStrong, shape = RoundedCornerShape(0.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TessetaMark(variant = TessetaMarkVariant.DARK, size = 38.dp)
        Spacer(Modifier.height(9.dp))
        PrimaryDestinations.forEach { dest ->
            val active = destination?.hasRoute(dest.route::class) == true
            FoldableNavIcon(
                icon = dest.icon,
                active = active,
                contentDescription = dest.label,
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

@Composable
private fun FoldableNavIcon(
    icon: ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(38.dp)
            .background(
                if (active) Hf.colors.accentBg else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 9.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(
                        Hf.colors.accent,
                        RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp),
                    ),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Hf.colors.accentDim else Hf.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}
