package io.github.giansalvo.ggcalendar

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.giansalvo.ggcalendar.core.data.CalendarRepository
import javax.inject.Inject

@HiltAndroidApp
class GGCalendarApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var repository: CalendarRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        repository.schedulePeriodicSync()
    }
}
