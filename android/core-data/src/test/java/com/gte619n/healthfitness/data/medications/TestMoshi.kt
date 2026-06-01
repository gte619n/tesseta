package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.net.DayOfWeekMoshiAdapter
import com.gte619n.healthfitness.data.net.InstantAdapter
import com.gte619n.healthfitness.data.net.LocalDateAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** Shared Moshi for medications data-layer tests, mirroring NetworkModule. */
internal object MedsTestMoshi {
    val instance: Moshi = Moshi.Builder()
        .add(LocalDateAdapter())
        .add(InstantAdapter())
        .add(DayOfWeekMoshiAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()
}
