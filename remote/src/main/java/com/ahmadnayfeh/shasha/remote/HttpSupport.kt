package com.ahmadnayfeh.shasha.remote

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

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
    val bytes = receiveChannel().readRemaining(maxBytes + 1).readByteArray()
    if (bytes.size > maxBytes) throw tooLarge(maxBytes)
    return bytes
}

internal suspend inline fun <reified T> ApplicationCall.receiveJson(maxBytes: Long = Limits.JSON_BYTES): T {
    val text = receiveBytesLimited(maxBytes).decodeToString()
    return try {
        RemoteJson.decodeFromString(serializer<T>(), text)
    } catch (e: SerializationException) {
        throw badRequest()
    } catch (e: IllegalArgumentException) {
        throw badRequest()
    }
}

internal class Upload(val fileName: String?, val bytes: ByteArray)

/**
 * A file sent either as the raw body (name in the `X-File-Name` header, URL-encoded, or `?name=`)
 * or as the first file part of a multipart/form-data body.
 */
internal suspend fun ApplicationCall.receiveUpload(maxBytes: Long): Upload {
    if (request.contentType().match(ContentType.MultiPart.FormData)) {
        val declared = request.contentLength()
        if (declared != null && declared > maxBytes + Limits.MULTIPART_OVERHEAD) throw tooLarge(maxBytes)
        val multipart = receiveMultipart(formFieldLimit = maxBytes + 2)
        while (true) {
            val part = multipart.readPart() ?: break
            try {
                if (part is PartData.FileItem) {
                    val bytes = part.provider().readRemaining(maxBytes + 1).readByteArray()
                    if (bytes.size > maxBytes) throw tooLarge(maxBytes)
                    if (bytes.isEmpty()) throw badRequest(ServerMessages.EMPTY_FILE)
                    return Upload(part.originalFileName, bytes)
                }
            } finally {
                part.dispose()
            }
        }
        throw badRequest(ServerMessages.NO_FILE)
    }
    val name = request.headers["X-File-Name"]?.let { runCatching { it.decodeURLPart() }.getOrNull() }
        ?: request.queryParameters["name"]
    val bytes = receiveBytesLimited(maxBytes)
    if (bytes.isEmpty()) throw badRequest(ServerMessages.EMPTY_FILE)
    return Upload(name, bytes)
}
