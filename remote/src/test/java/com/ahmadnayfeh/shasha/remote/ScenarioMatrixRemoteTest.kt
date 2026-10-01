package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.client.statement.bodyAsText
import org.junit.Test

/**
 * The rows of the phone ↔ TV scenario matrix (docs/dev/phone-tv-scenarios.md) that need the server's own clock: the
 * parent session's sliding 30 minutes and its expiry in the middle of an action. The rest of the matrix runs against
 * the TV's real rules in :app (ScenarioMatrixTest).
 */
class ScenarioMatrixRemoteTest {
    @Test
    fun `PS-03 a session idle 29 minutes still works, and every use slides it`() = remoteTest { h ->
        val session = client.login()
        h.now += 29 * 60_000L
        assertThat(client.call("GET", "/api/parent/settings", parent = session).status.value).isEqualTo(200)
        h.now += 29 * 60_000L
        assertThat(client.call("GET", "/api/parent/settings", parent = session).status.value).isEqualTo(200)
    }

    @Test
    fun `PS-04 a session idle 30 minutes is refused on every parent route, and the TV is never asked`() = remoteTest { h ->
        val session = client.login()
        h.now += 30 * 60_000L
        h.controller.calls.clear()
        for ((method, path, body) in PARENT_ROUTES) {
            val response = client.call(method, path, body, parent = session)
            assertWithMessage("$method $path").that(response.status.value).isEqualTo(403)
            assertWithMessage("$method $path").that(response.json().str("code")).isEqualTo("parent_session")
        }
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun `PS-05 a session that expired mid-action reaches nothing on the TV, and after a login the same write does`() = remoteTest { h ->
        val session = client.login()
        h.now += 31 * 60_000L
        h.controller.calls.clear()
        val refused = client.call("POST", "/api/parent/quota", """{"profileId":"c1","minutes":30}""", parent = session)
        assertThat(refused.status.value).isEqualTo(403)
        assertThat(h.controller.calls).isEmpty()
        val again = client.login()
        val done = client.call("POST", "/api/parent/quota", """{"profileId":"c1","minutes":30}""", parent = again)
        assertThat(done.status.value).isEqualTo(200)
        assertThat(h.controller.calls).containsExactly("parentLogin(1234)", "setQuota(c1,30)").inOrder()
    }

    @Test
    fun `LG-16 the server brake forgets after 6 hours without a failure`() = remoteTest { h ->
        h.controller.loginResult = ParentLoginResult.WrongPin(3)
        repeat(4) { client.call("POST", "/api/parent/login", """{"pin":"0000"}""") }
        h.now += 6 * 60 * 60_000L
        // Four more failures do not block: the old ones were forgotten.
        repeat(4) {
            assertThat(client.call("POST", "/api/parent/login", """{"pin":"0000"}""").status.value).isEqualTo(401)
        }
        h.controller.loginResult = ParentLoginResult.Success
        assertThat(client.call("POST", "/api/parent/login", """{"pin":"1234"}""").bodyAsText()).contains("\"ok\":true")
    }
}
