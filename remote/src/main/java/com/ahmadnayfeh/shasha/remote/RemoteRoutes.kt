package com.ahmadnayfeh.shasha.remote

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import java.time.LocalDate

internal const val PARENT_SESSION_HEADER = "X-Parent-Session"

@Serializable internal data class SearchRequest(val q: String)
@Serializable internal data class PlayRequest(val url: String)
@Serializable internal data class ProfileRequest(val id: String, val pin: String? = null)
@Serializable internal data class LoginRequest(val pin: String)
@Serializable internal data class LoginResponse(val ok: Boolean, val session: String, val message: String)
// minutes / extraMinutes have no default on purpose: a request that forgets them must not mean "unlimited".
@Serializable internal data class QuotaRequest(val profileId: String, val minutes: Int?)
@Serializable internal data class UnlockTimeRequest(val extraMinutes: Int?)
@Serializable internal data class AllowlistAddRequest(val profileId: String, val link: String)
@Serializable internal data class AllowlistRemoveRequest(val profileId: String, val id: String)
@Serializable internal data class LinkRequest(val link: String)
@Serializable internal data class ChannelRequest(val channelId: String)

private const val MAX_TEXT = 2048
private const val MAX_MINUTES = 24 * 60
private const val MAX_SEEK_SECONDS = 24 * 60 * 60

internal fun Application.remoteModule(
    controller: RemoteController,
    tokenProvider: () -> String,
    sessions: ParentSessions,
    pages: RemotePages = RemotePages.fromClasspath(),
    onError: (Throwable) -> Unit = {},
) {
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("X-Frame-Options", "DENY")

        val request = call.request
        val bearer = request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        val expected = tokenProvider()
        val authorized = RemoteTokens.matches(bearer, expected) ||
            RemoteTokens.matches(request.queryParameters["t"], expected)
        if (!authorized) {
            val browserNavigation = request.httpMethod == HttpMethod.Get && request.path() == "/" &&
                request.headers[HttpHeaders.Accept].orEmpty().contains("text/html")
            if (browserNavigation) {
                // A person, not a script: explain how to pair (and retry with a remembered token).
                call.respondPage(pages.unpaired, HttpStatusCode.Unauthorized)
            } else {
                call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.PAIRING, ServerMessages.PAIRING)
            }
            finish()
            return@intercept
        }
        try {
            proceed()
        } catch (e: CancellationException) {
            // The phone went away: nothing to answer. A cancellation from inside the controller (e.g. its
            // own withTimeout) while the call is still alive is a failure like any other.
            if (!currentCoroutineContext().isActive) throw e
            call.respondFailure(e, onError)
        } catch (e: Throwable) {
            call.respondFailure(e, onError)
        }
    }

    routing {
        get("/") { call.respondPage(pages.main, HttpStatusCode.OK) }
        get("/icon.png") {
            val icon = pages.icon ?: throw ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, ServerMessages.NOT_FOUND)
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondBytes(icon, ContentType.Image.PNG)
        }

        route("/api") {
            get("/status") { call.respondJson(controller.status()) }
            post("/search") {
                val q = call.receiveJson<SearchRequest>().q.trim()
                if (q.isEmpty() || q.length > 200) throw badRequest(ServerMessages.EMPTY_QUERY)
                // Defence in depth: the child profile has no search at all, whatever the controller does.
                if (!controller.status().canSearch) throw rejected(ServerMessages.SEARCH_DISABLED)
                call.respondJson(controller.search(q))
            }
            post("/play") {
                val url = call.receiveJson<PlayRequest>().url.trim()
                if (url.isEmpty()) throw badRequest(ServerMessages.EMPTY_LINK)
                if (url.length > MAX_TEXT) throw badRequest()
                call.respondJson(controller.playLink(url))
            }
            post("/control") {
                val cmd = call.receiveJson<PlaybackCommand>()
                if (cmd.action == PlaybackAction.SEEK_BY && (cmd.seconds == 0 || kotlin.math.abs(cmd.seconds) > MAX_SEEK_SECONDS)) {
                    throw badRequest()
                }
                call.respondJson(controller.control(cmd))
            }
            get("/profiles") { call.respondJson(controller.profiles()) }
            post("/profile") {
                val req = call.receiveJson<ProfileRequest>()
                requireText(req.id)
                val pin = req.pin?.takeIf { it.isNotEmpty() }
                if (pin != null && pin.length > 32) throw badRequest()
                call.respondJson(controller.switchProfile(req.id, pin))
            }

            route("/parent") {
                post("/login") {
                    val pin = call.receiveJson<LoginRequest>().pin
                    if (pin.isEmpty()) throw badRequest(ServerMessages.EMPTY_PIN)
                    if (pin.length > 32) throw badRequest()
                    when (val result = controller.parentLogin(pin)) {
                        ParentLoginResult.Success ->
                            call.respondJson(LoginResponse(ok = true, session = sessions.create(), message = ServerMessages.PARENT_WELCOME))
                        is ParentLoginResult.WrongPin -> throw ApiException(
                            HttpStatusCode.Unauthorized, ErrorCodes.WRONG_PIN, ServerMessages.wrongPin(result.attemptsLeft),
                        )
                        is ParentLoginResult.LockedOut -> throw ApiException(
                            HttpStatusCode.TooManyRequests, ErrorCodes.LOCKED_OUT,
                            ServerMessages.lockedOut(result.retryAfterSeconds), retryAfterSeconds = result.retryAfterSeconds.coerceAtLeast(1),
                        )
                    }
                }
                post("/logout") {
                    call.request.headers[PARENT_SESSION_HEADER]?.let(sessions::remove)
                    call.respondJson(RemoteResult.ok(ServerMessages.LOGGED_OUT))
                }
                post("/quota") {
                    call.requireParent(sessions)
                    val req = call.receiveJson<QuotaRequest>()
                    requireText(req.profileId)
                    if (req.minutes != null && req.minutes !in 0..MAX_MINUTES) throw badRequest()
                    call.respondJson(controller.setQuota(req.profileId, req.minutes))
                }
                post("/unlock-time") {
                    call.requireParent(sessions)
                    val extra = call.receiveJson<UnlockTimeRequest>().extraMinutes
                    if (extra != null && extra !in 1..MAX_MINUTES) throw badRequest()
                    call.respondJson(controller.unlockTimeUp(extra))
                }
                post("/unlock-video") {
                    call.requireParent(sessions)
                    call.respondJson(controller.unlockVideo())
                }
                get("/allowlist") {
                    call.requireParent(sessions)
                    val profileId = call.request.queryParameters["profileId"].orEmpty()
                    requireText(profileId)
                    call.respondJson(controller.allowlist(profileId))
                }
                post("/allowlist/add") {
                    call.requireParent(sessions)
                    val req = call.receiveJson<AllowlistAddRequest>()
                    requireText(req.profileId)
                    requireText(req.link.trim(), ServerMessages.EMPTY_LINK)
                    call.respondJson(controller.addToAllowlist(req.profileId, req.link.trim()))
                }
                post("/allowlist/remove") {
                    call.requireParent(sessions)
                    val req = call.receiveJson<AllowlistRemoveRequest>()
                    requireText(req.profileId)
                    requireText(req.id)
                    call.respondJson(controller.removeFromAllowlist(req.profileId, req.id))
                }
                post("/takeout") {
                    call.requireParent(sessions)
                    val upload = call.receiveUpload(Limits.TAKEOUT_BYTES)
                    val csv = TakeoutFiles.toCsv(upload.fileName, upload.bytes)
                    call.respondJson(controller.importTakeout(csv.fileName, csv.bytes))
                }
                get("/subscriptions") {
                    call.requireParent(sessions)
                    call.respondJson(controller.subscriptions())
                }
                post("/subscriptions/add") {
                    call.requireParent(sessions)
                    val link = call.receiveJson<LinkRequest>().link.trim()
                    requireText(link, ServerMessages.EMPTY_LINK)
                    call.respondJson(controller.addSubscription(link))
                }
                post("/subscriptions/remove") {
                    call.requireParent(sessions)
                    val channelId = call.receiveJson<ChannelRequest>().channelId
                    requireText(channelId)
                    call.respondJson(controller.removeSubscription(channelId))
                }
                get("/backup") {
                    call.requireParent(sessions)
                    val bytes = controller.exportBackup()
                    val name = "shasha-backup-${LocalDate.now()}.json"
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString(),
                    )
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondBytes(bytes, JsonUtf8)
                }
                post("/backup") {
                    call.requireParent(sessions)
                    val upload = call.receiveUpload(Limits.BACKUP_BYTES)
                    call.respondJson(controller.importBackup(upload.bytes))
                }
            }
        }

        route("{...}") {
            handle { throw ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, ServerMessages.NOT_FOUND) }
        }
    }
}

