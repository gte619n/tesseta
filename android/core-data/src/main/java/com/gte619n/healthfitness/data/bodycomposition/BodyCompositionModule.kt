package com.gte619n.healthfitness.data.bodycomposition

import com.gte619n.healthfitness.data.bodycomposition.api.BodyCompositionApi
import com.gte619n.healthfitness.data.bodycomposition.api.DexaScanApi
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionRepository
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BodyCompositionModule {

    @Binds
    @Singleton
    abstract fun bindBodyComp(impl: BodyCompositionRepositoryImpl): BodyCompositionRepository

    @Binds
    @Singleton
    abstract fun bindDexa(impl: DexaScanRepositoryImpl): DexaScanRepository

    companion object {
        @Provides
        @Singleton
        fun bodyApi(retrofit: Retrofit): BodyCompositionApi =
            retrofit.create(BodyCompositionApi::class.java)

        @Provides
        @Singleton
        fun dexaApi(retrofit: Retrofit): DexaScanApi =
            retrofit.create(DexaScanApi::class.java)
    }
}
