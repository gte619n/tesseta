package com.gte619n.healthfitness.data.bodycomposition

import com.gte619n.healthfitness.data.bodycomposition.api.BodyCompositionApi
import com.gte619n.healthfitness.data.bodycomposition.api.DexaScanApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

// BodyCompositionRepository / DexaScanRepository are concrete @Inject classes —
// no @Binds needed; this module just provides the Retrofit APIs.
@Module
@InstallIn(SingletonComponent::class)
object BodyCompositionModule {

    @Provides
    @Singleton
    fun bodyApi(retrofit: Retrofit): BodyCompositionApi =
        retrofit.create(BodyCompositionApi::class.java)

    @Provides
    @Singleton
    fun dexaApi(retrofit: Retrofit): DexaScanApi =
        retrofit.create(DexaScanApi::class.java)
}
