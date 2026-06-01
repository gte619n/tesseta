package com.gte619n.healthfitness.feature.blood.nav

import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.NavGraphBuilder
import androidx.navigation.navArgument
import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.gte619n.healthfitness.feature.blood.AddReadingScreen
import com.gte619n.healthfitness.feature.blood.BloodOverviewScreen
import com.gte619n.healthfitness.feature.blood.MarkerDetailScreen
import com.gte619n.healthfitness.feature.blood.ReportDetailScreen
import com.gte619n.healthfitness.feature.blood.UploadLabReportScreen

object BloodRoutes {
    const val OVERVIEW = "blood"
    const val MARKER_DETAIL = "blood/markers/{markerKey}"
    const val REPORT_DETAIL = "blood/reports/{reportId}"
    const val ADD_READING = "blood/add"
    const val UPLOAD_REPORT = "blood/upload"

    const val ARG_MARKER_KEY = "markerKey"
    const val ARG_REPORT_ID = "reportId"

    fun markerDetail(m: BloodMarker): String = "blood/markers/${m.name}"
    fun reportDetail(id: String): String = "blood/reports/$id"
}

fun NavGraphBuilder.bloodGraph(navController: NavHostController) {
    composable(BloodRoutes.OVERVIEW) {
        BloodOverviewScreen(
            onBack = { navController.popBackStack() },
            onMarkerClick = { navController.navigate(BloodRoutes.markerDetail(it)) },
            onReportClick = { navController.navigate(BloodRoutes.reportDetail(it)) },
            onAddReading = { navController.navigate(BloodRoutes.ADD_READING) },
            onUploadPdf = { navController.navigate(BloodRoutes.UPLOAD_REPORT) },
        )
    }
    composable(
        route = BloodRoutes.MARKER_DETAIL,
        arguments = listOf(navArgument(BloodRoutes.ARG_MARKER_KEY) { type = NavType.StringType }),
    ) {
        MarkerDetailScreen(onBack = { navController.popBackStack() })
    }
    composable(
        route = BloodRoutes.REPORT_DETAIL,
        arguments = listOf(navArgument(BloodRoutes.ARG_REPORT_ID) { type = NavType.StringType }),
    ) {
        ReportDetailScreen(onBack = { navController.popBackStack() })
    }
    dialog(BloodRoutes.ADD_READING) {
        AddReadingScreen(
            onDone = { navController.popBackStack() },
            onBack = { navController.popBackStack() },
        )
    }
    dialog(BloodRoutes.UPLOAD_REPORT) {
        UploadLabReportScreen(
            onComplete = { reportId ->
                navController.popBackStack()
                navController.navigate(BloodRoutes.reportDetail(reportId))
            },
            onDismiss = { navController.popBackStack() },
            onBack = { navController.popBackStack() },
        )
    }
}
