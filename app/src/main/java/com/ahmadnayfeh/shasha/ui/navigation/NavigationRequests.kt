package com.ahmadnayfeh.shasha.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ahmadnayfeh.shasha.ShashaApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/** How a [NavigationRequests.open] went. */
enum class OpenResult {
    OPENED,

    /** The screens on the TV are another profile's than the one the destination was allowed for: nothing opened. */
    OTHER_PROFILE,

    /** No profile's screens follow the requests, they did not answer in time, or their graph lacks the destination. */
    FAILED,
}

/**
 * Destinations asked for from outside the screens: the phone remote's links (SPEC §5). The current
 * profile's nav host carries them out ([FollowNavigationRequests]) and says whether it could.
 *
 * Nothing waits here: a request made while no profile's screens are composed (the setup, the profile
 * picker, the "time up" screen) is not kept for a later nav host, it simply fails. Every request names the
 * profile the policy allowed it for, and only that profile's screens open it: a link never opens in a
 * profile that was chosen after it was checked.
 */
class NavigationRequests {
    class Request internal constructor(
        val destination: Screen,
        /** The profile the policy allowed [destination] for. */
        val profileId: String,
        internal val done: CompletableDeferred<OpenResult>,
    )

    private val flow = MutableSharedFlow<Request>(extraBufferCapacity = 4)

    /** What the nav host collects. */
    val requests: SharedFlow<Request> = flow.asSharedFlow()

    /** Whether a nav host follows the requests right now. */
    val isFollowed: Boolean get() = flow.subscriptionCount.value > 0

    /**
     * Asks the nav host to open [destination], allowed for [profileId]. [OpenResult.FAILED] when no nav host
     * follows, or none answered within [timeoutMs].
     */
    suspend fun open(destination: Screen, profileId: String, timeoutMs: Long = TIMEOUT_MS): OpenResult {
        if (!isFollowed) return OpenResult.FAILED
        val request = Request(destination, profileId, CompletableDeferred())
        if (!flow.tryEmit(request)) return OpenResult.FAILED
        return withTimeoutOrNull(timeoutMs) { request.done.await() } ?: OpenResult.FAILED
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
    }
}

/**
 * Opens what [NavigationRequests] asks for in [nav], the nav host of [profileId] (the current profile). A
 * request allowed for another profile is refused ([OpenResult.OTHER_PROFILE]): the profile changed after the
 * policy checked it. A player in the back stack (its video ended or was unlocked: while one is locked no
 * request comes, SPEC §4) makes way for the new destination, so BACK returns to where the viewer was before
 * it. A destination this profile's graph does not have (an adult page in a child profile) fails. Does
 * nothing outside the app (tests with a plain Application).
 */
@Composable
fun FollowNavigationRequests(nav: NavHostController, profileId: String) {
    val requests = (LocalContext.current.applicationContext as? ShashaApp)?.container?.navigationRequests ?: return
    LaunchedEffect(nav, requests, profileId) {
        requests.requests.collect { request -> request.done.complete(follow(nav, profileId, request)) }
    }
}

/** What [FollowNavigationRequests] does with one request. */
internal fun follow(nav: NavHostController, profileId: String, request: NavigationRequests.Request): OpenResult {
    if (request.profileId != profileId) return OpenResult.OTHER_PROFILE
    return try {
        nav.navigate(request.destination) { popUpTo<Screen.Player> { inclusive = true } }
        OpenResult.OPENED
    } catch (e: IllegalArgumentException) {
        OpenResult.FAILED
    } catch (e: IllegalStateException) {
        OpenResult.FAILED
    }
}
