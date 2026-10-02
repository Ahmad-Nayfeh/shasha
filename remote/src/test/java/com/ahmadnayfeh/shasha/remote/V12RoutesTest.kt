package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * v1.2 on the wire: a link waits for «قبول» on the TV (the answer carries the request), the parent confirms a video
 * from the parent section, and the server notes when a paired phone was last here.
 */
class V12RoutesTest {
    @Test
    fun play_answersWithTheWaitingRequest() = remoteTest { h ->
        val res = client.call("POST", "/api/play", """{"url":"https://youtu.be/abcdefghijk"}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        val json = res.json()
        assertThat(json.str("ok")).isEqualTo("true")
        val request = json.getValue("request").jsonObject
        assertThat(request.str("id")).isEqualTo("req1")
        assertThat(request.str("state")).isEqualTo("PENDING")
        assertThat(request.str("secondsLeft")).isEqualTo("30")
        assertThat(h.controller.calls).containsExactly("playLink(https://youtu.be/abcdefghijk)")
    }

    @Test
    fun play_refused_hasNoRequest() = remoteTest { h ->
        h.controller.playResult = RemotePlayResult.refused("الوضع الجاد مفعّل.")
        val json = client.call("POST", "/api/play", """{"url":"https://youtu.be/abcdefghijk"}""").json()
        assertThat(json.str("ok")).isEqualTo("false")
        assertThat(json["request"]).isEqualTo(JsonNull)
    }

    @Test
    fun status_carriesTheConfirmationAndTheLastRequest() = remoteTest { h ->
        h.controller.status = h.controller.status.copy(
            confirmation = RemoteConfirmation("conf1", "وثائقي", secondsLeft = 95),
            playRequest = RemotePlayRequest("req1", "كرتون", RemotePlayState.REJECTED, "رُفض الطلب على الشاشة."),
        )
        val json = client.call("GET", "/api/status").json()
        val confirmation = json.getValue("confirmation").jsonObject
        assertThat(confirmation.str("id")).isEqualTo("conf1")
        assertThat(confirmation.str("title")).isEqualTo("وثائقي")
        assertThat(confirmation.str("secondsLeft")).isEqualTo("95")
        assertThat(json.getValue("playRequest").jsonObject.str("state")).isEqualTo("REJECTED")
    }

    @Test
    fun confirm_needsTheParentSession_thenForwardsTheAnswer() = remoteTest { h ->
        val none = client.call("POST", "/api/parent/confirm", """{"id":"conf1","approve":true}""")
        assertThat(none.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(none.json().str("code")).isEqualTo("parent_session")
        assertThat(h.controller.calls).doesNotContain("answerConfirmation(conf1,true)")

        val s = client.login()
        h.controller.result = RemoteResult.ok("أُكّد.")
        val yes = client.call("POST", "/api/parent/confirm", """{"id":"conf1","approve":true}""", parent = s)
        assertThat(yes.status).isEqualTo(HttpStatusCode.OK)
        assertThat(yes.json().str("message")).isEqualTo("أُكّد.")
        client.call("POST", "/api/parent/confirm", """{"id":"conf1","approve":false}""", parent = s)
        assertThat(h.controller.calls.filter { it.startsWith("answerConfirmation") })
            .containsExactly("answerConfirmation(conf1,true)", "answerConfirmation(conf1,false)").inOrder()
    }

    @Test
    fun confirm_withoutAnAnswerOrAnId_is400_andNothingIsDecided() = remoteTest { h ->
        val s = client.login()
        for (body in listOf("""{"id":"conf1"}""", """{"id":"","approve":true}""", """{"approve":true}""", """{"id":"conf1","approve":"yes"}""")) {
            assertThat(client.call("POST", "/api/parent/confirm", body, parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        }
        assertThat(h.controller.calls.filter { it.startsWith("answerConfirmation") }).isEmpty()
    }

    @Test
    fun presence_onlyARequestWithTheTokenCounts() = remoteTest { h ->
        h.now = 1_000
        val seen = mutableListOf<Long>()
        h.presence.onSeen = { seen += h.now }
        assertThat(h.presence.sinceLastMillis()).isNull()
        assertThat(client.call("GET", "/api/status", token = "wrong").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(h.presence.seenWithin(60_000)).isFalse()
        assertThat(seen).isEmpty()

        client.call("GET", "/api/status")
        assertThat(seen).containsExactly(1_000L)
        h.now = 61_000
        assertThat(h.presence.sinceLastMillis()).isEqualTo(60_000)
        assertThat(h.presence.seenWithin(60_000)).isTrue()
        h.now = 61_001
        assertThat(h.presence.seenWithin(60_000)).isFalse()
        client.call("GET", "/api/profiles")
        assertThat(h.presence.seenWithin(60_000)).isTrue()
        h.presence.forget()
        assertThat(h.presence.seenWithin(60_000)).isFalse()
        assertThat(h.presence.sinceLastMillis()).isNull()
    }

    @Test
    fun presence_aFailingListener_neverBreaksTheRequest() = remoteTest { h ->
        h.presence.onSeen = { throw IllegalStateException("disk full") }
        val res = client.call("GET", "/api/status")
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        assertThat(res.json().getValue("profileName").jsonPrimitive.content).isEqualTo("أحمد")
        assertThat(h.presence.seenWithin(1)).isTrue()
    }
}
