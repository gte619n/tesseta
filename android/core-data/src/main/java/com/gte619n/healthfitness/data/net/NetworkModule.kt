package com.gte619n.healthfitness.data.net

import com.gte619n.healthfitness.data.auth.AuthApi
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
import com.gte619n.healthfitness.data.goals.ChatApi
import com.gte619n.healthfitness.data.goals.GoalsApi
import com.gte619n.healthfitness.data.nutrition.FoodApi
import com.gte619n.healthfitness.data.nutrition.NutritionApi
import com.gte619n.healthfitness.data.nutrition.NutritionCaptureApi
import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramApi
import com.gte619n.healthfitness.data.workouts.program.chat.WorkoutProgramChatApi
import com.gte619n.healthfitness.data.workouts.trt.TrtContextApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
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
            // java.time adapters must precede the reflective Kotlin factory.
            .add(LocalDateAdapter())
            .add(InstantAdapter())
            .add(DayOfWeekMoshiAdapter())
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
    fun provideTokenAuthenticator(
        repo: GoogleAuthRepository,
    ): TokenAuthenticator = TokenAuthenticator(repo)

    @Provides
    @Singleton
    @Named("logging")
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

    // On-disk HTTP response cache so repeat dashboard fetches can be served from
    // (or revalidated against) cache instead of a full round-trip. Sized at 20 MB
    // in the app cache dir; OkHttp evicts least-recently-used entries past that.
    @Provides
    @Singleton
    fun provideHttpCache(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): Cache = Cache(java.io.File(context.cacheDir, "http_cache"), 20L * 1024 * 1024)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        auth: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        @Named("logging") logging: HttpLoggingInterceptor,
        cache: Cache,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(auth)
            .addInterceptor(logging)
            .authenticator(tokenAuthenticator)
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

    // ADR-0010: a SEPARATE client for the /api/auth/** endpoints. It deliberately
    // omits both the AuthInterceptor (exchange supplies the Google token itself;
    // refresh/logout carry an opaque token in the body) and the
    // TokenAuthenticator (a refresh call must never recurse into 401-refresh).
    // This is also what keeps the Hilt graph acyclic: the main client's
    // authenticator depends on GoogleAuthRepository → AuthApi → this client,
    // which has no authenticator.
    @Provides
    @Singleton
    @Named("auth")
    fun provideAuthOkHttpClient(
        @Named("logging") logging: HttpLoggingInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @Named("auth")
    fun provideAuthRetrofit(
        @Named("auth") client: OkHttpClient,
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
    fun provideAuthApi(@Named("auth") retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

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

    @Provides
    @Singleton
    fun provideWorkoutProgramApi(retrofit: Retrofit): WorkoutProgramApi =
        retrofit.create(WorkoutProgramApi::class.java)

    @Provides
    @Singleton
    fun provideWorkoutProgramChatApi(retrofit: Retrofit): WorkoutProgramChatApi =
        retrofit.create(WorkoutProgramChatApi::class.java)

    @Provides
    @Singleton
    fun provideTrtContextApi(retrofit: Retrofit): TrtContextApi =
        retrofit.create(TrtContextApi::class.java)

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}
