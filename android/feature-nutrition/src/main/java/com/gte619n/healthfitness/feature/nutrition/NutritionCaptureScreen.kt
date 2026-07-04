package com.gte619n.healthfitness.feature.nutrition

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.LabelCaptureFood
import com.gte619n.healthfitness.domain.nutrition.MealCaptureItem
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun NutritionCaptureRoute(
    onBack: () -> Unit,
    viewModel: NutritionCaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.isOnline.collectAsStateWithLifecycle()
    // A barcode hit logs immediately and emits NavigateBack — pop to the nutrition
    // page (which refreshes on resume to show the new entry).
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CaptureEvent.NavigateBack -> onBack()
            }
        }
    }
    NutritionCaptureScreen(
        state = state,
        online = online,
        onBack = onBack,
        onBarcodeDetected = viewModel::onBarcodeDetected,
        onAnalyzeMeal = viewModel::analyzeMeal,
        onAnalyzeLabel = { jpeg -> viewModel.analyzeLabel(jpeg) },
        onConfirmBarcodeFood = viewModel::confirmBarcodeFood,
        onConfirmMealItems = viewModel::confirmMealItems,
        onConfirmLabelDraft = viewModel::confirmLabelDraft,
        onFallbackToLabel = viewModel::fallbackToLabel,
        onReset = viewModel::reset,
    )
}

@Composable
fun NutritionCaptureScreen(
    state: NutritionCaptureUiState,
    onBack: () -> Unit,
    online: Boolean = true,
    onBarcodeDetected: (String) -> Unit,
    onAnalyzeMeal: (ByteArray) -> Unit,
    onAnalyzeLabel: (ByteArray) -> Unit,
    onConfirmBarcodeFood: (Food, Int, Double) -> Unit,
    onConfirmMealItems: (List<MealCaptureItem>) -> Unit,
    onConfirmLabelDraft: (LabelCaptureFood, Int, Double) -> Unit,
    onFallbackToLabel: () -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    val controller = remember { CameraCaptureController() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(
            title = "Capture",
            subtitle = "Point at a barcode or label, or photograph your meal",
            onBack = onBack,
        )
        Spacer(Modifier.height(10.dp))

        if (!online) {
            // D17: barcode lookup + meal/label analysis are online-only AI/network
            // flows. Offline we disable capture entirely and queue nothing.
            com.gte619n.healthfitness.ui.sync.OfflineNotice(
                message = "Scanning food and analyzing meals needs an internet connection.",
            )
            return@Column
        }

        if (!hasPermission) {
            CameraDenied(onRequest = { launcher.launch(Manifest.permission.CAMERA) })
            return@Column
        }

        when (val stage = state.stage) {
            CaptureStage.Working -> AnalyzingPane()
            is CaptureStage.Done -> DonePane(message = stage.message, onAgain = onReset)
            is CaptureStage.BarcodeFood -> BarcodeFoodPane(
                food = stage.food,
                onConfirm = { idx, qty -> onConfirmBarcodeFood(stage.food, idx, qty) },
                onCancel = onReset,
            )
            is CaptureStage.BarcodeMiss -> BarcodeMissPane(code = stage.code, onFallback = onFallbackToLabel, onCancel = onReset)
            is CaptureStage.MealItems -> MealItemsPane(items = stage.items, onConfirm = onConfirmMealItems, onCancel = onReset)
            is CaptureStage.LabelDraft -> LabelDraftPane(
                food = stage.food,
                onConfirm = { idx, qty -> onConfirmLabelDraft(stage.food, idx, qty) },
                onCancel = onReset,
            )
            CaptureStage.Scanning -> ScanningPane(
                controller = controller,
                onBarcodeDetected = onBarcodeDetected,
                onAnalyzeMeal = onAnalyzeMeal,
                onAnalyzeLabel = onAnalyzeLabel,
            )
        }

        if (state.error != null) {
            Text(
                state.error,
                style = Hf.type.bodyMd,
                color = Hf.colors.alert,
                modifier = Modifier.padding(18.dp),
            )
        }
    }
}
