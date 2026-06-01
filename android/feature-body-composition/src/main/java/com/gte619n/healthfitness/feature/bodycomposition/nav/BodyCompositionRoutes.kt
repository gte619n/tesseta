package com.gte619n.healthfitness.feature.bodycomposition.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.gte619n.healthfitness.feature.bodycomposition.detail.DexaScanDetailScreen
import com.gte619n.healthfitness.feature.bodycomposition.overview.BodyCompositionScreen
import com.gte619n.healthfitness.feature.bodycomposition.upload.UploadDexaScreen

/** String-based Navigation-Compose routes for the body composition feature. */
object BodyCompositionRoutes {
    const val BODY = "body"
    const val UPLOAD = "body/dexa/upload"

    /** Route arg name for the DEXA scan id. */
    const val ARG_SCAN_ID = "scanId"

    /** Detail route pattern (with placeholder). */
    const val DETAIL = "body/dexa/{scanId}"

    /** Concrete detail route for navigation. */
    fun scanDetail(scanId: String): String = "body/dexa/$scanId"
}

fun NavGraphBuilder.bodyCompositionGraph(navController: NavHostController) {
    composable(BodyCompositionRoutes.BODY) {
        BodyCompositionScreen(navController)
    }
    composable(
        route = BodyCompositionRoutes.DETAIL,
        arguments = listOf(
            navArgument(BodyCompositionRoutes.ARG_SCAN_ID) { type = NavType.StringType },
        ),
    ) {
        DexaScanDetailScreen(navController)
    }
    composable(BodyCompositionRoutes.UPLOAD) {
        UploadDexaScreen(navController)
    }
}
