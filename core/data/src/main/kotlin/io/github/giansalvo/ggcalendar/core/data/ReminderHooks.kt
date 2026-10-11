package io.github.giansalvo.ggcalendar.core.data

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Inject
import javax.inject.Singleton

/** Implemented by the notifications module: the events or their instances changed, so the reminders must be scheduled again. */
fun interface ReminderHook {
    fun remindersChanged()
}

/** Lets the data layer tell the notifications module about changes without depending on it. */
@Singleton
class ReminderHooks @Inject constructor(private val hooks: Set<@JvmSuppressWildcards ReminderHook>) {
    fun changed() = hooks.forEach { it.remindersChanged() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderHooksModule {
    @Multibinds
    abstract fun hooks(): Set<ReminderHook>
}
