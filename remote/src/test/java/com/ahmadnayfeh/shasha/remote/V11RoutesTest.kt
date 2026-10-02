package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** v1.1 parent routes: settings, profile rules, the block list and the stats reach the controller as sent, checked first. */
class V11RoutesTest {

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.session() = client.login()

    @Test
    fun settings_get_change_reset_reachTheController() = remoteTest { h ->
        val s = session()
        val page = client.call("GET", "/api/parent/settings", parent = s)
        assertThat(page.status).isEqualTo(HttpStatusCode.OK)
        assertThat(page.json().getValue("groups").jsonArray.single().jsonObject.str("id")).isEqualTo("serious")

        val change = client.call("POST", "/api/parent/settings", """{"key":"serious_mode_children","value":false}""", parent = s)
        assertThat(change.status).isEqualTo(HttpStatusCode.OK)
        assertThat(change.json().str("ok")).isEqualTo("true")
        assertThat(client.call("POST", "/api/parent/settings", """{"key":"extra_minutes","value":[15,30]}""", parent = s).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("POST", "/api/parent/settings/reset", """{"group":"pin"}""", parent = s).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("POST", "/api/parent/settings/reset", """{"group":null}""", parent = s).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("POST", "/api/parent/settings/reset", "{}", parent = s).status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.calls.filter { it != "parentLogin(1234)" }).containsExactly(
            "settings", "changeSetting(serious_mode_children,false)", "changeSetting(extra_minutes,[15,30])",
            "resetSettings(pin)", "resetSettings(null)", "resetSettings(null)",
        ).inOrder()
    }

    @Test
    fun settings_theBaseValueReachesTheController_nullMeansNone() = remoteTest { h ->
        val s = session()
        client.call("POST", "/api/parent/settings", """{"key":"extra_minutes","value":[15,30],"base":[15,30,60]}""", parent = s)
        client.call("POST", "/api/parent/settings", """{"key":"serious_mode_children","value":false,"base":null}""", parent = s)
        assertThat(h.controller.calls.filter { it.startsWith("changeSetting") }).containsExactly(
            "changeSetting(extra_minutes,[15,30],base=[15,30,60])", "changeSetting(serious_mode_children,false)",
        ).inOrder()
    }

    @Test
    fun settings_reconnectPortIsPassedOn() = remoteTest { h ->
        h.controller.settingsResult = RemoteSettingsResult(true, "ينتقل", h.controller.settingsPage, reconnectPort = 9000)
        val res = client.call("POST", "/api/parent/settings", """{"key":"remote_port","value":9000}""", parent = session())
        assertThat(res.json().str("reconnectPort")).isEqualTo("9000")
    }

