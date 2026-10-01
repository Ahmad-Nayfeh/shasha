package com.ahmadnayfeh.shasha.remote.qa

import com.ahmadnayfeh.shasha.remote.RemoteServer
import com.ahmadnayfeh.shasha.remote.RemoteUrls
import java.net.InetAddress

/**
 * Visual QA only (not a test): serves the phone page on 127.0.0.1 with [DemoController] until the
 * process is killed. Every controller call is printed as "CALL …" so a browser script can check
 * which calls the page made.
 *
 * Args: [port] [token]. Run it with the unit-test runtime classpath of :remote.
 */
fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toInt() ?: 8765
    val token = args.getOrNull(1) ?: "qa-Token_2026-shasha"
    val loopback = InetAddress.getByName("127.0.0.1")
    val say: (String) -> Unit = { line -> synchronized(System.out) { println(line); System.out.flush() } }
    lateinit var server: RemoteServer
    lateinit var demo: DemoController
    // A new port from the page: announced in the status first (every open page follows), then the server moves there
    // keeping the parent sessions, as the TV's RemoteRuntime does.
    val move: (Int) -> Unit = { newPort ->
        Thread {
            Thread.sleep(800)
            demo.movingTo = newPort
            Thread.sleep(2_500)
            server.stop(endSessions = false)
            val moved = server.start(loopback, newPort)
            demo.movingTo = null
            say("MOVED $moved")
        }.start()
    }
    val taken: (Int) -> Boolean = { p -> runCatching { java.net.ServerSocket().use { it.reuseAddress = true; it.bind(java.net.InetSocketAddress(loopback, p)) } }.isFailure }
    demo = DemoController(say, move, taken)
    server = RemoteServer(demo) { token }
    server.onError = { it.printStackTrace() }
    val bound = server.start(loopback, port)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    println("READY ${RemoteUrls.pairingUrl(loopback, bound, token)}")
    System.out.flush()
    Thread.sleep(Long.MAX_VALUE)
}
