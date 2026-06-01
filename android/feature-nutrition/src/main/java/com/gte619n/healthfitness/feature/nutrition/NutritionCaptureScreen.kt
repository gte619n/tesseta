package com.gte619n.healthfitness.feature.nutrition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.LabelCaptureFood
import com.gte619n.healthfitness.domain.nutrition.MealCaptureItem
import com.gte619n.healthfitness.domain.nutrition.forPortion
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun NutritionCaptureRoute(
    onBack: () -> Unit,
    viewModel: NutritionCaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

/**
 * Unified live-camera pane. A single feed continuously scans for barcodes and
 * (via OCR) nutrition labels, auto-advancing as soon as either is detected.
 * The user can also tap ANALYZE MEAL to photograph the plate, or use the
 * floating folder button to pick an existing photo to analyze as a meal.
 */
@Composable
private fun ScanningPane(
    controller: CameraCaptureController,
    onBarcodeDetected: (String) -> Unit,
    onAnalyzeMeal: (ByteArray) -> Unit,
    onAnalyzeLabel: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Re-arm barcode/label detection whenever we return to the live feed.
    LaunchedEffect(Unit) { controller.clearMemo() }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            // Decode + downscale off the main thread, then analyze as a meal.
            scope.launch(Dispatchers.IO) {
                runCatching { loadJpegFromUri(context, uri) }.getOrNull()?.let(onAnalyzeMeal)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 18.dp)
                .background(Hf.colors.textPrimary, RoundedCornerShape(12.dp)),
        ) {
            CameraPreview(
                controller = controller,
                onBarcode = onBarcodeDetected,
                onLabelDetected = {
                    controller.takePhoto(
                        context = context,
                        onResult = onAnalyzeLabel,
                        onError = { /* surfaced via state.error on the next attempt */ },
                    )
                },
            )
            Text(
                "Point at a barcode or nutrition label",
                style = Hf.type.capsSm,
                color = Hf.colors.textInverse,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
            FloatingActionButton(
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                containerColor = Hf.colors.surface,
                contentColor = Hf.colors.textSecondary,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(46.dp),
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = "Choose a photo",
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
            PrimaryButton(text = "ANALYZE MEAL", modifier = Modifier.fillMaxWidth()) {
                controller.takePhoto(
                    context = context,
                    onResult = onAnalyzeMeal,
                    onError = { /* surfaced via state.error on the next attempt */ },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Reads a picked image Uri into downscaled JPEG bytes (longest side ≤ [maxDim]).
 * Re-encoding normalizes PNG/HEIC picks to JPEG and keeps the upload small for
 * the meal-analysis call. Run off the main thread.
 */
private fun loadJpegFromUri(context: Context, uri: Uri, maxDim: Int = 1568): ByteArray {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: error("Could not read the selected image")
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        out.toByteArray()
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

/**
 * Shown while a photo is being analyzed (or a meal logged): the spinner plus a
 * rotating set of status messages so the wait reads as progress, not a hang.
 */
@Composable
private fun AnalyzingPane() {
    val messages = listOf(
        "Analyzing your photo…",
        "Identifying ingredients…",
        "Estimating portions & macros…",
        "Plating your meal…",
        "Generating food images…",
        "Almost there…",
    )
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1900)
            index = (index + 1) % messages.size
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Hf.colors.accent)
        Spacer(Modifier.height(18.dp))
        AnimatedContent(targetState = messages[index], label = "analyzing-status") { msg ->
            Text(
                msg,
                style = Hf.type.bodyMd,
                color = Hf.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

