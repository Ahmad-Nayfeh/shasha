package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.Test

class BodyLimitTest {
    private val mb = 1024 * 1024

    @Test
    fun jsonBodies_over16KB_are413() = remoteTest { h ->
        val big = """{"q":"${"a".repeat(17 * 1024)}"}"""
        val res = client.call("POST", "/api/search", big)
        assertThat(res.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        assertThat(res.json().str("code")).isEqualTo("too_large")
        assertThat(client.call("POST", "/api/parent/login", """{"pin":"${"1".repeat(20 * 1024)}"}""").status)
            .isEqualTo(HttpStatusCode.PayloadTooLarge)
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun takeout_over20MB_is413() = remoteTest { h ->
        val s = client.login()
        val res = client.post("/api/parent/takeout") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s); header("X-File-Name", "subscriptions.csv")
            contentType(ContentType.Text.CSV); setBody(ByteArray(20 * mb + 1) { 'a'.code.toByte() })
        }
        assertThat(res.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        assertThat(res.json().str("message")).isEqualTo("الملف أكبر من الحد المسموح (20 ميغابايت).")
        assertThat(h.controller.calls.drop(1)).isEmpty()
    }

    @Test
    fun takeout_exactly20MB_isAccepted() = remoteTest { h ->
        val s = client.login()
        val res = client.post("/api/parent/takeout") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s); header("X-File-Name", "subscriptions.csv")
            setBody(ByteArray(20 * mb) { 'a'.code.toByte() })
        }
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.lastBytes!!.size).isEqualTo(20 * mb)
    }

    @Test
    fun backup_over10MB_is413_rawAndMultipart() = remoteTest { h ->
        val s = client.login()
        val raw = client.post("/api/parent/backup") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s); setBody(ByteArray(10 * mb + 1))
        }
        assertThat(raw.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        val multi = client.submitFormWithBinaryData("/api/parent/backup", formData {
            append("file", ByteArray(10 * mb + 1), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"b.json\"") })
        }) { bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s) }
        assertThat(multi.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        assertThat(h.controller.calls.drop(1)).isEmpty()
    }

    @Test
    fun emptyUploads_are400() = remoteTest { h ->
        val s = client.login()
        val res = client.post("/api/parent/backup") { bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s) }
        assertThat(res.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(res.json().str("message")).isEqualTo(ServerMessages.EMPTY_FILE)
        assertThat(h.controller.calls.drop(1)).isEmpty()
    }
}
