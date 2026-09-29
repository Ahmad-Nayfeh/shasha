package com.ahmadnayfeh.shasha.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * DataStores on files of a test's temporary folder. [restart] closes the open instance and opens the
 * same file again, the way a new process would (DataStore allows one active instance per file).
 */
class TestDataStores(private val dir: File) {
    private val open = mutableMapOf<String, CoroutineScope>()

    fun open(name: String = PolicyStore.FILE_NAME): DataStore<Preferences> {
        check(name !in open) { "$name is already open" }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        open[name] = scope
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "$name.preferences_pb") })
    }

    fun restart(name: String = PolicyStore.FILE_NAME): DataStore<Preferences> {
        close(name)
        return open(name)
    }

    fun closeAll() = open.keys.toList().forEach(::close)

    private fun close(name: String) {
        val scope = open.remove(name) ?: return
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
    }
}

/** A scope for the code under test (profile following, state flows), cancelled after each test. */
fun testScope(): CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

fun CoroutineScope.cancelAndWait() {
    val job = coroutineContext.job // this scope's job (inside runBlocking, coroutineContext would be runBlocking's own)
    runBlocking { job.cancelAndJoin() }
}
