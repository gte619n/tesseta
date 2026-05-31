package com.gte619n.healthfitness.feature.nutrition

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.LabelCaptureFood
import com.gte619n.healthfitness.domain.nutrition.MealCaptureItem
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.forPortion
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun NutritionCaptureRoute(
    onBack: () -> Unit,
    viewModel: NutritionCaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NutritionCaptureScreen(
        state = state,
        onBack = onBack,
        onSetMode = viewModel::setMode,
        onSetPhotoKind = viewModel::setPhotoKind,
        onSetMeal = viewModel::setMeal,
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
    onSetMode: (CaptureMode) -> Unit,
    onSetPhotoKind: (PhotoKind) -> Unit,
    onSetMeal: (Meal) -> Unit,
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
            .background(Hf.colors.canvas),
    ) {
        CaptureTopBar(onBack = onBack)
        ModeToggle(mode = state.mode, onSetMode = onSetMode)
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.padding(horizontal = 18.dp)) {
            MealPicker(selected = state.meal, onSelect = onSetMeal)
        }
        Spacer(Modifier.height(10.dp))

        if (!hasPermission) {
            CameraDenied(onRequest = { launcher.launch(Manifest.permission.CAMERA) })
            return@Column
        }

        when (val stage = state.stage) {
            CaptureStage.Working -> Centered { CircularProgressIndicator(color = Hf.colors.accent) }
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
                state = state,
                controller = controller,
                onSetPhotoKind = onSetPhotoKind,
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

@Composable
private fun CaptureTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Outlined.ArrowBack,
            contentDescription = "Back",
            tint = Hf.colors.textSecondary,
            modifier = Modifier.size(22.dp).clickable(onClick = onBack),
        )
        Text("Capture", style = Hf.type.headingLg.copy(fontSize = 20.sp), color = Hf.colors.textPrimary)
    }
}

@Composable
private fun ModeToggle(mode: CaptureMode, onSetMode: (CaptureMode) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        SegItem("Barcode", mode == CaptureMode.BARCODE, Modifier.weight(1f)) { onSetMode(CaptureMode.BARCODE) }
        SegItem("Photo", mode == CaptureMode.PHOTO, Modifier.weight(1f)) { onSetMode(CaptureMode.PHOTO) }
    }
}

@Composable
private fun SegItem(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(if (active) Hf.colors.accentBg else Hf.colors.surface)
            .border(0.5.dp, if (active) Hf.colors.accent else Hf.colors.borderDefault)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Hf.type.bodyMd,
            color = if (active) Hf.colors.accentDim else Hf.colors.textSecondary,
        )
    }
}

