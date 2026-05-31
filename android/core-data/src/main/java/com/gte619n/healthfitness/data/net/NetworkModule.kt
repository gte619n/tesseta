package com.gte619n.healthfitness.data.net

import com.gte619n.healthfitness.data.auth.IdTokenCache
import com.gte619n.healthfitness.data.goals.ChatApi
import com.gte619n.healthfitness.data.goals.GoalsApi
import com.gte619n.healthfitness.data.nutrition.FoodApi
import com.gte619n.healthfitness.data.nutrition.NutritionApi
import com.gte619n.healthfitness.data.nutrition.NutritionCaptureApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the backend base URL. The :app module is the only place that
 * knows the concrete URL (it lives in app BuildConfig), so app provides this
 * binding into the graph; core-data consumes it here. See IMPL-12
 * remaining-assumptions item 15.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackendBaseUrl

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi =
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    @Provides
    @Singleton
    fun provideIdTokenCache(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): IdTokenCache = IdTokenCache(context)

    @Provides
    @Singleton
    fun provideAuthInterceptor(cache: IdTokenCache): AuthInterceptor =
        AuthInterceptor(cache)

    @Provides
    @Singleton
    @Named("logging")
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        auth: AuthInterceptor,
        @Named("logging") logging: HttpLoggingInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        moshi: Moshi,
        @BackendBaseUrl baseUrl: String,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl.ensureTrailingSlash())
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideGoalsApi(retrofit: Retrofit): GoalsApi =
        retrofit.create(GoalsApi::class.java)

    @Provides
    @Singleton
    fun provideChatApi(retrofit: Retrofit): ChatApi =
        retrofit.create(ChatApi::class.java)

    @Provides
    @Singleton
    fun provideNutritionApi(retrofit: Retrofit): NutritionApi =
        retrofit.create(NutritionApi::class.java)

    @Provides
    @Singleton
    fun provideFoodApi(retrofit: Retrofit): FoodApi =
        retrofit.create(FoodApi::class.java)

    @Provides
    @Singleton
    fun provideNutritionCaptureApi(retrofit: Retrofit): NutritionCaptureApi =
        retrofit.create(NutritionCaptureApi::class.java)

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}
