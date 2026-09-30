package com.ahmadnayfeh.shasha.remote

import java.security.MessageDigest
import java.util.Base64

/**
 * A static page from the classpath (remote/src/main/resources/web/) with a Content-Security-Policy
 * that allows exactly its own inline scripts (by hash), so injected markup could never run code.
 */
internal class WebPage(val bytes: ByteArray) {
    val contentSecurityPolicy: String = buildCsp(bytes.decodeToString())

    companion object {
        fun resource(path: String): ByteArray? =
            WebPage::class.java.classLoader?.getResourceAsStream(path)?.use { it.readBytes() }

        fun load(path: String): WebPage =
            WebPage(resource(path) ?: error("Missing classpath resource $path"))

        private val inlineScript = Regex("""<script>([\s\S]*?)</script>""")

        internal fun buildCsp(html: String): String {
            val hashes = inlineScript.findAll(html).joinToString(" ") { match ->
                val digest = MessageDigest.getInstance("SHA-256").digest(match.groupValues[1].toByteArray(Charsets.UTF_8))
                "'sha256-${Base64.getEncoder().encodeToString(digest)}'"
            }
            return listOf(
                "default-src 'none'",
                "script-src ${hashes.ifEmpty { "'none'" }}",
                "style-src 'unsafe-inline'",
                "img-src 'self' data: https:",
                "connect-src 'self'",
                "base-uri 'none'",
                "form-action 'none'",
                "frame-ancestors 'none'",
            ).joinToString("; ")
        }
    }
}
