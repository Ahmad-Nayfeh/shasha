package com.ahmadnayfeh.shasha.mobile.sponsorblock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

/** Where a video's segments come from ([SponsorBlockClient]; a fake in tests). */
fun interface SegmentFetcher {
    /**
     * Every segment SponsorBlock has for [videoId] in the categories this app knows, sorted by start; empty
     * when there are none.
     * @throws IOException when SponsorBlock cannot be reached or answers something unusable.
     */
    suspend fun fetch(videoId: String): List<SponsorSegment>
}

/**
 * SponsorBlock's API, the privacy-preserving way: the request names only the first 4 hex characters of the video id's
 * SHA-256 (`GET /api/skipSegments/a16c?categories=[…]&actionTypes=["skip"]`), the server answers for every video sharing
 * that prefix, and the video's own entry is picked here.
 *
 * Every category the app knows is asked for, whatever is selected: the cache then serves any selection, and a
 * change in the settings applies without asking again. HTTP 404 means "no segments". Short timeouts: this runs
 * beside the stream extraction and never holds the video back.
 */
class SponsorBlockClient(
    http: OkHttpClient,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    /** The whole request, answer included, or it is given up (the video plays without skipping). */
    callTimeoutMs: Long = CALL_TIMEOUT_MS,
) : SegmentFetcher {
    private val client: OkHttpClient = http.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /** The request for [videoId]'s prefix (the video id itself never leaves the phone). */
    fun requestUrl(videoId: String): HttpUrl = baseUrl.newBuilder()
        .addPathSegments("api/skipSegments")
        .addPathSegment(hashPrefix(videoId))
        .addQueryParameter("categories", jsonArray(SponsorCategory.entries.map { it.apiKey }))
        .addQueryParameter("actionTypes", jsonArray(SegmentAction.entries.map { it.apiKey }))
        .build()

    override suspend fun fetch(videoId: String): List<SponsorSegment> = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(requestUrl(videoId)).header("Accept", "application/json").build())
        // Leaving the player cancels the request instead of waiting for its timeout.
        call.await().use { response ->
            when (response.code) {
                HTTP_NOT_FOUND -> emptyList()
                HTTP_OK -> parseSegments(readBody(response), videoId)
                else -> throw IOException("HTTP ${response.code}")
            }
        }
    }

    /** A prefix is shared by ~150 videos (about 60 KB); an answer far larger than that is not one to use. */
    private fun readBody(response: Response): String {
        val body = response.body
        if (body.contentLength() > MAX_BODY_BYTES) throw IOException("SponsorBlock answer too large")
        val source = body.source()
        if (source.request(MAX_BODY_BYTES + 1)) throw IOException("SponsorBlock answer too large")
        return source.readUtf8()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://sponsor.ajay.app/"
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 5_000L
        const val CALL_TIMEOUT_MS = 10_000L
        private const val MAX_BODY_BYTES = 4L * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        /** The first 4 hex characters of SHA-256(videoId), lower case: all the server learns. */
        fun hashPrefix(videoId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
            return digest.take(2).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        /**
         * [videoId]'s segments in SponsorBlock's answer for its prefix (other videos are dropped), sorted by
         * start. Entries this app cannot use are left out one by one: an unknown category or action, a broken
         * time range. Throws only when [body] is not a JSON array at all.
         */
        fun parseSegments(body: String, videoId: String): List<SponsorSegment> {
            val videos = try {
                json.parseToJsonElement(body) as? JsonArray
            } catch (e: IllegalArgumentException) {
                throw IOException("SponsorBlock answer is not JSON", e)
            } ?: throw IOException("SponsorBlock answer is not a list")
            return videos.asSequence()
                .mapNotNull { it as? JsonObject }
                .filter { it.string("videoID") == videoId }
                .flatMap { (it["segments"] as? JsonArray).orEmpty() }
                .mapNotNull { (it as? JsonObject)?.toSegment() }
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.startMs }, { it.endMs }))
                .toList()
        }

        private fun JsonObject.toSegment(): SponsorSegment? {
            val category = string("category")?.let(SponsorCategory::fromApiKey) ?: return null
            val action = string("actionType")?.let(SegmentAction::fromApiKey) ?: return null
            val range = (this["segment"] as? JsonArray)?.takeIf { it.size == 2 } ?: return null
            val start = range[0].seconds() ?: return null
            val end = range[1].seconds() ?: return null
            if (start < 0 || end <= start) return null
            val startMs = (start * 1000).roundToLong()
            val endMs = (end * 1000).roundToLong()
            if (endMs <= startMs) return null
            val duration = this["videoDuration"]?.seconds()?.takeIf { it > 0 } ?: 0.0
            val id = string("UUID")?.takeIf { it.isNotBlank() } ?: "${category.apiKey}@$startMs-$endMs"
            return SponsorSegment(id, category, action, startMs, endMs, (duration * 1000).roundToLong())
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonElement.seconds(): Double? =
            (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

        private fun jsonArray(values: List<String>): String = values.joinToString(",", "[", "]") { "\"$it\"" }
    }
}

/** Runs the call on OkHttp's threads; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
