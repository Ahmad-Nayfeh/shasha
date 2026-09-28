package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.junit.Test

class ParentSessionTest {
    private val minute = 60_000L

    @Test
    fun parentRoutes_withoutSession_are401_withBogusSession_are403() = remoteTest { h ->
        for ((method, path, body) in PARENT_ROUTES) {
            val missing = client.call(method, path, body)
            assertWithMessage("$method $path").that(missing.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(missing.json().str("code")).isEqualTo("parent_session")
            val bogus = client.call(method, path, body, parent = "not-a-session")
            assertWithMessage("$method $path").that(bogus.status).isEqualTo(HttpStatusCode.Forbidden)
            assertThat(bogus.json().str("code")).isEqualTo("parent_session")
        }
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun login_success_createsAWorkingSession() = remoteTest { h ->
        val res = client.call("POST", "/api/parent/login", """{"pin":"2468"}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        val json = res.json()
        assertThat(json.str("ok")).isEqualTo("true")
        val session = json.str("session")
        assertThat(session.length).isAtLeast(40)
        assertThat(h.controller.calls).containsExactly("parentLogin(2468)")
        for ((method, path, body) in PARENT_ROUTES) {
            val r = client.call(method, path, body, parent = session)
            assertWithMessage("$method $path").that(r.status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun login_wrongPin_is401_withAttemptsLeft() = remoteTest { h ->
        h.controller.loginResult = ParentLoginResult.WrongPin(attemptsLeft = 2)
        val res = client.call("POST", "/api/parent/login", """{"pin":"0000"}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.Unauthorized)
        val json = res.json()
        assertThat(json.str("code")).isEqualTo("wrong_pin")
        assertThat(json.str("message")).isEqualTo("رمز PIN غير صحيح. بقيت محاولتان.")
        assertThat(json).doesNotContainKey("session")
        assertThat(h.sessions.size()).isEqualTo(0)
    }

    @Test
    fun login_lockedOut_is429_withRetryAfter() = remoteTest { h ->
        h.controller.loginResult = ParentLoginResult.LockedOut(retryAfterSeconds = 270)
        val res = client.call("POST", "/api/parent/login", """{"pin":"1234"}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.TooManyRequests)
        assertThat(res.headers[HttpHeaders.RetryAfter]).isEqualTo("270")
        val json = res.json()
        assertThat(json.str("code")).isEqualTo("locked_out")
        assertThat(json.str("message")).isEqualTo("محاولات خاطئة كثيرة. حاول بعد 5 دقائق.")
        assertThat(h.sessions.size()).isEqualTo(0)
    }

    @Test
    fun login_emptyOrHugePin_isRejectedBeforeTheController() = remoteTest { h ->
        assertThat(client.call("POST", "/api/parent/login", """{"pin":""}""").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/parent/login", """{"pin":"${"1".repeat(40)}"}""").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/parent/login", """{}""").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun session_slidesWithUse_andExpiresAfter30IdleMinutes() = remoteTest { h ->
        val session = client.login()
        h.now += 29 * minute
        assertThat(client.call("POST", "/api/parent/unlock-video", parent = session).status).isEqualTo(HttpStatusCode.OK)
        h.now += 29 * minute // 58 min after login, 29 after last use
        assertThat(client.call("POST", "/api/parent/unlock-video", parent = session).status).isEqualTo(HttpStatusCode.OK)
        h.now += 30 * minute
        val expired = client.call("POST", "/api/parent/unlock-video", parent = session)
        assertThat(expired.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(expired.json().str("message")).contains("انتهت جلسة")
        assertThat(h.controller.calls.count { it == "unlockVideo" }).isEqualTo(2)
    }

    @Test
    fun logout_endsTheSession() = remoteTest {
        val session = client.login()
        assertThat(client.call("POST", "/api/parent/logout", parent = session).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("POST", "/api/parent/unlock-video", parent = session).status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun atMostEightSessions_oldestIsDropped() = remoteTest {
        val sessions = (1..9).map { client.login() }
        assertThat(client.call("POST", "/api/parent/unlock-video", parent = sessions.first()).status).isEqualTo(HttpStatusCode.Forbidden)
        for (s in sessions.drop(1)) {
            assertThat(client.call("POST", "/api/parent/unlock-video", parent = s).status).isEqualTo(HttpStatusCode.OK)
        }
    }
}
