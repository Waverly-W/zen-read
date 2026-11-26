package com.zenread.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Shared HTTP client and JSON settings. Feature code derives per-use clients with
 * `client.newBuilder()` (e.g. longer read timeouts for LLM streaming) so connection
 * pools and dispatchers stay shared. Never log request bodies or headers: they carry API keys.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(
                if (request.header("User-Agent") == null) {
                    request.newBuilder().header("User-Agent", USER_AGENT).build()
                } else {
                    request
                },
            )
        }
        .build()

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private const val USER_AGENT = "ZenRead/0.1 (Android)"
}
