package com.ahmadnayfeh.shasha.remote

import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress

/**
 * The phone remote: a Ktor CIO server with the pairing page and its JSON API (see RemoteRoutes.kt).
 *
 * [start] and [stop] block (binding, graceful shutdown); call them off the main thread, e.g. from the
 * foreground service on Dispatchers.IO. [tokenProvider] is read on every request, so a regenerated
 * token takes effect immediately (call [revokeParentSessions] too).
 */
class RemoteServer internal constructor(
    private val controller: RemoteController,
    private val tokenProvider: () -> String,
    nowMillis: () -> Long,
) {
    constructor(controller: RemoteController, tokenProvider: () -> String) :
        this(controller, tokenProvider, { System.nanoTime() / 1_000_000 })

    /**
     * Receives what would otherwise be lost: controller exceptions (the phone only gets a generic
     * HTTP 500) and engine failures after a successful start. Called on server threads. Log it;
     * never show it to the user.
     */
    @Volatile
    var onError: (Throwable) -> Unit = {}

    private val sessions = ParentSessions(nowMillis)
    private val pages by lazy { RemotePages.fromClasspath() }
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** The port actually bound (useful with port 0), or null while stopped. */
    @Volatile
    var boundPort: Int? = null
        private set

    val isRunning: Boolean get() = boundPort != null

    /**
     * Binds to [bindAddress] only (never the wildcard address) and returns the bound port (pass 0 for
     * any free port). Throws (e.g. java.net.BindException) if the port is taken; the server is then
     * left stopped and can be started again.
     */
    @Synchronized
    fun start(bindAddress: InetAddress, port: Int): Int {
        require(!bindAddress.isAnyLocalAddress) { "Refusing to listen on every interface; pass the LAN address." }
        require(port in 0..65535) { "Bad port $port" }
        check(server == null) { "Already started" }
        val pages = pages
        val attempt = Attempt()
        // Without a handler, a failing accept loop (e.g. the port is taken) reaches the thread's
        // uncaught-exception handler, which on Android kills the app. start() reports bind failures by
        // throwing; later engine failures go to onError.
        val engineFailures = CoroutineExceptionHandler { _, e ->
            if (e is CancellationException) return@CoroutineExceptionHandler
            if (attempt.started) runCatching { onError(e) } else attempt.failure = e
        }
        val module: Application.() -> Unit = { remoteModule(controller, tokenProvider, sessions, pages) { onError(it) } }
        val config = serverConfig(applicationEnvironment()) {
            parentCoroutineContext = engineFailures
            module(module)
        }
        val created = embeddedServer(CIO, config) {
            connector {
                host = bindAddress.hostAddress!!
                this.port = port
            }
            reuseAddress = true
            connectionIdleTimeoutSeconds = 20
            shutdownGracePeriod = GRACE_MILLIS
            shutdownTimeout = TIMEOUT_MILLIS
        }
        try {
            created.start(wait = false)
            val actual = runBlocking { created.engine.resolvedConnectors().first().port }
            attempt.started = true
            server = created
            boundPort = actual
            return actual
        } catch (e: Throwable) {
            runCatching { created.stop(0, 0) }
            throw startFailure(e, attempt.failure)
        }
    }

    /**
     * Ktor may report a failed bind as the cancellation it caused. Callers need the real cause, and
     * must never get a CancellationException from a plain function: inside a coroutine it would
     * look like an ordinary cancellation and the failure would pass silently.
     */
    private fun startFailure(e: Throwable, engineFailure: Throwable?): Throwable {
        if (e !is CancellationException) return e
        return generateSequence(e.cause) { it.cause }.firstOrNull { it !is CancellationException }
            ?: engineFailure
            ?: IOException("The remote server did not start", e)
    }

    /** Graceful but short: in-flight requests get [GRACE_MILLIS], everything ends within [TIMEOUT_MILLIS]. Idempotent. */
    @Synchronized
    fun stop() {
        val running = server ?: return
        server = null
        boundPort = null
        sessions.clear()
        running.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
    }

    /** Logs every phone out of the parent section (e.g. after the parent PIN or the token changed). */
    fun revokeParentSessions() = sessions.clear()

    private class Attempt {
        @Volatile
        var started = false

        @Volatile
        var failure: Throwable? = null
    }

    private companion object {
        const val GRACE_MILLIS = 300L
        const val TIMEOUT_MILLIS = 1_500L
    }
}
