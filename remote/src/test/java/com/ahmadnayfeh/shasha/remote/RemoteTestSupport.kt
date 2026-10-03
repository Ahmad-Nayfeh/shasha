package com.ahmadnayfeh.shasha.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

const val TOKEN = "Pa1r-token_for_tests"

/** Every route: method, path, and a valid JSON body for POSTs. */
val PUBLIC_ROUTES = listOf(
    Triple("GET", "/", null),
    Triple("GET", "/icon.png", null),
    Triple("GET", "/api/status", null),
    Triple("POST", "/api/search", """{"q":"x"}"""),
    Triple("POST", "/api/search/page", """{"q":"x","page":"p2"}"""),
    Triple("POST", "/api/play", """{"url":"https://youtu.be/abcdefghijk"}"""),
    Triple("POST", "/api/control", """{"action":"PAUSE"}"""),
    Triple("GET", "/api/profiles", null),
    Triple("POST", "/api/profile", """{"id":"a"}"""),
    Triple("POST", "/api/parent/login", """{"pin":"1234"}"""),
    Triple("POST", "/api/parent/logout", null),
)
val PARENT_ROUTES = listOf(
    Triple("POST", "/api/parent/quota", """{"profileId":"c1","minutes":60}"""),
    Triple("POST", "/api/parent/unlock-time", """{"extraMinutes":15}"""),
    Triple("POST", "/api/parent/unlock-video", null),
    Triple("POST", "/api/parent/confirm", """{"id":"conf1","approve":true}"""),
    Triple("GET", "/api/parent/allowlist?profileId=c1", null),
    Triple("POST", "/api/parent/allowlist/add", """{"profileId":"c1","link":"https://youtube.com/@x"}"""),
    Triple("POST", "/api/parent/allowlist/remove", """{"profileId":"c1","id":"UCx"}"""),
    Triple("POST", "/api/parent/takeout", "UC1234567890123456789012,http://x,قناة"),
    Triple("GET", "/api/parent/subscriptions", null),
    Triple("POST", "/api/parent/subscriptions/add", """{"link":"https://youtube.com/@x"}"""),
    Triple("POST", "/api/parent/subscriptions/remove", """{"channelId":"UCx"}"""),
    Triple("GET", "/api/parent/backup", null),
    Triple("POST", "/api/parent/backup", """{"v":1}"""),
    Triple("GET", "/api/parent/settings", null),
    Triple("POST", "/api/parent/settings", """{"key":"serious_mode_children","value":false}"""),
    Triple("POST", "/api/parent/settings/reset", """{"group":"serious"}"""),
    Triple("GET", "/api/parent/profiles", null),
    Triple("POST", "/api/parent/profile/flags", """{"profileId":"c1","hideShorts":true}"""),
    Triple("POST", "/api/parent/profile/create", """{"name":"ليلى","avatar":"star"}"""),
    Triple("POST", "/api/parent/profile/edit", """{"profileId":"c1","name":"سارة"}"""),
    Triple("POST", "/api/parent/profile/delete", """{"profileId":"c1"}"""),
    Triple("POST", "/api/parent/schedule", """{"profileId":"c1","fromMinute":420,"untilMinute":1320}"""),
    Triple("GET", "/api/parent/blocklist", null),
    Triple("POST", "/api/parent/blocklist/add", """{"kind":"keyword","value":"رعب"}"""),
    Triple("POST", "/api/parent/blocklist/remove", """{"kind":"channel","value":"UCbad"}"""),
    Triple("GET", "/api/parent/stats", null),
)

internal class Harness(val controller: FakeController = FakeController()) {
    var now = 0L
    val sessions = ParentSessions({ now })
    val pinGate = PinGate { now }
    val presence = PhonePresence { now }
    var token = TOKEN
    /** What the server passed to onError (unexpected controller exceptions). */
    val errors = CopyOnWriteArrayList<Throwable>()
}

internal fun remoteTest(harness: Harness = Harness(), block: suspend ApplicationTestBuilder.(Harness) -> Unit) = testApplication {
    application {
        remoteModule(harness.controller, { harness.token }, harness.sessions, harness.pinGate, presence = harness.presence, onError = harness.errors::add)
    }
    block(harness)
}

suspend fun HttpClient.call(
    method: String,
    path: String,
    body: String? = null,
    token: String? = TOKEN,
    parent: String? = null,
): HttpResponse {
    val configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        token?.let { bearerAuth(it) }
        parent?.let { header(PARENT_SESSION_HEADER, it) }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
    return if (method == "GET") get(path, configure) else post(path, configure)
}

suspend fun HttpClient.login(pin: String = "1234"): String {
    val json = Json.parseToJsonElement(call("POST", "/api/parent/login", """{"pin":"$pin"}""").bodyAsText()).jsonObject
    return json.getValue("session").jsonPrimitive.content
}

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
