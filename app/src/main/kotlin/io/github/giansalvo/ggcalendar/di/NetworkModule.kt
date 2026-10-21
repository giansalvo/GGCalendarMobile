package io.github.giansalvo.ggcalendar.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import io.github.giansalvo.ggcalendar.core.network.IcsFetcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun calendarApi(http: OkHttpClient): GoogleCalendarApi = GoogleCalendarApi(http)

    @Provides
    @Singleton
    fun icsFetcher(http: OkHttpClient): IcsFetcher = IcsFetcher(http)
}
