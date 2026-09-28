package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class AuthTest {
    @Test
    fun page_withoutToken_is401Json() = remoteTest { h ->
        val res = client.get("/")
        assertThat(res.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(res.contentType()?.withoutParameters()).isEqualTo(ContentType.Application.Json)
        assertThat(res.json().str("code")).isEqualTo("pairing")
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun page_withoutToken_fromABrowser_is401_andOnlyThePairingHint() = remoteTest { h ->
        val res = client.get("/") { header(HttpHeaders.Accept, BROWSER_ACCEPT) }
        assertThat(res.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(res.contentType()?.withoutParameters()).isEqualTo(ContentType.Text.Html)
        assertThat(res.contentType()?.parameter("charset")?.lowercase()).isEqualTo("utf-8")
        val body = res.bodyAsText()
        assertThat(body).contains("امسح رمز QR")
        assertThat(body).contains("""dir="rtl"""")
        assertThat(body).doesNotContain("الآن على الشاشة")
        assertThat(body).doesNotContain(TOKEN)
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun page_withWrongToken_is401() = remoteTest {
        for (t in listOf("nope", "${TOKEN}x", TOKEN.dropLast(1), TOKEN.uppercase())) {
            assertThat(client.get("/?t=$t").status).isEqualTo(HttpStatusCode.Unauthorized)
            val browser = client.get("/?t=$t") { header(HttpHeaders.Accept, BROWSER_ACCEPT) }
            assertThat(browser.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(browser.bodyAsText()).doesNotContain("الآن على الشاشة")
        }
    }

    @Test
    fun onlyThePageItself_getsTheHtmlHint() = remoteTest {
        val api = client.get("/api/status") { header(HttpHeaders.Accept, BROWSER_ACCEPT) }
        assertThat(api.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(api.contentType()?.withoutParameters()).isEqualTo(ContentType.Application.Json)
    }

    @Test
    fun page_withQueryToken_isUtf8RtlHtml_notCached() = remoteTest {
        val res = client.get("/?t=$TOKEN")
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        assertThat(res.contentType()?.withoutParameters()).isEqualTo(ContentType.Text.Html)
        assertThat(res.contentType()?.parameter("charset")?.lowercase()).isEqualTo("utf-8")
        val body = res.bodyAsBytes().decodeToString()
        assertThat(body).contains("""dir="rtl"""")
        assertThat(body).contains("""lang="ar"""")
        assertThat(body).contains("الآن على الشاشة")
        assertThat(res.headers[HttpHeaders.CacheControl]).contains("no-store")
        assertThat(res.headers["Referrer-Policy"]).isEqualTo("no-referrer")
        assertThat(res.headers["X-Content-Type-Options"]).isEqualTo("nosniff")
    }

    @Test
    fun page_withBearerToken_isServed() = remoteTest {
        assertThat(client.call("GET", "/").status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun page_hasNoExternalResources() = remoteTest {
        val body = client.get("/?t=$TOKEN").bodyAsText()
        assertThat(body).doesNotContainMatch("""(src|href)=["']https?://""")
        assertThat(body).doesNotContain("@import")
        assertThat(body).doesNotContain("@@")
    }

    @Test
    fun page_csp_allowsExactlyItsOwnInlineScript() = remoteTest {
        val res = client.get("/?t=$TOKEN")
        val csp = res.headers["Content-Security-Policy"]!!
        val script = Regex("""<script>([\s\S]*?)</script>""").find(res.bodyAsText())!!.groupValues[1]
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.toByteArray()))
        assertThat(csp).contains("script-src 'sha256-$hash'")
        assertThat(csp).contains("default-src 'none'")
        assertThat(csp).contains("connect-src 'self'")
        assertThat(csp).doesNotContain("unsafe-eval")
    }

    @Test
    fun unpairedPage_hasItsOwnCsp() = remoteTest {
        val res = client.get("/") { header(HttpHeaders.Accept, BROWSER_ACCEPT) }
        val script = Regex("""<script>([\s\S]*?)</script>""").find(res.bodyAsText())!!.groupValues[1]
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.toByteArray()))
        assertThat(res.headers["Content-Security-Policy"]).contains("script-src 'sha256-$hash'")
    }

    @Test
    fun api_withoutToken_is401Json() = remoteTest { h ->
        val res = client.call("GET", "/api/status", token = null)
        assertThat(res.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(res.contentType()?.withoutParameters()).isEqualTo(ContentType.Application.Json)
        val json = res.json()
        assertThat(json.str("code")).isEqualTo("pairing")
        assertThat(json.str("ok")).isEqualTo("false")
        assertThat(json.str("message")).contains("QR")
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun api_withWrongBearer_is401() = remoteTest {
        assertThat(client.call("GET", "/api/status", token = "wrong").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(client.call("GET", "/api/status", token = "").status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun api_withBearer_orQueryToken_isOk() = remoteTest { h ->
        assertThat(client.call("GET", "/api/status").status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.call("GET", "/api/status?t=$TOKEN", token = null).status).isEqualTo(HttpStatusCode.OK)
        val lower = client.get("/api/status") { header(HttpHeaders.Authorization, "bearer $TOKEN") }
        assertThat(lower.status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.calls).containsExactly("status", "status", "status")
    }

    @Test
    fun api_basicAuthScheme_isNotAccepted() = remoteTest {
        val res = client.get("/api/status") { header(HttpHeaders.Authorization, "Basic $TOKEN") }
        assertThat(res.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun everyRoute_withoutToken_is401_andNeverReachesTheController() = remoteTest { h ->
        for ((method, path, body) in PUBLIC_ROUTES + PARENT_ROUTES) {
            val res = client.call(method, path, body, token = null, parent = "whatever")
            assertWithMessage(method, path, res.status, HttpStatusCode.Unauthorized)
            val wrong = client.call(method, path, body, token = "x$TOKEN", parent = "whatever")
            assertWithMessage(method, path, wrong.status, HttpStatusCode.Unauthorized)
        }
        assertThat(h.controller.calls).isEmpty()
    }

    @Test
    fun unknownRoute_withoutToken_is401_withToken_is404Json() = remoteTest {
        assertThat(client.call("GET", "/nope", token = null).status).isEqualTo(HttpStatusCode.Unauthorized)
        val res = client.call("GET", "/api/nope")
        assertThat(res.status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(res.json().str("code")).isEqualTo("not_found")
    }

    @Test
    fun tokenProvider_isReadOnEveryRequest() = remoteTest { h ->
        assertThat(client.call("GET", "/api/status").status).isEqualTo(HttpStatusCode.OK)
        h.token = "rotated-token"
        assertThat(client.call("GET", "/api/status").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(client.call("GET", "/api/status", token = "rotated-token").status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun emptyConfiguredToken_rejectsEverything() = remoteTest(Harness().apply { token = "" }) {
        assertThat(client.call("GET", "/api/status", token = "").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(client.get("/?t=").status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun noCorsHeaders_evenForCrossOriginRequests() = remoteTest {
        val preflight = client.options("/api/status") {
            header(HttpHeaders.Origin, "http://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertThat(preflight.headers[HttpHeaders.AccessControlAllowOrigin]).isNull()
        val get = client.get("/api/status") {
            header(HttpHeaders.Origin, "http://evil.example")
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
        }
        assertThat(get.headers[HttpHeaders.AccessControlAllowOrigin]).isNull()
    }

    @Test
    fun icon_needsToken() = remoteTest {
        assertThat(client.get("/icon.png").status).isEqualTo(HttpStatusCode.Unauthorized)
        val res = client.get("/icon.png?t=$TOKEN")
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        assertThat(res.contentType()).isEqualTo(ContentType.Image.PNG)
        assertThat(res.bodyAsBytes().copyOfRange(1, 4).decodeToString()).isEqualTo("PNG")
    }

    private fun assertWithMessage(method: String, path: String, actual: HttpStatusCode, expected: HttpStatusCode) {
        com.google.common.truth.Truth.assertWithMessage("$method $path").that(actual).isEqualTo(expected)
    }

    private companion object {
        /** What a phone browser sends when it navigates to a page. */
        const val BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    }
}
