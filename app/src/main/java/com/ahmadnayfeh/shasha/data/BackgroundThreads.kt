package com.ahmadnayfeh.shasha.data

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where background work runs that must never take CPU from the player or the UI (the TV's CPU is
 * weak; SmartTube saw stutter from exactly this). THREAD_PRIORITY_BACKGROUND threads only get the
 * CPU time nothing more important wants.
 */
object BackgroundThreads {
    /** Three threads (the feed sync's parallelism); idle ones exit after 30 s. */
    val dispatcher: CoroutineDispatcher by lazy {
        ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), LowPriorityThreadFactory("shasha-sync"))
            .apply { allowCoreThreadTimeOut(true) }
            .asCoroutineDispatcher()
    }

    /**
     * Runs [block] on a Dispatchers.IO thread lowered to background priority for the duration.
     *
     * The YouTube module does its blocking work (request and parsing) inside
     * `withContext(Dispatchers.IO)`. Started from an IO thread, that switch is a no-op, so the work
     * stays on this lowered thread; from [dispatcher] it would hop to a normal-priority IO thread.
     * The priority is restored by thread id, so a resumption on another thread cannot leak it.
     */
    suspend fun <T> lowPriorityIo(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val tid = Process.myTid()
        val before = Process.getThreadPriority(tid)
        Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
        try {
            block()
        } finally {
            runCatching { Process.setThreadPriority(tid, before) }
        }
    }
}

private class LowPriorityThreadFactory(private val prefix: String) : ThreadFactory {
    private val count = AtomicInteger()

    override fun newThread(task: Runnable): Thread = Thread(
        {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        },
        "$prefix-${count.incrementAndGet()}",
    ).apply { isDaemon = true }
}
