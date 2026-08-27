package com.rahga.x2rock.di

import com.rahga.x2rock.BuildConfig
import com.rahga.x2rock.network.AuthInterceptor
import com.rahga.x2rock.network.SonosApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Shared connection pool and dispatcher. Both derived clients call newBuilder() on this so a
     * TV box isn't paying for two pools and two dispatcher thread pools.
     */
    @Provides
    @Singleton
    fun provideBaseOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // A player refresh fans out one request per speaker; the default of 5 would queue them.
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
            .build()

    @Provides
    @Singleton
    @TokenClient
    fun provideTokenClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    // BASIC, never BODY: these response bodies are the access and refresh tokens.
                    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                            else HttpLoggingInterceptor.Level.NONE
                }
            )
            .build()

    @Provides
    @Singleton
    fun provideSonosApiService(
        base: OkHttpClient,
        authInterceptor: AuthInterceptor
    ): SonosApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
        }
        val client = base.newBuilder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.ws.sonos.com/control/api/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SonosApiService::class.java)
    }
}
