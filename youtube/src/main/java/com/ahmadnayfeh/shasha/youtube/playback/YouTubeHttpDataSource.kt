package com.ahmadnayfeh.shasha.youtube.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.HttpUtil
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.math.min

/**
 * Media3 [HttpDataSource] on OkHttp that applies [GoogleVideoQuirks] to googlevideo "/videoplayback"
 * URLs (request number, range parameter, POST, the User-Agent of the client that produced the URL).
 * Any other URL (subtitles, HLS playlists, manifests) is a plain GET with a Range header.
 */
@OptIn(UnstableApi::class)
internal class YouTubeHttpDataSource(
    private val callFactory: Call.Factory,
    private val quirks: GoogleVideoQuirks,
    private val defaultRequestProperties: HttpDataSource.RequestProperties,
) : BaseDataSource(/* isNetwork= */ true), HttpDataSource {

    class Factory(private val callFactory: Call.Factory, private val quirks: GoogleVideoQuirks) : HttpDataSource.Factory {
        private val defaultRequestProperties = HttpDataSource.RequestProperties()

        override fun createDataSource(): HttpDataSource =
            YouTubeHttpDataSource(callFactory, quirks, defaultRequestProperties)

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): Factory {
            this.defaultRequestProperties.clearAndSet(defaultRequestProperties)
            return this
        }
    }

    private val requestProperties = HttpDataSource.RequestProperties()
    private var requestNumber = 0L

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var stream: InputStream? = null
    private var opened = false
    private var bytesToRead = 0L
    private var bytesRead = 0L

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0
        bytesToRead = 0
        transferInitializing(dataSpec)

        val plan = GoogleVideoRequests.plan(
            url = dataSpec.uri.toString(),
            position = dataSpec.position,
            length = dataSpec.length,
            quirks = quirks,
            nextRequestNumber = { requestNumber++ },
        )
        val response = try {
            execute(buildRequest(plan, dataSpec))
        } catch (e: IOException) {
            throw HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSourceException.TYPE_OPEN)
        }
        this.response = response
        val code = response.code

        if (!response.isSuccessful) {
            if (code == 416 && !plan.rangeInUrl &&
                dataSpec.position == HttpUtil.getDocumentSize(response.header("Content-Range"))
            ) {
                // Asked for the byte right after the end: an empty read, not an error.
                opened = true
                transferStarted(dataSpec)
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0
            }
            val body = try {
                response.body.bytes()
            } catch (e: IOException) {
                ByteArray(0)
            }
            val headers = response.headers.toMultimap()
            closeQuietly()
            val cause = if (code == 416) DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) else null
            throw InvalidResponseCodeException(code, response.message, cause, headers, dataSpec, body)
        }

        // With a Range header, a 200 (instead of 206) means the server ignored the range: skip manually.
        // With the range in the URL the body always starts at the requested position.
        val bytesToSkip = if (!plan.rangeInUrl && code == 200 && dataSpec.position != 0L) dataSpec.position else 0L
        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            val contentLength = HttpUtil.getContentLength(response.header("Content-Length"), response.header("Content-Range"))
            if (contentLength != C.LENGTH_UNSET.toLong()) contentLength - bytesToSkip else C.LENGTH_UNSET.toLong()
        }
        stream = response.body.byteStream()
        opened = true
        transferStarted(dataSpec)
        try {
            skipFully(bytesToSkip, dataSpec)
        } catch (e: HttpDataSourceException) {
            closeQuietly()
            throw e
        }
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        try {
            return readInternal(buffer, offset, length)
        } catch (e: IOException) {
            throw HttpDataSourceException.createForIOException(e, checkNotNull(dataSpec), HttpDataSourceException.TYPE_READ)
        }
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        closeQuietly()
        response = null
        dataSpec = null
    }

    override fun getUri(): Uri? = response?.let { Uri.parse(it.request.url.toString()) }

    override fun getResponseCode(): Int = response?.code ?: -1

    override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap() ?: emptyMap()

    override fun setRequestProperty(name: String, value: String) = requestProperties.set(name, value)

    override fun clearRequestProperty(name: String) = requestProperties.remove(name)

    override fun clearAllRequestProperties() = requestProperties.clear()

    private fun buildRequest(plan: PlannedRequest, dataSpec: DataSpec): Request {
        val builder = Request.Builder().url(plan.url)
        val headers = LinkedHashMap<String, String>()
        headers.putAll(defaultRequestProperties.snapshot)
        headers.putAll(requestProperties.snapshot)
        headers.putAll(dataSpec.httpRequestHeaders)
        headers.putAll(plan.headers) // the googlevideo rules win over anything generic
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            // Otherwise OkHttp asks for gzip and hides Content-Length, breaking byte accounting.
            builder.header("Accept-Encoding", "identity")
        }
        val body = plan.body ?: dataSpec.httpBody
        return when {
            plan.method == "POST" -> builder.post((plan.body ?: ByteArray(0)).toRequestBody(FORM))
            body != null && dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST -> builder.post(body.toRequestBody())
            else -> builder.get()
        }.build()
    }

    /**
     * Enqueue + wait instead of Call.execute(): Media3 cancels loads by interrupting the loader
     * thread, and a blocking execute() would ignore that until a socket timeout.
     */
    private fun execute(request: Request): Response {
        val call = callFactory.newCall(request)
        val future = CompletableFuture<Response>()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                future.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!future.complete(response)) response.close()
            }
        })
        try {
            return future.get()
        } catch (e: InterruptedException) {
            call.cancel()
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause ?: e)
        }
    }

    private fun skipFully(bytesToSkip: Long, dataSpec: DataSpec) {
        if (bytesToSkip == 0L) return
        val skipBuffer = ByteArray(4096)
        var remaining = bytesToSkip
        try {
            while (remaining > 0) {
                val read = checkNotNull(stream).read(skipBuffer, 0, min(remaining, skipBuffer.size.toLong()).toInt())
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                if (read == -1) {
                    throw HttpDataSourceException(
                        dataSpec, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, HttpDataSourceException.TYPE_OPEN,
                    )
                }
                remaining -= read
                bytesTransferred(read)
            }
        } catch (e: HttpDataSourceException) {
            throw e
        } catch (e: IOException) {
            throw HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSourceException.TYPE_OPEN)
        }
    }

    private fun readInternal(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var readLength = length
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            readLength = min(readLength.toLong(), remaining).toInt()
        }
        val read = checkNotNull(stream).read(buffer, offset, readLength)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRead += read
        bytesTransferred(read)
        return read
    }

    private fun closeQuietly() {
        stream = null
        response?.body?.closeQuietlyCompat()
    }

    private fun okhttp3.ResponseBody.closeQuietlyCompat() {
        try {
            close()
        } catch (_: Exception) {
            // Closing a half-read body can fail on a broken socket; nothing to recover.
        }
    }

    private companion object {
        // HttpURLConnection (NewPipe's player, known to work) sends this type for a POST by default;
        // OkHttp would send no Content-Type at all.
        val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}
