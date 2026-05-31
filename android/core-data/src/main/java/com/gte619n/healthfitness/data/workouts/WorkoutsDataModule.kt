package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.data.net.BackendBaseUrl
import com.gte619n.healthfitness.data.net.DayOfWeekMoshiAdapter
import com.gte619n.healthfitness.data.net.InstantAdapter
import com.gte619n.healthfitness.data.net.LocalDateAdapter
import com.gte619n.healthfitness.domain.workouts.EquipmentRepository
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Named
import javax.inject.Singleton

/**
 * Wiring for the workouts (gym + equipment) data layer.
 *
 * EquipmentSpec polymorphism: NetworkModule's base [Moshi] does not consume
 * an @IntoSet adapter set, and moshi-adapters' PolymorphicJsonAdapterFactory
 * is not on the classpath. So we build a dedicated `@Named("workoutsMoshi")`
 * with our hand-rolled [EquipmentSpecJsonAdapter.FACTORY], and a
 * `@Named("workoutsRetrofit")` Retrofit that uses it. Both workouts APIs are
 * created from that Retrofit so the polymorphic `specs` field (de)serializes
 * via Retrofit's converter — no per-mapper Moshi calls needed.
 *
 * Adapter ordering is critical: [EquipmentSpecJsonAdapter.FACTORY] must be
 * registered BEFORE [KotlinJsonAdapterFactory], because the reflective Kotlin
 * factory THROWS ("Cannot serialize abstract class") when asked for the sealed
 * `EquipmentSpec` base type. We rebuild from scratch (rather than
 * `baseMoshi.newBuilder().add(...)`, which appends our factory last) so our
 * factory wins for `EquipmentSpec` while the Kotlin factory still handles the
 * concrete subtypes and DTOs. The java.time + DayOfWeek adapters mirror
 * NetworkModule's base Moshi (lowercase day-of-week wire form).
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkoutsDataModule {

    @Provides
    @Singleton
    @Named("workoutsMoshi")
    fun provideWorkoutsMoshi(): Moshi =
        Moshi.Builder()
            .add(EquipmentSpecJsonAdapter.FACTORY)
            .add(LocalDateAdapter())
            .add(InstantAdapter())
            .add(DayOfWeekMoshiAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()

    @Provides
    @Singleton
    @Named("workoutsRetrofit")
    fun provideWorkoutsRetrofit(
        okHttpClient: OkHttpClient,
        @Named("workoutsMoshi") moshi: Moshi,
        @BackendBaseUrl baseUrl: String,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideLocationApi(@Named("workoutsRetrofit") retrofit: Retrofit): LocationApi =
        retrofit.create(LocationApi::class.java)

    @Provides
    @Singleton
    fun provideEquipmentApi(@Named("workoutsRetrofit") retrofit: Retrofit): EquipmentApi =
        retrofit.create(EquipmentApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkoutsRepositoryModule {
    @Binds
    abstract fun bindLocationRepository(impl: LocationRepositoryImpl): LocationRepository

    @Binds
    abstract fun bindEquipmentRepository(impl: EquipmentRepositoryImpl): EquipmentRepository
}
