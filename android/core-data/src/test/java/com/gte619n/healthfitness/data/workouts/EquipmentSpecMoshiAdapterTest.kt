package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.domain.workouts.EquipmentSpec
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EquipmentSpecMoshiAdapterTest {

    private val moshi: Moshi = Moshi.Builder()
        // FACTORY must precede the reflective Kotlin factory (which throws for
        // the sealed EquipmentSpec base type), matching WorkoutsDataModule.
        .add(EquipmentSpecJsonAdapter.FACTORY)
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(EquipmentSpec::class.java)

    private fun roundTrip(spec: EquipmentSpec): EquipmentSpec {
        val json = adapter.toJson(spec)
        return adapter.fromJson(json)!!
    }

    @Test
    fun `selectorized round-trips`() {
        val spec = EquipmentSpec.Selectorized(10.0, 200.0, 5.0)
        val json = adapter.toJson(spec)
        assertTrue(json.contains("\"specSchema\":\"SELECTORIZED\""))
        assertEquals(spec, roundTrip(spec))
    }

    @Test
    fun `plate loaded round-trips`() {
        val spec = EquipmentSpec.PlateLoaded(45.0, listOf(2.5, 5.0, 10.0, 25.0, 35.0, 45.0))
        assertEquals(spec, roundTrip(spec))
    }

    @Test
    fun `bodyweight round-trips`() {
        val json = adapter.toJson(EquipmentSpec.Bodyweight)
        assertTrue(json.contains("\"specSchema\":\"BODYWEIGHT\""))
        assertEquals(EquipmentSpec.Bodyweight, adapter.fromJson(json))
    }

    @Test
    fun `cable round-trips`() {
        val spec = EquipmentSpec.Cable(weightStack = 90.0, numStations = 2)
        assertEquals(spec, roundTrip(spec))
    }

    @Test
    fun `cardio round-trips`() {
        val spec = EquipmentSpec.Cardio(resistanceLevels = 10, hasIncline = true)
        assertEquals(spec, roundTrip(spec))
    }

    @Test
    fun `weight set round-trips`() {
        val spec = EquipmentSpec.WeightSet(
            minWeight = 5.0, maxWeight = 50.0, increment = 5.0, weights = null,
        )
        assertEquals(spec, roundTrip(spec))
    }

    @Test
    fun `unknown discriminator decodes to bodyweight`() {
        val json = """{"specSchema":"FUTURE_V2","foo":1}"""
        assertEquals(EquipmentSpec.Bodyweight, adapter.fromJson(json))
    }

    @Test
    fun `missing discriminator decodes to bodyweight`() {
        val json = """{"foo":1}"""
        assertEquals(EquipmentSpec.Bodyweight, adapter.fromJson(json))
    }
}
