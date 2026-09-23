package com.gte619n.healthfitness.shared.presentation.nutrition

import com.gte619n.healthfitness.shared.data.EntryPatchRequest
import com.gte619n.healthfitness.shared.data.EntryRequest
import com.gte619n.healthfitness.shared.data.Food
import com.gte619n.healthfitness.shared.data.FoodRepository
import com.gte619n.healthfitness.shared.data.MealSearchResult
import com.gte619n.healthfitness.shared.data.NutritionDayRepository
import com.gte619n.healthfitness.shared.data.NutritionOp
import com.gte619n.healthfitness.shared.data.NutritionOpQueue
import com.gte619n.healthfitness.shared.data.ServingSize
import com.gte619n.healthfitness.shared.domain.nutrition.Entry
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.nutrition.MealGroup
import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * IMPL-IOS-01 Phase 3 Wave C — commonTest fixtures for the nutrition VMs. Plain
 * KMP fakes (no MockK), mirroring the Android feature-nutrition test fakes.
 */

fun sampleEntry(
    entryId: String = "e-1",
    meal: String = "BREAKFAST",
    foodName: String = "Oatmeal",
    kcal: Double = 200.0,
    protein: Double = 10.0,
): Entry = Entry(
    entryId = entryId,
    meal = meal,
    foodId = "food-1",
    foodName = foodName,
    servingLabel = "1 bowl",
    servingGrams = 240.0,
    quantity = 1.0,
    macros = Macros(caloriesKcal = kcal, proteinGrams = protein),
    source = "CATALOG",
)

fun sampleDay(
    date: String = "2026-09-23",
    entries: List<Entry> = listOf(sampleEntry()),
): NutritionDay {
    val groups = entries.groupBy { it.meal }.map { (meal, es) ->
        MealGroup(meal = meal, subtotal = es.sumMacros(), entries = es)
    }
    return NutritionDay(date = date, totals = entries.sumMacros(), meals = groups)
}

fun sampleFood(
    foodId: String = "food-99",
    name: String = "Protein Bar",
    barcode: String = "012345678905",
): Food = Food(
    foodId = foodId,
    name = name,
    barcode = barcode,
    macrosPer100g = Macros(caloriesKcal = 400.0, proteinGrams = 30.0),
    servingSizes = listOf(ServingSize("1 bar (60 g)", 60.0), ServingSize("100 g", 100.0)),
    defaultServingIndex = 0,
    source = "OPEN_FOOD_FACTS",
    status = "CONFIRMED",
    imageStatus = "NONE",
)

/** Fake day repository whose observed day is a hot flow the test mutates. Records
 *  mutation calls so tests can assert the intent fired. */
