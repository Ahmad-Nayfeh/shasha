package com.ahmadnayfeh.shasha.youtube.newpipe

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor's HTTP layer on top of the app's shared OkHttpClient (same connection pool).
 *
 * - Headers coming from the extractor override ours (it sets client-specific User-Agents).
 * - Cookies are merged, never overwritten: ours + the extractor's (e.g. the consent cookie).
 * - HTTP 429 becomes [ReCaptchaException], as NewPipeExtractor expects.
 * - HTTP 403 is remembered per thread so the caller can report "HTTP 403" instead of the
 *   vague parsing error the extractor usually raises afterwards.
 */
internal class NewPipeDownloader(
    baseClient: OkHttpClient,
    private val cookies: () -> String? = { null },
) : Downloader() {

    // App interceptors (e.g. a global User-Agent) would silently break the extractor's per-client
    // headers, so the extractor gets a copy of the shared client without them.
    private val client: OkHttpClient = baseClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val blockedStatus = ThreadLocal<Int?>()

    /** Starts a new trace on the current thread. Call before a blocking extractor operation. */
    fun beginTrace() = blockedStatus.remove()

    /** 403 if YouTube answered 403 on this thread since [beginTrace], else null. */
    fun blockedStatusSinceTrace(): Int? = blockedStatus.get()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
            ?: if (request.httpMethod().requiresBody()) ByteArray(0).toRequestBody() else null

        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)

        var extractorCookie: String? = null
        request.headers().forEach { (name, values) ->
            if (name.equals("Cookie", ignoreCase = true)) {
                extractorCookie = mergeCookies(extractorCookie, values.joinToString("; "))
            } else {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
        }
        mergeCookies(cookies(), extractorCookie)?.let { builder.header("Cookie", it) }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested (HTTP 429)", request.url())
            }
            if (response.code == 403) blockedStatus.set(403)
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString(),
            )
        }
    }

    internal companion object {
        /** Latest Firefox ESR, same as NewPipe. Extractor requests usually override it. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        private fun String.requiresBody() = this == "POST" || this == "PUT" || this == "PATCH"

        /** Merges two Cookie header values; on a name clash the [override] value wins. */
        fun mergeCookies(base: String?, override: String?): String? {
            val merged = LinkedHashMap<String, String>()
            for (header in listOf(base, override)) {
                header?.split(';')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.forEach { pair -> merged[pair.substringBefore('=').trim()] = pair }
            }
            return merged.values.joinToString("; ").ifEmpty { null }
        }
    }
}