private fun rejected(message: String) = ApiException(HttpStatusCode.Forbidden, ErrorCodes.REJECTED, message)

private fun requireText(value: String, message: String = ServerMessages.BAD_REQUEST) {
    if (value.isBlank() || value.length > MAX_TEXT) throw badRequest(message)
}

private fun ApplicationCall.requireParent(sessions: ParentSessions) {
    val id = request.headers[PARENT_SESSION_HEADER]
    if (id.isNullOrEmpty()) {
        throw ApiException(HttpStatusCode.Unauthorized, ErrorCodes.PARENT_SESSION, ServerMessages.PARENT_REQUIRED)
    }
    if (!sessions.touch(id)) {
        throw ApiException(HttpStatusCode.Forbidden, ErrorCodes.PARENT_SESSION, ServerMessages.PARENT_EXPIRED)
    }
}

private suspend fun ApplicationCall.respondPage(page: WebPage, status: HttpStatusCode) {
    response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
    response.header("Content-Security-Policy", page.contentSecurityPolicy)
    respondBytes(page.bytes, ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
}

private suspend fun ApplicationCall.respondFailure(e: Throwable, onError: (Throwable) -> Unit) {
    if (e !is ApiException && e !is RemoteRejection) runCatching { onError(e) }
    if (response.isCommitted) return
    when (e) {
        is ApiException -> {
            e.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
            respondError(e.status, e.code, e.message.orEmpty())
        }
        is RemoteRejection -> respondError(HttpStatusCode.Forbidden, ErrorCodes.REJECTED, e.userMessage)
        // Never echo exception text: it may carry internals (paths, URLs, tokens).
        else -> respondError(HttpStatusCode.InternalServerError, ErrorCodes.SERVER_ERROR, ServerMessages.SERVER_ERROR)
    }
}

/** The pages the server can serve, loaded once. */
internal class RemotePages(val main: WebPage, val unpaired: WebPage, val icon: ByteArray?) {
    companion object {
        fun fromClasspath() = RemotePages(
            main = WebPage.load("web/index.html"),
            unpaired = WebPage.load("web/unpaired.html"),
            icon = WebPage.resource("web/icon.png"),
        )
    }
}