class FakeNutritionDayRepository(
    private val days: MutableStateFlow<NutritionDay> = MutableStateFlow(sampleDay()),
    private val cached: NutritionDay? = sampleDay(),
    private val dayThrows: Boolean = false,
    var targetValue: Macros? = null,
) : NutritionDayRepository {

    val deleted = mutableListOf<String>()
    val patched = mutableListOf<Pair<String, EntryPatchRequest>>()
    val added = mutableListOf<EntryRequest>()
    val adjustSubmits = mutableListOf<Triple<String, String, Boolean>>()
    val adjustCommits = mutableListOf<Pair<String, Boolean?>>()
    val adjustDiscards = mutableListOf<String>()
    val leftoverApplies = mutableListOf<String>()
    val leftoverDiscards = mutableListOf<String>()
    val leftoverRestores = mutableListOf<String>()
    val leftoverAnalyzes = mutableListOf<String>()

    override fun observeDay(date: String): Flow<NutritionDay> = days
    override suspend fun day(date: String): NutritionDay {
        if (dayThrows) throw RuntimeException("offline")
        return days.value
    }
    override suspend fun cachedDay(date: String): NutritionDay? = cached
    override suspend fun refreshDay(date: String) {}

    override suspend fun target(): Macros? = targetValue
    override suspend fun setTarget(target: Macros): Macros { targetValue = target; return target }

    override suspend fun addEntry(date: String, body: EntryRequest): Entry {
        added += body
        return sampleEntry(entryId = "new", meal = body.meal, foodName = body.foodName)
    }
    override suspend fun patchEntry(date: String, entryId: String, body: EntryPatchRequest): Entry {
        patched += entryId to body
        return sampleEntry(entryId = entryId)
    }
    override suspend fun deleteEntry(date: String, entryId: String) { deleted += entryId }

    override suspend fun updateComposite(
        date: String,
        entryId: String,
        title: String,
        portion: Double,
        quantities: List<Double>,
    ): Entry = sampleEntry(entryId = entryId, foodName = title)

    override suspend fun removeIngredient(date: String, entryId: String, index: Int): NutritionDayRepository.RemovedIngredient =
        throw NotImplementedError()
    override suspend fun restoreIngredient(
        date: String,
        entryId: String,
        index: Int,
        ingredient: com.gte619n.healthfitness.shared.domain.nutrition.EntryIngredient,
    ): Entry = sampleEntry(entryId = entryId)

    override suspend fun submitAdjust(date: String, entryId: String, instruction: String, saveAsMeal: Boolean) {
        adjustSubmits += Triple(entryId, instruction, saveAsMeal)
    }
    override suspend fun commitAdjust(date: String, entryId: String, saveAsMeal: Boolean?): Entry {
        adjustCommits += entryId to saveAsMeal
        return sampleEntry(entryId = entryId)
    }
    override suspend fun discardAdjust(date: String, entryId: String): Entry {
        adjustDiscards += entryId
        return sampleEntry(entryId = entryId)
    }

    override suspend fun analyzeLeftovers(date: String, entryId: String, jpeg: ByteArray) {
        leftoverAnalyzes += entryId
    }
    override suspend fun applyLeftovers(date: String, entryId: String): Entry {
        leftoverApplies += entryId
        return sampleEntry(entryId = entryId)
    }
    override suspend fun discardLeftovers(date: String, entryId: String): Entry {
        leftoverDiscards += entryId
        return sampleEntry(entryId = entryId)
    }
    override suspend fun restoreLeftovers(date: String, entryId: String): Entry {
        leftoverRestores += entryId
        return sampleEntry(entryId = entryId)
    }

    override suspend fun regenerateEntryImage(date: String, entryId: String): Entry = sampleEntry(entryId = entryId)
    override suspend fun reanalyzeEntry(date: String, entryId: String): Entry = sampleEntry(entryId = entryId)
    override suspend fun servingHint(date: String, entryId: String): String? = "Typical serving: 240 g"

    override suspend fun describeMealAsync(date: String, description: String, meal: String) {}
    override suspend fun relog(date: String, source: Entry, meal: String): Entry = source.copy(meal = meal)
    override suspend fun logDescribedMeal(
        date: String,
        mealId: String,
        meal: String,
        label: String,
        knownImageUrl: String?,
        knownImageStatus: String,
    ) {}

    override suspend fun recentMeals(meal: String?): List<Entry> = listOf(sampleEntry())
    override suspend fun cachedRecentMeals(meal: String?): List<Entry> = listOf(sampleEntry())
    override suspend fun searchMeals(query: String): List<MealSearchResult> = emptyList()
    override suspend fun cachedSearchMeals(query: String): List<MealSearchResult> = emptyList()
    override suspend fun archiveMeal(mealId: String) {}

    /** Test helper: push a new day into the reactive stream. */
    fun emit(day: NutritionDay) { days.value = day }
}

/** Fake food repo: barcode lookup returns a preset hit (or null for a miss). */
class FakeFoodRepository(
    private val barcodeHit: Food? = sampleFood(),
    private val searchResults: List<Food> = listOf(sampleFood()),
) : FoodRepository {
    val deleted = mutableListOf<String>()
    override suspend fun search(query: String): List<Food> = searchResults
    override suspend fun localSearch(query: String): List<Food> = searchResults
    override suspend fun barcodeLookup(code: String): Food? = barcodeHit
    override suspend fun warmFromIds(foodIds: Collection<String>) {}
    override suspend fun deleteFood(foodId: String) { deleted += foodId }
}

/** Fake op queue whose in-flight ops are a hot flow the test mutates. */
class FakeNutritionOpQueue(
    val ops: MutableStateFlow<List<NutritionOp>> = MutableStateFlow(emptyList()),
) : NutritionOpQueue {
    val captured = mutableListOf<Triple<String, String, Int>>() // date, meal, jpeg size
    override fun observeAll(): Flow<List<NutritionOp>> = ops
    override suspend fun enqueueCapturePhoto(date: String, mealWire: String, jpeg: ByteArray): String {
        captured += Triple(date, mealWire, jpeg.size)
        return "op-cap-1"
    }
    override suspend fun enqueueConfirmMealItems(
        date: String,
        mealWire: String,
        items: List<com.gte619n.healthfitness.shared.data.MealCaptureItem>,
    ): String = "op-items-1"
    override suspend fun enqueueConfirmLabel(
        date: String,
        mealWire: String,
        draft: com.gte619n.healthfitness.shared.data.LabelCaptureFood,
        servingIndex: Int,
        quantity: Double,
    ): String = "op-label-1"
}
