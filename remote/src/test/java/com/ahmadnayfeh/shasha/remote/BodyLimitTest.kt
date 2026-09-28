package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
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
    fun multipart_fileJustOverTheLimit_is413() = remoteTest { h ->
        val s = client.login()
        // The whole body stays within the multipart allowance; only the file itself is too big.
        for (extra in listOf(1, 2, 100)) {
            val multi = client.submitFormWithBinaryData("/api/parent/backup", formData {
                append("file", ByteArray(10 * mb + extra), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"b.json\"") })
            }) { bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s) }
            com.google.common.truth.Truth.assertWithMessage("+$extra bytes: ${multi.bodyAsText()}").that(multi.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
            assertThat(multi.json().str("code")).isEqualTo("too_large")
        }
        assertThat(h.controller.calls.drop(1)).isEmpty()
    }

    @Test
    fun multipart_withoutContentLength_isStillLimited() = remoteTest { h ->
        val s = client.login()
        val res = client.post("/api/parent/takeout") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s)
            setBody(streamed(multipart("file" to ByteArray(20 * mb + 10)), ContentType.MultiPart.FormData.withParameter("boundary", BOUNDARY)))
        }
        assertThat(res.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        assertThat(h.controller.calls.drop(1)).isEmpty()
    }

    @Test
    fun rawBody_withoutContentLength_isStillLimited() = remoteTest { h ->
        val s = client.login()
        val res = client.post("/api/parent/backup") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s)
            setBody(streamed(ByteArray(10 * mb + 1), ContentType.Application.Json))
        }
        assertThat(res.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        assertThat(h.controller.calls.drop(1)).isEmpty()
    }

    @Test
    fun multipart_findsTheFileAmongOtherParts_andIgnoresWhatFollows() = remoteTest { h ->
        val s = client.login()
        val csv = "UC1234567890123456789012,http://www.youtube.com/channel/UC1234567890123456789012,قناة\n".toByteArray()
        val body = multipart("note" to "مرحبا".toByteArray(), "file" to csv, "after" to ByteArray(4096))
        val res = client.post("/api/parent/takeout") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s)
            setBody(streamed(body, ContentType.MultiPart.FormData.withParameter("boundary", BOUNDARY)))
        }
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.lastFileName).isEqualTo("subscriptions.csv")
        assertThat(h.controller.lastBytes).isEqualTo(csv)
    }

    @Test
    fun multipart_withoutAFile_is400() = remoteTest { h ->
        val s = client.login()
        val res = client.post("/api/parent/backup") {
            bearerAuth(TOKEN); header(PARENT_SESSION_HEADER, s)
            setBody(streamed(multipart("note" to "x".toByteArray()), ContentType.MultiPart.FormData.withParameter("boundary", BOUNDARY)))
        }
        assertThat(res.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(res.json().str("message")).isEqualTo(ServerMessages.NO_FILE)
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

    /** multipart/form-data by hand: parts named "file" carry a filename, the others are plain fields. */
    private fun multipart(vararg parts: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((name, content) in parts) {
            val filename = if (name == "file") "; filename=\"subscriptions.csv\"" else ""
            out.write("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$name\"$filename\r\n\r\n".toByteArray())
            out.write(content)
            out.write("\r\n".toByteArray())
        }
        out.write("--$BOUNDARY--\r\n".toByteArray())
        return out.toByteArray()
    }

    /** A body without Content-Length (sent chunked), like a client that streams its upload. */
    private fun streamed(bytes: ByteArray, type: ContentType) = object : OutgoingContent.WriteChannelContent() {
        override val contentType = type
        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully(bytes)
        }
    }

    private companion object {
        const val BOUNDARY = "shasha-test-boundary-7MA4YWxkTrZu0gW"
    }
}
