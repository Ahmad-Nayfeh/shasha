package com.ahmadnayfeh.shasha.remote

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.decodeURLPart
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.IOException

internal val RemoteJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal object Limits {
    const val JSON_BYTES = 16L * 1024
    const val TAKEOUT_BYTES = 20L * 1024 * 1024
    const val BACKUP_BYTES = 10L * 1024 * 1024
    /** Room for multipart boundaries and part headers on top of the file itself. */
    const val MULTIPART_OVERHEAD = 64L * 1024
    /** Parts read while looking for the file (a browser sends exactly one). */
    const val MULTIPART_PARTS = 8
}

/** Error body of every failed request. [code] lets the page react (e.g. "pairing" → re-scan QR). */
@Serializable
internal data class ApiError(val ok: Boolean = false, val code: String, val message: String)

internal class ApiException(
    val status: HttpStatusCode,
    val code: String,
    message: String,
    val retryAfterSeconds: Long? = null,
) : Exception(message)

internal object ErrorCodes {
    const val PAIRING = "pairing"
    const val PARENT_SESSION = "parent_session"
    const val WRONG_PIN = "wrong_pin"
    const val LOCKED_OUT = "locked_out"
    const val BAD_REQUEST = "bad_request"
    const val TOO_LARGE = "too_large"
    const val UNSUPPORTED = "unsupported"
    const val REJECTED = "rejected"
    const val NOT_FOUND = "not_found"
    const val SERVER_ERROR = "server_error"
}

internal fun badRequest(message: String = ServerMessages.BAD_REQUEST) =
    ApiException(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, message)

internal fun tooLarge(maxBytes: Long) =
    ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.TOO_LARGE, ServerMessages.tooLarge(maxBytes))

internal val JsonUtf8 = ContentType.Application.Json.withCharset(Charsets.UTF_8)

internal suspend fun <T> ApplicationCall.respondJson(
    serializer: KSerializer<T>,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(RemoteJson.encodeToString(serializer, value), JsonUtf8, status)
}

internal suspend inline fun <reified T> ApplicationCall.respondJson(value: T, status: HttpStatusCode = HttpStatusCode.OK) =
    respondJson(serializer<T>(), value, status)

internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) =
    respondJson(ApiError.serializer(), ApiError(code = code, message = message), status)

/** Reads the whole body, refusing (413) anything over [maxBytes] without buffering more than that. */
internal suspend fun ApplicationCall.receiveBytesLimited(maxBytes: Long): ByteArray {
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) throw tooLarge(maxBytes)
    val bytes = receiveChannel().readBuffer(maxBytes + 1).readByteArray()
    if (bytes.size > maxBytes) throw tooLarge(maxBytes)
    return bytes
}

internal suspend inline fun <reified T> ApplicationCall.receiveJson(maxBytes: Long = Limits.JSON_BYTES): T {
    val text = receiveBytesLimited(maxBytes).decodeToString()
    return try {
        RemoteJson.decodeFromString(serializer<T>(), text)
    } catch (e: IllegalArgumentException) { // SerializationException included
        throw badRequest()
    }
}

internal class Upload(val fileName: String?, val bytes: ByteArray)

/**
 * A file sent either as the raw body (name in the `X-File-Name` header, URL-encoded, or `?name=`)
 * or as the first file part of a multipart/form-data body.
 */
internal suspend fun ApplicationCall.receiveUpload(maxBytes: Long): Upload {
    if (request.contentType().match(ContentType.MultiPart.FormData)) return receiveMultipartUpload(maxBytes)
    val name = request.headers["X-File-Name"]?.let { runCatching { it.decodeURLPart() }.getOrNull() }
        ?: request.queryParameters["name"]
    val bytes = receiveBytesLimited(maxBytes)
    if (bytes.isEmpty()) throw badRequest(ServerMessages.EMPTY_FILE)
    return Upload(name, bytes)
}

private suspend fun ApplicationCall.receiveMultipartUpload(maxBytes: Long): Upload {
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes + Limits.MULTIPART_OVERHEAD) throw tooLarge(maxBytes)
    // Ktor applies formFieldLimit to every part, the file included, and fails its parser coroutine
    // with an IOException past it. The parser runs as a child of this scope, so that failure (or a
    // dropped connection) ends up in the catch below instead of cancelling the whole call.
    return try {
        coroutineScope {
            val upload = firstFile(receiveMultipart(formFieldLimit = maxBytes + 1), maxBytes)
            coroutineContext.cancelChildren() // do not wait for the parser to read what follows the file
            upload
        }
    } catch (e: IOException) {
        throw tooLarge(maxBytes)
    } catch (e: ApiException) {
        // Under load the part can reach us empty before the parser's failure does; the scope then carries
        // that failure as suppressed, and the file was too large, not empty or missing.
        if (e.suppressed.any { it is IOException }) throw tooLarge(maxBytes)
        throw e
    }
}

private suspend fun firstFile(multipart: MultiPartData, maxBytes: Long): Upload {
    repeat(Limits.MULTIPART_PARTS) {
        val part = multipart.readPart() ?: throw badRequest(ServerMessages.NO_FILE)
        try {
            if (part is PartData.FileItem) {
                val bytes = part.provider().readBuffer(maxBytes + 1).readByteArray()
                if (bytes.size > maxBytes) throw tooLarge(maxBytes)
                if (bytes.isEmpty()) throw badRequest(ServerMessages.EMPTY_FILE)
                return Upload(part.originalFileName, bytes)
            }
        } finally {
            part.release()
        }
    }
    throw badRequest(ServerMessages.NO_FILE)
}
