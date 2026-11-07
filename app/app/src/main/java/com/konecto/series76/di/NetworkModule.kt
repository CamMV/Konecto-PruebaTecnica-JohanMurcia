package com.konecto.series76.di

import com.konecto.series76.BuildConfig
import com.konecto.series76.data.remote.KonectoApi
import com.konecto.series76.data.remote.interceptor.AuthInterceptor
import com.konecto.series76.data.remote.interceptor.BaseUrlInterceptor
import com.konecto.series76.data.remote.interceptor.UnauthorizedInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides @Singleton
    fun okHttp(
        baseUrl: BaseUrlInterceptor,
        auth: AuthInterceptor,
        unauthorized: UnauthorizedInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(baseUrl)
        .addInterceptor(auth)
        .addInterceptor(unauthorized)
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        // The agent (LLM + tools) can take a while; the outbox retries timeouts idempotently.
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun api(client: OkHttpClient, json: Json): KonectoApi = Retrofit.Builder()
        .baseUrl(BaseUrlInterceptor.PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(KonectoApi::class.java)
}