    @Test
    fun settings_badShapes_are400_andNeverReachTheController() = remoteTest { h ->
        val s = session()
        val bad = listOf(
            """{"key":"","value":true}""",
            """{"key":"${"k".repeat(65)}","value":true}""",
            """{"value":true}""",
            """{"key":"serious_mode_children"}""",
            "not json",
        )
        for (body in bad) assertThat(client.call("POST", "/api/parent/settings", body, parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/parent/settings/reset", """{"group":""}""", parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(h.controller.calls.none { it.startsWith("changeSetting") || it.startsWith("resetSettings") }).isTrue()
    }

    @Test
    fun profileFlagsAndSchedule_areChecked() = remoteTest { h ->
        val s = session()
        assertThat(client.call("GET", "/api/parent/profiles", parent = s).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("POST", "/api/parent/profile/flags", """{"profileId":"c1","hideShorts":false}""", parent = s).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("POST", "/api/parent/profile/flags", """{"profileId":"c1","showSuggestions":true}""", parent = s).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("POST", "/api/parent/profile/flags", """{"profileId":"c1"}""", parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/parent/schedule", """{"profileId":"c1","fromMinute":420,"untilMinute":0}""", parent = s).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("POST", "/api/parent/schedule", """{"profileId":"c1","fromMinute":null,"untilMinute":null}""", parent = s).status).isEqualTo(HttpStatusCode.OK)
        // One time without the other, a forgotten field, or out of the day: refused before the controller.
        for (body in listOf(
            """{"profileId":"c1","fromMinute":420,"untilMinute":null}""",
            """{"profileId":"c1","fromMinute":420}""",
            """{"profileId":"c1","fromMinute":-1,"untilMinute":60}""",
            """{"profileId":"c1","fromMinute":60,"untilMinute":1440}""",
            """{"profileId":"","fromMinute":60,"untilMinute":120}""",
        )) assertThat(client.call("POST", "/api/parent/schedule", body, parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(h.controller.calls.filter { it.startsWith("set") }).containsExactly(
            "setProfileFlags(c1,false,null,null)", "setProfileFlags(c1,null,null,true)", "setSchedule(c1,420,0)", "setSchedule(c1,null,null)",
        ).inOrder()
    }

    @Test
    fun profiles_thePageCarriesTheTvsChoices_andCreateEditDelete_areChecked() = remoteTest { h ->
        val s = session()
        val page = client.call("GET", "/api/parent/profiles", parent = s).json()
        assertThat(page.getValue("profiles").jsonArray).hasSize(2)
        assertThat(page.getValue("quotaChoices").jsonArray.first().toString()).isEqualTo("null")
        assertThat(page.str("nameMaxLength")).isEqualTo("20")

        client.call("POST", "/api/parent/profile/create", """{"name":"  ليلى  ","avatar":"star"}""", parent = s)
        client.call("POST", "/api/parent/profile/edit", """{"profileId":"c1","name":" سارة "}""", parent = s)
        client.call("POST", "/api/parent/profile/edit", """{"profileId":"c1","avatar":"cat"}""", parent = s)
        client.call("POST", "/api/parent/profile/delete", """{"profileId":"c1"}""", parent = s)
        // Empty or long names, odd avatar keys, an edit that changes nothing: refused before the controller.
        for ((path, body) in listOf(
            "create" to """{"name":"   ","avatar":"star"}""",
            "create" to """{"name":"${"ن".repeat(65)}","avatar":"star"}""",
            "create" to """{"name":"ليلى","avatar":"../x"}""",
            "create" to """{"name":"ليلى"}""",
            "edit" to """{"profileId":"c1"}""",
            "edit" to """{"profileId":"c1","name":""}""",
            "edit" to """{"profileId":"c1","avatar":"Star!"}""",
            "edit" to """{"profileId":"","name":"x"}""",
            "delete" to """{"profileId":""}""",
        )) assertThat(client.call("POST", "/api/parent/profile/$path", body, parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(h.controller.calls.filter { it.endsWith(")") && (it.startsWith("create") || it.startsWith("edit") || it.startsWith("delete")) }).containsExactly(
            "createChild(ليلى,star)", "editProfile(c1,سارة,null)", "editProfile(c1,null,cat)", "deleteProfile(c1)",
        ).inOrder()
    }

    @Test
    fun blocklist_kindsAndLimits() = remoteTest { h ->
        val s = session()
        val list = client.call("GET", "/api/parent/blocklist", parent = s).json()
        assertThat(list.getValue("keywords").jsonArray.single()).isEqualTo(JsonPrimitive("رعب"))
        client.call("POST", "/api/parent/blocklist/add", """{"kind":"keyword","value":"  مقالب "}""", parent = s)
        client.call("POST", "/api/parent/blocklist/add", """{"kind":"channel","value":"https://youtube.com/@x"}""", parent = s)
        client.call("POST", "/api/parent/blocklist/remove", """{"kind":"keyword","value":"رعب"}""", parent = s)
        client.call("POST", "/api/parent/blocklist/remove", """{"kind":"channel","value":"UCbad"}""", parent = s)
        for (body in listOf(
            """{"kind":"keyword","value":"   "}""",
            """{"kind":"keyword","value":"${"ك".repeat(101)}"}""",
            """{"kind":"video","value":"x"}""",
        )) assertThat(client.call("POST", "/api/parent/blocklist/add", body, parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/parent/blocklist/remove", """{"kind":"other","value":"x"}""", parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(h.controller.calls.filter { it.contains("lock") && it != "blocklist" }).containsExactly(
            "blockKeyword(مقالب)", "blockChannel(https://youtube.com/@x)", "unblockKeyword(رعب)", "unblockChannel(UCbad)",
        ).inOrder()
    }

    @Test
    fun stats_areJson() = remoteTest {
        val res = client.call("GET", "/api/parent/stats", parent = session())
        val first = Json.parseToJsonElement(res.bodyAsText()).jsonArray.first().jsonObject
        assertThat(first.getValue("days").jsonArray).hasSize(7)
    }

    @Test
    fun thePage_mayReachOtherPortsOfTheSameHost_only() = remoteTest {
        val res = client.get("/?t=$TOKEN") { header(HttpHeaders.Host, "192.168.1.20:8765") }
        val csp = res.headers["Content-Security-Policy"]!!
        assertThat(csp).contains("connect-src 'self' http://192.168.1.20:*")
        // The page proves the new port is this TV by loading its icon (token needed) from there.
        assertThat(csp).contains("img-src 'self' data: https: http://192.168.1.20:*")
        // A strange Host header gets the strict policy.
        val odd = client.get("/?t=$TOKEN") { header(HttpHeaders.Host, "evil.com\" *") }
        assertThat(odd.headers["Content-Security-Policy"]).contains("connect-src 'self';")
    }
}
