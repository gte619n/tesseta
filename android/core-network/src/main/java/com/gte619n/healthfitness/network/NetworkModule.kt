package com.gte619n.healthfitness.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Wires the shared OkHttp + Retrofit + Moshi stack.
 *
 * Two things to call out:
 *  1. The bearer-token interceptor is added *before* the logging
 *     interceptor, so the `Authorization` header shows up in the log.
 *  2. The same `OkHttpClient` instance backs both Retrofit and the SSE
 *     consumer — they share the connection pool, the authenticator, and
 *     the auth interceptor.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun moshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

    @Provides
    @Singleton
    fun authInterceptor(tokenProvider: AuthTokenProvider): AuthInterceptor =
        AuthInterceptor(tokenProvider)

    @Provides
    @Singleton
    fun tokenAuthenticator(tokenProvider: AuthTokenProvider): TokenAuthenticator =
        TokenAuthenticator(tokenProvider)

    @Provides
    @Singleton
    fun okHttpClient(
        auth: AuthInterceptor,
        logging: HttpLoggingInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(auth)
        .addInterceptor(logging)
        .authenticator(authenticator)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun retrofit(
        client: OkHttpClient,
        moshi: Moshi,
        base: BackendBaseUrlProvider,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(base.baseUrl)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun sseFactory(client: OkHttpClient): EventSource.Factory =
        EventSources.createFactory(client)
}
