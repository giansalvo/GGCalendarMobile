package io.github.giansalvo.ggcalendar.core.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * What became of the events of an import into a Google calendar. The import queues its events ([begin] names their outbox entries);
 * the pusher says for each one whether Google took it ([sent]) or it will never go ([rejected]); when the last one is resolved the
 * result is returned once, to be told to the user. It lives in memory: if the app is closed before the end, no report is made.
 */
@Singleton
class ImportReports @Inject constructor() {
    class Finished(val calendarName: String, val sent: Int, val rejected: Int)

    private class Batch(val name: String, val pending: MutableSet<Long>, var sent: Int = 0, var rejected: Int = 0)

    private val batches = ArrayList<Batch>()

    @Synchronized
    fun begin(calendarName: String, seqs: Collection<Long>) {
        if (seqs.isNotEmpty()) batches += Batch(calendarName, seqs.toMutableSet())
    }

    @Synchronized
    fun sent(seq: Long): Finished? = resolve(seq) { it.sent++ }

    @Synchronized
    fun rejected(seq: Long): Finished? = resolve(seq) { it.rejected++ }

    private fun resolve(seq: Long, count: (Batch) -> Unit): Finished? {
        val batch = batches.firstOrNull { seq in it.pending } ?: return null
        batch.pending.remove(seq)
        count(batch)
        if (batch.pending.isNotEmpty()) return null
        batches.remove(batch)
        return Finished(batch.name, batch.sent, batch.rejected)
    }
}