@Composable
private fun ScanningPane(
    state: NutritionCaptureUiState,
    controller: CameraCaptureController,
    onSetPhotoKind: (PhotoKind) -> Unit,
    onBarcodeDetected: (String) -> Unit,
    onAnalyzeMeal: (ByteArray) -> Unit,
    onAnalyzeLabel: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(state.mode, state.stage) { controller.clearBarcodeMemo() }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.mode == CaptureMode.PHOTO) {
            Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                ChipRow(
                    options = PhotoKind.entries,
                    selected = state.photoKind,
                    label = { if (it == PhotoKind.MEAL) "Meal" else "Label" },
                    onSelect = onSetPhotoKind,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 18.dp)
                .background(Hf.colors.textPrimary, RoundedCornerShape(12.dp)),
        ) {
            CameraPreview(
                controller = controller,
                onBarcode = if (state.mode == CaptureMode.BARCODE) onBarcodeDetected else null,
            )
            if (state.mode == CaptureMode.BARCODE) {
                Text(
                    "Point at a barcode",
                    style = Hf.type.capsSm,
                    color = Hf.colors.textInverse,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }

        if (state.mode == CaptureMode.PHOTO) {
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
                PrimaryButton(
                    text = if (state.photoKind == PhotoKind.MEAL) "ANALYZE MEAL" else "READ LABEL",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    controller.takePhoto(
                        context = context,
                        onResult = { jpeg ->
                            if (state.photoKind == PhotoKind.MEAL) onAnalyzeMeal(jpeg) else onAnalyzeLabel(jpeg)
                        },
                        onError = { /* surfaced via state.error on the next analyze attempt */ },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BarcodeFoodPane(food: Food, onConfirm: (Int, Double) -> Unit, onCancel: () -> Unit) {
    var servingIndex by remember(food.foodId) {
        mutableStateOf(food.defaultServingIndex.coerceIn(0, (food.servingSizes.size - 1).coerceAtLeast(0)))
    }
    var quantity by remember(food.foodId) { mutableStateOf(1.0) }
    Column(modifier = Modifier.fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState())) {
        Text(food.name, style = Hf.type.headingMd, color = Hf.colors.textPrimary)
        if (food.brand != null) {
            Text(food.brand!!, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
        }
        Spacer(Modifier.height(14.dp))
        ServingAndQuantity(
            servingSizes = food.servingSizes,
            macrosPer100g = food.macrosPer100g,
            servingIndex = servingIndex,
            quantity = quantity,
            onServingChange = { servingIndex = it },
            onQuantityChange = { quantity = it },
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Rescan", Modifier.weight(1f), onCancel)
            PrimaryButton("Log it", Modifier.weight(1f)) { onConfirm(servingIndex, quantity) }
        }
    }
}

@Composable
private fun BarcodeMissPane(code: String, onFallback: () -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
        Text("No match for $code", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "This product isn't in the catalog yet. Photograph its nutrition label to add it.",
            style = Hf.type.bodyMd,
            color = Hf.colors.textTertiary,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Rescan", Modifier.weight(1f), onCancel)
            PrimaryButton("Photograph label", Modifier.weight(1f), onFallback)
        }
    }
}

@Composable
private fun MealItemsPane(items: List<MealCaptureItem>, onConfirm: (List<MealCaptureItem>) -> Unit, onCancel: () -> Unit) {
    // Editable portion per item; remove items by dropping them.
    val edited = remember(items) {
        androidx.compose.runtime.mutableStateListOf(*items.toTypedArray())
    }
    Column(modifier = Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
        Text("Detected items", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text("Adjust portions or remove items before logging.", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
        Spacer(Modifier.height(12.dp))
        if (edited.isEmpty()) {
            Text("No items left.", style = Hf.type.bodyMd, color = Hf.colors.textTertiary)
        }
        edited.forEachIndexed { index, item ->
            MealItemCard(
                item = item,
                onChangePortion = { grams ->
                    edited[index] = item.copy(
                        estimatedPortionGrams = grams,
                        macrosForPortion = item.macrosPer100g.forPortion(grams, 1.0),
                    )
                },
                onRemove = { edited.removeAt(index) },
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Cancel", Modifier.weight(1f), onCancel)
            PrimaryButton("Log ${edited.size} item(s)", Modifier.weight(1f)) {
                if (edited.isNotEmpty()) onConfirm(edited.toList())
            }
        }
    }
}

@Composable
private fun MealItemCard(item: MealCaptureItem, onChangePortion: (Double) -> Unit, onRemove: () -> Unit) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.name, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                Text("Remove", style = Hf.type.capsSm, color = Hf.colors.alert, modifier = Modifier.clickable { onRemove() })
            }
            Spacer(Modifier.height(8.dp))
            MacroPreview(macros = item.macrosForPortion)
            Spacer(Modifier.height(10.dp))
            Text("Portion: ${formatGrams(item.estimatedPortionGrams)}", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
            Spacer(Modifier.height(4.dp))
            // Coarse portion stepper: half / current / 1.5x / 2x of the estimate.
            ChipRow(
                options = listOf(0.5, 1.0, 1.5, 2.0),
                selected = 1.0,
                label = { "${it}×" },
                onSelect = { mult -> onChangePortion(item.estimatedPortionGrams * mult) },
            )
        }
    }
}

@Composable
private fun LabelDraftPane(food: LabelCaptureFood, onConfirm: (Int, Double) -> Unit, onCancel: () -> Unit) {
    var servingIndex by remember(food.name) {
        mutableStateOf(food.defaultServingIndex.coerceIn(0, (food.servingSizes.size - 1).coerceAtLeast(0)))
    }
    var quantity by remember(food.name) { mutableStateOf(1.0) }
    Column(modifier = Modifier.fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState())) {
        Text(food.name, style = Hf.type.headingMd, color = Hf.colors.textPrimary)
        if (food.brand != null) {
            Text(food.brand!!, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
        }
        Spacer(Modifier.height(14.dp))
        ServingAndQuantity(
            servingSizes = food.servingSizes,
            macrosPer100g = food.macrosPer100g,
            servingIndex = servingIndex,
            quantity = quantity,
            onServingChange = { servingIndex = it },
            onQuantityChange = { quantity = it },
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Discard", Modifier.weight(1f), onCancel)
            PrimaryButton("Add to catalog & log", Modifier.weight(1f)) { onConfirm(servingIndex, quantity) }
        }
    }
}

@Composable
private fun DonePane(message: String, onAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.CameraAlt, contentDescription = null, tint = Hf.colors.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, style = Hf.type.headingMd, color = Hf.colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Capture another", Modifier.fillMaxWidth(), onAgain)
    }
}

@Composable
private fun CameraDenied(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Camera access is needed to scan barcodes and photograph food.",
            style = Hf.type.bodyMd,
            color = Hf.colors.textTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Grant camera access", Modifier.fillMaxWidth(), onRequest)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
