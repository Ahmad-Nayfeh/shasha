package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RoutesTest {
    private val csv = "Channel Id,Channel Url,Channel Title\nUC1234567890123456789012,http://www.youtube.com/channel/UC1234567890123456789012,قناة\n"

    @Test
    fun status_returnsTheControllersStatus_asUtf8Json() = remoteTest {
        val res = client.call("GET", "/api/status")
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        assertThat(res.contentType()?.withoutParameters()).isEqualTo(ContentType.Application.Json)
        assertThat(res.contentType()?.parameter("charset")?.lowercase()).isEqualTo("utf-8")
        assertThat(res.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        val json = res.json()
        assertThat(json.str("profileName")).isEqualTo("أحمد")
        assertThat(json.str("profileKind")).isEqualTo("ADULT")
        assertThat(json.str("sessionState")).isEqualTo("OK")
        assertThat(json.str("timeLeftMinutes")).isEqualTo("30")
        val np = json.getValue("nowPlaying").jsonObject
        assertThat(np.str("title")).isEqualTo("فيديو تجريبي")
        assertThat(np.str("positionSeconds")).isEqualTo("42")
        assertThat(np.str("isLive")).isEqualTo("false")
    }

    @Test
    fun search_forwardsTrimmedQuery_andReturnsItems() = remoteTest { h ->
        val res = client.call("POST", "/api/search", """{"q":"  أناشيد أطفال  "}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        val items = kotlinx.serialization.json.Json.parseToJsonElement(res.bodyAsText()).jsonArray
        assertThat(items).hasSize(2)
        assertThat(items[0].jsonObject.str("type")).isEqualTo("video")
        assertThat(items[1].jsonObject.str("type")).isEqualTo("channel")
        assertThat(h.controller.calls).containsExactly("status", "search(أناشيد أطفال)").inOrder()
    }

    @Test
    fun search_isRefused_whenTheProfileCannotSearch() = remoteTest { h ->
        h.controller.status = h.controller.status.copy(canSearch = false, profileKind = RemoteProfileKind.CHILD)
        val res = client.call("POST", "/api/search", """{"q":"x"}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(res.json().str("code")).isEqualTo("rejected")
        assertThat(h.controller.calls).containsExactly("status")
    }

    @Test
    fun search_emptyQuery_is400() = remoteTest { h ->
        val res = client.call("POST", "/api/search", """{"q":"   "}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(res.json().str("message")).isEqualTo(ServerMessages.EMPTY_QUERY)
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun search_tooLongQuery_is400_withItsOwnMessage() = remoteTest { h ->
        assertThat(client.call("POST", "/api/search", """{"q":"${"ب".repeat(200)}"}""").status).isEqualTo(HttpStatusCode.OK)
        val res = client.call("POST", "/api/search", """{"q":"${"ب".repeat(201)}"}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(res.json().str("message")).isEqualTo(ServerMessages.QUERY_TOO_LONG)
        assertThat(h.controller.calls).containsExactly("status", "search(${"ب".repeat(200)})").inOrder()
    }

    @Test
    fun play_forwardsTheLink_andPassesTheResultThrough() = remoteTest { h ->
        h.controller.playResult = RemotePlayResult.refused("هذه القناة ليست في قائمة السماح.")
        val res = client.call("POST", "/api/play", """{"url":" https://youtu.be/abcdefghijk "}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        val json = res.json()
        assertThat(json.str("ok")).isEqualTo("false")
        assertThat(json.str("message")).isEqualTo("هذه القناة ليست في قائمة السماح.")
        assertThat(h.controller.calls).containsExactly("playLink(https://youtu.be/abcdefghijk)")
    }

    @Test
    fun play_emptyLink_is400() = remoteTest { h ->
        val res = client.call("POST", "/api/play", """{"url":""}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(res.json().str("message")).isEqualTo(ServerMessages.EMPTY_LINK)
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun control_forwardsActionAndSeconds() = remoteTest { h ->
        for (body in listOf("""{"action":"PLAY"}""", """{"action":"PAUSE"}""", """{"action":"TOGGLE"}""",
            """{"action":"SEEK_BY","seconds":-10}""", """{"action":"SEEK_BY","seconds":30}""", """{"action":"STOP"}""")) {
            assertThat(client.call("POST", "/api/control", body).status).isEqualTo(HttpStatusCode.OK)
        }
        assertThat(h.controller.calls).containsExactly(
            "control(PLAY,0)", "control(PAUSE,0)", "control(TOGGLE,0)", "control(SEEK_BY,-10)", "control(SEEK_BY,30)",
            "status", "control(STOP,0)", // STOP first checks the playback lock
        ).inOrder()
    }

    @Test
    fun control_stop_isRefused_whileTheVideoIsLocked_otherCommandsAreNot() = remoteTest { h ->
        h.controller.status = h.controller.status.copy(videoLocked = true, profileKind = RemoteProfileKind.CHILD, canSearch = false)
        val stop = client.call("POST", "/api/control", """{"action":"STOP"}""")
        assertThat(stop.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(stop.json().str("code")).isEqualTo("rejected")
        assertThat(stop.json().str("message")).isEqualTo(ServerMessages.VIDEO_LOCKED)
        assertThat(client.call("POST", "/api/control", """{"action":"PAUSE"}""").status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("POST", "/api/control", """{"action":"SEEK_BY","seconds":-10}""").status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.calls).containsExactly("status", "control(PAUSE,0)", "control(SEEK_BY,-10)").inOrder()
    }

    @Test
    fun control_badCommands_are400() = remoteTest { h ->
        assertThat(client.call("POST", "/api/control", """{"action":"EJECT"}""").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/control", """{"action":"SEEK_BY","seconds":0}""").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/control", """{"action":"SEEK_BY","seconds":999999}""").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/control", """not json""").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun profiles_andSwitch_forwardIdAndPin() = remoteTest { h ->
        val list = kotlinx.serialization.json.Json.parseToJsonElement(client.call("GET", "/api/profiles").bodyAsText()).jsonArray
        assertThat(list).hasSize(2)
        assertThat(list[0].jsonObject.str("needsPin")).isEqualTo("true")
        assertThat(list[0].jsonObject.str("isCurrent")).isEqualTo("true")
        assertThat(list[1].jsonObject.str("kind")).isEqualTo("CHILD")
        assertThat(list[1].jsonObject.str("dailyQuotaMinutes")).isEqualTo("60")
        client.call("POST", "/api/profile", """{"id":"a","pin":"1234"}""")
        client.call("POST", "/api/profile", """{"id":"c1"}""")
        client.call("POST", "/api/profile", """{"id":"c1","pin":""}""")
        assertThat(h.controller.calls).containsExactly(
            "profiles", "switchProfile(a,1234)", "switchProfile(c1,null)", "switchProfile(c1,null)",
        ).inOrder()
    }

    @Test
    fun quota_forwardsMinutesOrNull_andValidates() = remoteTest { h ->
        val s = client.login()
        assertThat(client.call("POST", "/api/parent/quota", """{"profileId":"c1","minutes":90}""", parent = s).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("POST", "/api/parent/quota", """{"profileId":"c1","minutes":null}""", parent = s).status).isEqualTo(HttpStatusCode.OK)
        // A missing field must not silently mean "unlimited".
        assertThat(client.call("POST", "/api/parent/quota", """{"profileId":"c1"}""", parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/parent/quota", """{"profileId":"c1","minutes":-5}""", parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/parent/quota", """{"profileId":"c1","minutes":1441}""", parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(h.controller.calls.drop(1)).containsExactly("setQuota(c1,90)", "setQuota(c1,null)").inOrder()
    }

    @Test
    fun unlockTime_andUnlockVideo_forward() = remoteTest { h ->
        val s = client.login()
        client.call("POST", "/api/parent/unlock-time", """{"extraMinutes":30}""", parent = s)
        client.call("POST", "/api/parent/unlock-time", """{"extraMinutes":null}""", parent = s)
        assertThat(client.call("POST", "/api/parent/unlock-time", """{"extraMinutes":0}""", parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.call("POST", "/api/parent/unlock-time", """{}""", parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        client.call("POST", "/api/parent/unlock-video", parent = s)
        assertThat(h.controller.calls.drop(1)).containsExactly("unlockTimeUp(30)", "unlockTimeUp(null)", "unlockVideo").inOrder()
    }

    @Test
    fun allowlist_getAddRemove_forwardProfileAndIds() = remoteTest { h ->
        val s = client.login()
        val res = client.call("GET", "/api/parent/allowlist?profileId=c1", parent = s)
        val json = res.json()
        assertThat(json.getValue("channels").jsonArray[0].jsonObject.str("title")).isEqualTo("قناة الأطفال")
        assertThat(json.getValue("playlists").jsonArray[0].jsonObject.str("id")).isEqualTo("PLx")
        assertThat(client.call("GET", "/api/parent/allowlist", parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        client.call("POST", "/api/parent/allowlist/add", """{"profileId":"c1","link":" https://youtube.com/@kids "}""", parent = s)
        client.call("POST", "/api/parent/allowlist/remove", """{"profileId":"c1","id":"PLx"}""", parent = s)
        assertThat(h.controller.calls.drop(1)).containsExactly(
            "allowlist(c1)", "addToAllowlist(c1,https://youtube.com/@kids)", "removeFromAllowlist(c1,PLx)",
        ).inOrder()
    }

    @Test
    fun subscriptions_listAddRemove_forward() = remoteTest { h ->
        val s = client.login()
        val list = kotlinx.serialization.json.Json.parseToJsonElement(client.call("GET", "/api/parent/subscriptions", parent = s).bodyAsText()).jsonArray
        assertThat(list[0].jsonObject.str("channelId")).isEqualTo("UCabc")
        client.call("POST", "/api/parent/subscriptions/add", """{"link":"https://www.youtube.com/@x"}""", parent = s)
        client.call("POST", "/api/parent/subscriptions/remove", """{"channelId":"UCabc"}""", parent = s)
        assertThat(client.call("POST", "/api/parent/subscriptions/remove", """{"channelId":""}""", parent = s).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(h.controller.calls.drop(1)).containsExactly(
            "subscriptions", "addSubscription(https://www.youtube.com/@x)", "removeSubscription(UCabc)",
        ).inOrder()
    }

    @Test
    fun backup_download_isAJsonAttachment() = remoteTest { h ->
        val s = client.login()
        val res = client.call("GET", "/api/parent/backup", parent = s)
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        assertThat(res.contentType()?.withoutParameters()).isEqualTo(ContentType.Application.Json)
        val disposition = ContentDisposition.parse(res.headers[HttpHeaders.ContentDisposition]!!)
        assertThat(disposition.disposition).isEqualTo("attachment")
        assertThat(disposition.parameter(ContentDisposition.Parameters.FileName)).matches("""shasha-backup-\d{4}-\d{2}-\d{2}\.json""")
        assertThat(res.bodyAsBytes()).isEqualTo(h.controller.backup)
    }

    @Test
    fun backup_upload_rawAndMultipart_forwardTheBytes() = remoteTest { h ->
        val s = client.login()
        val bytes = """{"profiles":[{"name":"سارة"}]}""".toByteArray()
        val raw = client.post("/api/parent/backup") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s)
            contentType(ContentType.Application.Json); setBody(bytes)
        }
        assertThat(raw.status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.lastBytes).isEqualTo(bytes)
        h.controller.lastBytes = null
        val multi = client.submitFormWithBinaryData("/api/parent/backup", formData {
            append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"backup.json\"") })
        }) { bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s) }
        assertThat(multi.status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.lastBytes).isEqualTo(bytes)
    }

    @Test
    fun takeout_rawCsv_isForwardedAsIs() = remoteTest { h ->
        val s = client.login()
        val res = client.post("/api/parent/takeout") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s)
            header("X-File-Name", "subscriptions.csv")
            contentType(ContentType.Text.CSV); setBody(csv.toByteArray())
        }
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.lastFileName).isEqualTo("subscriptions.csv")
        assertThat(h.controller.lastBytes!!.decodeToString()).isEqualTo(csv)
    }

    @Test
    fun takeout_multipartZip_isUnpackedToTheCsv() = remoteTest { h ->
        val s = client.login()
        val zip = zipOf(
            "Takeout/archive_browser.html" to "<html></html>",
            "Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv" to csv,
        )
        val res = client.submitFormWithBinaryData("/api/parent/takeout", formData {
            append("file", zip, Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"takeout-20260928T101010Z-001.zip\"")
                append(HttpHeaders.ContentType, "application/zip")
            })
        }) { bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s) }
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.lastFileName).isEqualTo("subscriptions.csv")
        assertThat(h.controller.lastBytes!!.decodeToString()).isEqualTo(csv)
    }

    @Test
    fun takeout_zipWithoutSubscriptions_is400_withArabicMessage() = remoteTest { h ->
        val s = client.login()
        val res = client.post("/api/parent/takeout") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s); header("X-File-Name", "takeout.zip")
            setBody(zipOf("Takeout/YouTube/history/watch-history.html" to "<html></html>"))
        }
        assertThat(res.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(res.json().str("message")).isEqualTo(ServerMessages.TAKEOUT_NOT_FOUND)
        assertThat(h.controller.calls.drop(1)).isEmpty()
    }

    @Test
    fun takeout_otherFileTypes_are415() = remoteTest { h ->
        val s = client.login()
        val res = client.post("/api/parent/takeout") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s); header("X-File-Name", "%D8%B5%D9%88%D8%B1%D8%A9.pdf")
            setBody("%PDF-1.7".toByteArray())
        }
        assertThat(res.status).isEqualTo(HttpStatusCode.UnsupportedMediaType)
        assertThat(res.json().str("message")).isEqualTo(ServerMessages.TAKEOUT_TYPE)
        assertThat(h.controller.calls.drop(1)).isEmpty()
    }

    @Test
    fun controllerRejection_is403_withItsMessage() = remoteTest { h ->
        h.controller.failure = RemoteRejection("الملف غير موجود.")
        val res = client.call("GET", "/api/profiles")
        assertThat(res.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(res.json().str("message")).isEqualTo("الملف غير موجود.")
        assertThat(h.errors).isEmpty() // a refusal is not an error worth logging
    }

    @Test
    fun controllerCrash_is500_withAGenericArabicMessage_andNoDetails() = remoteTest { h ->
        h.controller.failure = IllegalStateException("secret /data/user/0/com.ahmadnayfeh.shasha token=abc")
        val res = client.call("POST", "/api/play", """{"url":"https://youtu.be/abcdefghijk"}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.InternalServerError)
        assertThat(res.contentType()?.withoutParameters()).isEqualTo(ContentType.Application.Json)
        val body = res.bodyAsText()
        assertThat(body).doesNotContain("secret")
        assertThat(body).doesNotContain("IllegalStateException")
        assertThat(body).doesNotContain("at com.")
        val json = res.json()
        assertThat(json.str("code")).isEqualTo("server_error")
        assertThat(json.str("message")).isEqualTo(ServerMessages.SERVER_ERROR)
        // The details are not lost: the app gets them for its log.
        assertThat(h.errors).containsExactly(h.controller.failure)
    }

    @Test
    fun controllerTimeout_isA500Json_notASilentCancellation() = remoteTest { h ->
        h.controller.failure = kotlinx.coroutines.CancellationException("controller gave up")
        val res = client.call("GET", "/api/status")
        assertThat(res.status).isEqualTo(HttpStatusCode.InternalServerError)
        assertThat(res.json().str("code")).isEqualTo("server_error")
        assertThat(h.errors).hasSize(1)
    }

    @Test
    fun controllerCrash_onEveryRoute_isA500Json() = remoteTest { h ->
        val session = client.login()
        h.controller.failure = RuntimeException("boom")
        for ((method, path, body) in (PUBLIC_ROUTES + PARENT_ROUTES).filter { it.second.startsWith("/api/") && it.second != "/api/parent/logout" }) {
            val res = client.call(method, path, body, parent = session)
            com.google.common.truth.Truth.assertWithMessage("$method $path").that(res.status).isEqualTo(HttpStatusCode.InternalServerError)
            assertThat(res.contentType()?.withoutParameters()).isEqualTo(ContentType.Application.Json)
            assertThat(res.json().str("message")).isEqualTo(ServerMessages.SERVER_ERROR)
        }
    }

    @Test
    fun malformedJson_is400() = remoteTest { h ->
        for (body in listOf("{", "[]", """{"url":5}""", """{"q":null}""")) {
            val path = if (body.contains("url")) "/api/play" else "/api/search"
            val res = client.call("POST", path, body)
            assertThat(res.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(res.json().str("code")).isEqualTo("bad_request")
        }
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun unknownJsonFields_areIgnored() = remoteTest { h ->
        assertThat(client.call("POST", "/api/play", """{"url":"https://youtu.be/abcdefghijk","extra":1}""").status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.calls).hasSize(1)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
