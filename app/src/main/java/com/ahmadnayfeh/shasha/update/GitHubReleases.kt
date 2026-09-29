package com.ahmadnayfeh.shasha.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Why the updater stopped. The screen explains each in Arabic ([com.ahmadnayfeh.shasha.ui.screens.updates]). */
enum class UpdateErrorKind {
    /** No connection, DNS, a timeout: not GitHub's fault. */
    NETWORK,

    /** 401: the read-only token in this build is wrong, expired or revoked. */
    TOKEN_REJECTED,

    /** 403/429: GitHub's rate limit, or a token without read access to the repository. */
    FORBIDDEN,

    /** 404: no release published yet, or the token cannot see the (private) repository. */
    NOT_FOUND,

    /** The latest release has no .apk to install. */
    NO_APK,

    /** The latest release's tag is not `v<versionName>-<versionCode>`. */
    BAD_TAG,

    /** An answer that is not what GitHub sends (5xx, broken JSON, an asset outside the API host). */
    BAD_ANSWER,

    /** The download broke off or came back short. */
    DOWNLOAD_FAILED,

    /** The system cannot read the downloaded file as an app. */
    BAD_APK,

    /** The file is another app. */
    WRONG_PACKAGE,

    /** Its versionCode is not higher than the installed one. */
    NOT_NEWER,

    /** Signed with another key than the installed app: Android would refuse it, and so do we. */
    SIGNATURE_MISMATCH,

    /** The system installer failed (its own message is the detail). */
    INSTALL_FAILED,
}

class UpdateException(val kind: UpdateErrorKind, val detail: String? = null, cause: Throwable? = null) :
    Exception("$kind${detail?.let { ": $it" }.orEmpty()}", cause)

/**
 * SPEC §6: the latest GitHub Release of [repo] and its APK, read with a read-only [token] (the repository is
 * private). Every request to the API carries the token; the APK's download is redirected by the API to GitHub's
 * storage host with a signed address, and that request is made WITHOUT the token: it never leaves [apiBase]'s host.
 *
 * [client] is the app's shared one (its User-Agent: GitHub requires one). Suspends on Dispatchers.IO; cancelling
 * cancels the HTTP call.
 */
class GitHubReleases(
    private val client: OkHttpClient,
    private val repo: String,
    private val token: String,
    private val apiBase: HttpUrl = "https://api.github.com/".toHttpUrl(),
) {
    /** A private repository's releases cannot be read without it: the screen explains how to add one. */
    val hasToken: Boolean get() = token.isNotBlank()

    private val withoutRedirects: OkHttpClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    /**
     * The latest release, or null when it must not be offered (a draft or prerelease).
     * @throws UpdateException
     */
    suspend fun latest(): ReleaseInfo? {
        val url = apiBase.newBuilder().addPathSegments("repos/$repo/releases/latest").build()
        return execute(client.newCall(apiRequest(url, accept = "application/vnd.github+json"))) { response ->
            if (!response.isSuccessful) throw httpError(response.code)
            ReleaseParser.parse(response.body.string())
        }
    }

    /**
     * Saves [asset] as [target] (through a `.part` file: [target] only ever holds a whole download), reporting
     * [onProgress] (bytes so far, total when known) from the IO thread.
     * @throws UpdateException
     */
    suspend fun download(asset: ReleaseAsset, target: File, onProgress: (Long, Long?) -> Unit) {
        val url = asset.apiUrl.toHttpUrlOrNull()
        // The token only ever goes to the API host: an asset address anywhere else is not GitHub's answer.
        if (url == null || url.host != apiBase.host || url.port != apiBase.port || url.scheme != apiBase.scheme) {
            throw UpdateException(UpdateErrorKind.BAD_ANSWER, "asset url ${asset.apiUrl}")
        }
        val storage: HttpUrl? = execute(withoutRedirects.newCall(apiRequest(url, accept = "application/octet-stream"))) { response ->
            when {
                response.isRedirect -> {
                    val location = response.header("Location")?.let { response.request.url.resolve(it) }
                        ?: throw UpdateException(UpdateErrorKind.BAD_ANSWER, "redirect without a location")
                    if (apiBase.isHttps && !location.isHttps) throw UpdateException(UpdateErrorKind.BAD_ANSWER, "insecure redirect")
                    location
                }
                response.isSuccessful -> {
                    save(response, target, asset.size, onProgress)
                    null
                }
                else -> throw httpError(response.code)
            }
        }
        if (storage == null) return
        // A new request, built from nothing: no Authorization header can come along to the storage host.
        val request = Request.Builder().url(storage).header("Accept", "application/octet-stream").build()
        execute(client.newCall(request)) { response ->
            if (!response.isSuccessful) throw UpdateException(UpdateErrorKind.DOWNLOAD_FAILED, "HTTP ${response.code}")
            save(response, target, asset.size, onProgress)
        }
    }

    private fun apiRequest(url: HttpUrl, accept: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", API_VERSION)
        .build()

    private fun httpError(code: Int) = UpdateException(
        when (code) {
            401 -> UpdateErrorKind.TOKEN_REJECTED
            403, 429 -> UpdateErrorKind.FORBIDDEN
            404 -> UpdateErrorKind.NOT_FOUND
            else -> UpdateErrorKind.BAD_ANSWER
        },
        "HTTP $code",
    )

    private fun save(response: Response, target: File, expectedSize: Long, onProgress: (Long, Long?) -> Unit) {
        val total = response.body.contentLength().takeIf { it > 0 } ?: expectedSize.takeIf { it > 0 }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            var done = 0L
            var reported = 0L
            response.body.byteStream().use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    onProgress(0, total)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - reported >= PROGRESS_STEP_BYTES) {
                            reported = done
                            onProgress(done, total)
                        }
                    }
                    output.fd.sync()
                }
            }
            onProgress(done, total)
            if (expectedSize > 0 && done != expectedSize) {
                throw UpdateException(UpdateErrorKind.DOWNLOAD_FAILED, "$done of $expectedSize bytes")
            }
            target.delete()
            if (!part.renameTo(target)) throw UpdateException(UpdateErrorKind.DOWNLOAD_FAILED, "cannot write ${target.name}")
        } catch (e: IOException) {
            throw UpdateException(UpdateErrorKind.DOWNLOAD_FAILED, e.javaClass.simpleName, e)
        } finally {
            part.delete()
        }
    }

    /**
     * Runs [call] on IO and reads its response with [read]; cancelling the caller cancels the call (a download
     * stops at once instead of at its next read timeout). Failures to connect are [UpdateErrorKind.NETWORK].
     */
    private suspend fun <T> execute(call: Call, read: (Response) -> T): T = coroutineScope {
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel() // harmless once the response was read
            }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    call.execute().use(read)
                } catch (e: IOException) {
                    // Connecting or reading the answer (save() reports its own failures as DOWNLOAD_FAILED).
                    throw UpdateException(UpdateErrorKind.NETWORK, e.javaClass.simpleName, e)
                }
            }
        } finally {
            canceller.cancel()
        }
    }

    companion object {
        const val API_VERSION = "2022-11-28"
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024L
    }
}
