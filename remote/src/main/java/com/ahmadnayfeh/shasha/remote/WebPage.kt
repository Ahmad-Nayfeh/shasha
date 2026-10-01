package com.ahmadnayfeh.shasha.remote

import java.security.MessageDigest
import java.util.Base64

/**
 * A static page from the classpath (remote/src/main/resources/web/) with a Content-Security-Policy
 * that allows exactly its own inline scripts (by hash), so injected markup could never run code.
 */
internal class WebPage(val bytes: ByteArray) {
    private val html = bytes.decodeToString()
    val contentSecurityPolicy: String = buildCsp(html)

    /**
     * The policy for a page served to [host] (the address the phone used): the page may also reach the same TV on
     * another port, which is how it follows the server to a new port (it loads the icon from there, which only this
     * TV with this pairing token serves, before it moves). Anything that is not a plain host name or IPv4 address gets
     * the strict policy.
     */
    fun contentSecurityPolicy(host: String?): String =
        if (host != null && HOST.matches(host)) buildCsp(html, "http://$host:*") else contentSecurityPolicy

    companion object {
        fun resource(path: String): ByteArray? =
            WebPage::class.java.classLoader?.getResourceAsStream(path)?.use { it.readBytes() }

        fun load(path: String): WebPage =
            WebPage(resource(path) ?: error("Missing classpath resource $path"))

        private val inlineScript = Regex("""<script>([\s\S]*?)</script>""")

        private val HOST = Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")

        internal fun buildCsp(html: String, otherPorts: String? = null): String {
            val hashes = inlineScript.findAll(html).joinToString(" ") { match ->
                val digest = MessageDigest.getInstance("SHA-256").digest(match.groupValues[1].toByteArray(Charsets.UTF_8))
                "'sha256-${Base64.getEncoder().encodeToString(digest)}'"
            }
            return listOf(
                "default-src 'none'",
                "script-src ${hashes.ifEmpty { "'none'" }}",
                "style-src 'unsafe-inline'",
                "img-src 'self' data: https:" + (otherPorts?.let { " $it" } ?: ""),
                "connect-src 'self'" + (otherPorts?.let { " $it" } ?: ""),
                "base-uri 'none'",
                "form-action 'none'",
                "frame-ancestors 'none'",
            ).joinToString("; ")
        }
    }
}
