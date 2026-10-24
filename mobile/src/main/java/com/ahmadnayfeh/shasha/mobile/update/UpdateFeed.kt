package com.ahmadnayfeh.shasha.mobile.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Why the updater stopped. The update screen explains each. */
enum class UpdateErrorKind {
    /** No connection, DNS, a timeout. */
    NETWORK,

    /** 403/429: the download site refuses or limits us for now. */
    FORBIDDEN,

    /** 404: no manifest published (yet). */
    NOT_FOUND,

    /** The manifest has no entry for this app. */
    NO_APK,

    /** An answer that is not a usable manifest (5xx, broken JSON, an entry without a version, an address or a SHA-256). */
    BAD_ANSWER,

    /** The download broke off, came back short, or is not the file the manifest describes (its SHA-256). */
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
 * The latest version, read from the public update manifest at [manifestUrl] ([UpdateManifest]), and its APK.
 * Nothing here is secret: no request carries credentials, and an https address is never followed to plain http. The
 * APK must match the manifest's SHA-256 before it is kept; [ApkVerifier] then checks it against the installed app.
 *
 * [client] is the app's shared one. Suspends on Dispatchers.IO; cancelling cancels the HTTP call.
 */
class UpdateFeed(client: OkHttpClient, private val manifestUrl: HttpUrl) {
    private val client: OkHttpClient = client.newBuilder().followSslRedirects(false).build()

    /**
     * This app's entry in the manifest. Always asked afresh (the site's cache may otherwise hand back an older one).
     * @throws UpdateException
     */
    suspend fun latest(): ReleaseInfo {
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        return execute(client.newCall(request)) { response ->
            if (!response.isSuccessful) throw httpError(response.code)
            UpdateManifest.parse(response.body.string(), manifestUrl)
        }
    }

    /**
     * Saves [apk] as [target] (through a `.part` file: [target] only ever holds a whole download whose SHA-256 is the
     * manifest's), reporting [onProgress] (bytes so far, total when known) from the IO thread.
     * @throws UpdateException
     */
    suspend fun download(apk: ReleaseApk, target: File, onProgress: (Long, Long?) -> Unit) {
        val url = apk.url.toHttpUrlOrNull() ?: throw UpdateException(UpdateErrorKind.BAD_ANSWER, "apk url")
        val request = Request.Builder().url(url).build()
        execute(client.newCall(request)) { response ->
            if (!response.isSuccessful) throw UpdateException(UpdateErrorKind.DOWNLOAD_FAILED, "HTTP ${response.code}")
            save(response, target, apk, onProgress)
        }
    }

    private fun httpError(code: Int) = UpdateException(
        when (code) {
            403, 429 -> UpdateErrorKind.FORBIDDEN
            404 -> UpdateErrorKind.NOT_FOUND
            else -> UpdateErrorKind.BAD_ANSWER
        },
        "HTTP $code",
    )

    private fun save(response: Response, target: File, apk: ReleaseApk, onProgress: (Long, Long?) -> Unit) {
        val expectedSize = apk.size
        val total = response.body.contentLength().takeIf { it > 0 } ?: expectedSize.takeIf { it > 0 }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
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
                        digest.update(buffer, 0, read)
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
            if (digest.digest().toHex() != apk.sha256) throw UpdateException(UpdateErrorKind.DOWNLOAD_FAILED, "sha256 mismatch")
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
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024L

        /** [file]'s SHA-256 as 64 lowercase hex digits (an earlier download is only reused when it still matches). */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
