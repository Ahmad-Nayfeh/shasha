package com.ahmadnayfeh.shasha.remote

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone page's "something changed" mark ([RemoteStatus.rev]): it moves whenever anything the parent section shows
 * changes on the TV (a setting, a profile's rules or lists, the subscriptions), whoever changed it: this phone, another
 * phone or the TV's own Settings. Pages poll the status every 2 s and read again what they show when it moves, so two
 * phones and the TV never keep showing each other stale values.
 *
 * It starts at [start] (the time it was made: a restarted app never repeats a value a page may still hold) and counts
 * up by one per change of any of [sources] (compared with equals: a flow that repeats the same value counts nothing).
 */
class RemoteRevision(scope: CoroutineScope, sources: List<Flow<*>>, start: Long = System.currentTimeMillis()) {
    private val counter = AtomicLong(start)

    /** The current mark. */
    val value: Long get() = counter.get()

    init {
        sources.forEach { source ->
            scope.launch {
                source.distinctUntilChanged()
                    .drop(1) // what it held when this started is not a change
                    .catch { e -> if (e !is CancellationException) Log.w(TAG, "A change source failed", e) }
                    .collect { counter.incrementAndGet() }
            }
        }
    }

    private companion object {
        const val TAG = "RemoteRevision"
    }
}
