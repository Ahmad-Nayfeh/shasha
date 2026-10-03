package com.ahmadnayfeh.shasha.youtube.newpipe

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * - Requests to YouTube ask for its Restricted Mode (the cookie `PREF=f2=8000000`, as NewPipe's own app does for its "restricted
 *   mode" setting): one more layer under the app's own filter ([com.ahmadnayfeh.shasha.youtube.safety.SafeVideoSource]), which
 *   does not catch everything YouTube's does, and the other way round. Its value is merged into a PREF cookie that is
 *   already there, never replacing it ([withRestrictedMode]).
 * - HTTP 429 becomes [ReCaptchaException], as NewPipeExtractor expects.
 * - HTTP 403 is remembered per thread so the caller can report "HTTP 403" instead of the
 *   vague parsing error the extractor usually raises afterwards.
 * - A caller can watch, and reshape, the "browse" answers the extractor receives during one blocking call
 *   ([browseTaps], see [BrowseTaps]): how a channel's section is read in the order the viewer chose.
 */
internal class NewPipeDownloader(
    baseClient: OkHttpClient,
    private val cookies: () -> String? = { null },
    /** Whether a request to this host asks for Restricted Mode: YouTube's own hosts. Tests answer for their local server. */
    private val restrictedModeFor: (host: String) -> Boolean = { host -> isYouTubeHost(host) },
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

    /** What callers watch the "browse" answers with; see [BrowseTaps]. */
    val browseTaps = BrowseTaps()

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
        val merged = mergeCookies(cookies(), extractorCookie)
        val host = request.url().toHttpUrlOrNull()?.host.orEmpty()
        val cookie = if (restrictedModeFor(host)) withRestrictedMode(merged) else merged
        cookie?.let { builder.header("Cookie", it) }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested (HTTP 429)", request.url())
            }
            if (response.code == 403) blockedStatus.set(403)
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                browseTaps.answered(request.url(), response.body.string()),
                response.request.url.toString(),
            )
        }
    }

    internal companion object {
        /** Latest Firefox ESR, same as NewPipe. Extractor requests usually override it. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        private fun String.requiresBody() = this == "POST" || this == "PUT" || this == "PATCH"

        /** The cookie that asks YouTube for Restricted Mode: PREF is its preferences, `f2` their flags (a hexadecimal number). */
        const val RESTRICTED_MODE_COOKIE = "PREF=f2=8000000"

        /** The flag of Restricted Mode in `f2`. */
        private const val RESTRICTED_MODE_FLAG = 0x8000000L

        /** youtube.com and every host under it (www, m, music, the suggestions host…). */
        fun isYouTubeHost(host: String): Boolean = host == "youtube.com" || host.endsWith(".youtube.com")

        /**
         * [cookies] (a Cookie header value, or null) with Restricted Mode switched on. PREF is one cookie holding fields
         * separated by `&` (`PREF=f6=400&hl=en&f2=8000000`): a PREF that is there keeps its other fields, and its `f2` gets
         * the flag added to whatever flags it holds (`f2=400` becomes `f2=8000400`); without a PREF cookie, one is added at the
         * end. The other cookies, and their order, stay as they were.
         */
        fun withRestrictedMode(cookies: String?): String {
            val pairs = cookies?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().toMutableList()
            val at = pairs.indexOfFirst { it.substringBefore('=').trim() == "PREF" }
            if (at < 0) return (pairs + RESTRICTED_MODE_COOKIE).joinToString("; ")
            val fields = pairs[at].substringAfter('=', "").split('&').filter { it.isNotEmpty() }.toMutableList()
            val f2 = fields.indexOfFirst { it.substringBefore('=') == "f2" }
            if (f2 < 0) {
                fields += "f2=" + RESTRICTED_MODE_FLAG.toString(16)
            } else {
                val flags = fields[f2].substringAfter('=', "").toLongOrNull(16) ?: 0L
                fields[f2] = "f2=" + (flags or RESTRICTED_MODE_FLAG).toString(16)
            }
            pairs[at] = "PREF=" + fields.joinToString("&")
            return pairs.joinToString("; ")
        }

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
