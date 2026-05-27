package com.gte619n.healthfitness.feature.bodycomposition.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import com.gte619n.healthfitness.feature.bodycomposition.detail.DexaScanDetailScreen
import com.gte619n.healthfitness.feature.bodycomposition.overview.BodyCompositionScreen
import com.gte619n.healthfitness.feature.bodycomposition.upload.UploadDexaScreen

/**
 * NavGraphBuilder extension that registers the body-composition feature's
 * three routes. Wired into the phone NavHost from `AppNavGraph`. The
 * upload destination is a `dialog` so it overlays the overview without
 * losing the back stack.
 */
fun NavGraphBuilder.bodyCompositionGraph(
    onBack: () -> Unit,
    navigateToScan: (scanId: String) -> Unit,
    navigateToUpload: () -> Unit,
) {
    composable<BodyCompositionRoute> {
        BodyCompositionScreen(
            onBack = onBack,
            onScanClick = navigateToScan,
            onUploadClick = navigateToUpload,
        )
    }
    composable<DexaScanDetailRoute> {
        DexaScanDetailScreen(onBack = onBack)
    }
    dialog<UploadDexaRoute> {
        UploadDexaScreen(
            onComplete = { scanId ->
                onBack()
                navigateToScan(scanId)
            },
            onDismiss = onBack,
        )
    }
}
