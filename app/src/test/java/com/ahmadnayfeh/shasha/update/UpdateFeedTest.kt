package com.ahmadnayfeh.shasha.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * SPEC §6 against a local server playing the download site: the manifest and the APK are public files, so no request
 * carries credentials, and only a file with the manifest's SHA-256 is ever kept.
 */
class UpdateFeedTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val site = MockWebServer()

    @After
    fun tearDown() = site.shutdown()

    private fun feed(client: OkHttpClient = OkHttpClient()) = UpdateFeed(client, site.url("/shasha/latest.json"))

    private fun apk(sha256: String = APK_SHA, size: Long = APK.size.toLong()) =
        ReleaseApk(site.url("/shasha/shasha-tv.apk?v=1100").toString(), size, sha256)

    private fun target() = File(tmp.root, "updates/shasha-1.8.0-1100.apk")

    private fun error(block: suspend () -> Unit): UpdateException = runBlocking {
        try {
            block()
            throw AssertionError("expected an UpdateException")
        } catch (e: UpdateException) {
            e
        }
    }

    private fun RecordedRequest.headerNames() = headers.names().map { it.lowercase() }

    @Test
    fun theManifestIsAskedAfresh_withoutCredentials() = runBlocking<Unit> {
        site.enqueue(MockResponse().setBody(manifest()))
        val release = feed().latest()
        assertThat(release.versionCode).isEqualTo(1100)
        assertThat(release.versionName).isEqualTo("1.8.0")
        assertThat(release.apk).isEqualTo(ReleaseApk(site.url("/shasha/shasha-tv.apk?v=1100").toString(), APK.size.toLong(), APK_SHA))
        val request = site.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/shasha/latest.json")
        assertThat(request.getHeader("Cache-Control")).isEqualTo("no-cache")
        assertThat(request.headerNames()).doesNotContain("authorization")
    }

    @Test
    fun theApkComesDown_withoutCredentials_andIsKeptWhenItsHashMatches() = runBlocking<Unit> {
        site.enqueue(MockResponse().setBody(Buffer().write(APK)))
        val progress = mutableListOf<Long>()

        feed().download(apk(), target()) { done, total ->
            progress += done
            assertThat(total).isEqualTo(APK.size.toLong())
        }

        val request = site.takeRequest()
        assertThat(request.path).isEqualTo("/shasha/shasha-tv.apk?v=1100")
        assertThat(request.headerNames()).doesNotContain("authorization")
        assertThat(target().readBytes()).isEqualTo(APK)
        assertThat(UpdateFeed.sha256(target())).isEqualTo(APK_SHA)
        assertThat(File(target().path + ".part").exists()).isFalse()
        assertThat(progress.first()).isEqualTo(0)
        assertThat(progress.last()).isEqualTo(APK.size.toLong())
    }

    @Test
    fun aRedirectOnTheSameSite_isFollowed_stillWithoutCredentials() = runBlocking<Unit> {
        site.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/shasha/v1100/shasha-tv.apk"))
        site.enqueue(MockResponse().setBody(Buffer().write(APK)))
        feed().download(apk(), target()) { _, _ -> }
        site.takeRequest()
        val second = site.takeRequest()
        assertThat(second.path).isEqualTo("/shasha/v1100/shasha-tv.apk")
        assertThat(second.headerNames()).doesNotContain("authorization")
        assertThat(target().readBytes()).isEqualTo(APK)
    }

    @Test
    fun aFileThatIsNotTheManifestsOne_isNeverKept() {
        // The same size, other bytes (a stale cache, or tampering on the way).
        val other = APK.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        site.enqueue(MockResponse().setBody(Buffer().write(other)))
        val e = error { feed().download(apk(), target()) { _, _ -> } }
        assertThat(e.kind).isEqualTo(UpdateErrorKind.DOWNLOAD_FAILED)
        assertThat(e.detail).isEqualTo("sha256 mismatch")
        assertThat(target().exists()).isFalse()
        assertThat(File(target().path + ".part").exists()).isFalse()
    }

    @Test
    fun theManifestsHttpAnswersAreNamed() {
        for ((code, kind) in listOf(
            403 to UpdateErrorKind.FORBIDDEN,
            429 to UpdateErrorKind.FORBIDDEN,
            404 to UpdateErrorKind.NOT_FOUND,
            401 to UpdateErrorKind.BAD_ANSWER,
            500 to UpdateErrorKind.BAD_ANSWER,
            502 to UpdateErrorKind.BAD_ANSWER,
        )) {
            site.enqueue(MockResponse().setResponseCode(code).setBody("<html>no</html>"))
            val e = error { feed().latest() }
            assertThat(e.kind).isEqualTo(kind)
            assertThat(e.detail).isEqualTo("HTTP $code")
        }
    }

    @Test
    fun aManifestThatIsNotJson_orHasNoTvEntry_isNamed() {
        site.enqueue(MockResponse().setBody("<html>Site not found</html>"))
        assertThat(error { feed().latest() }.kind).isEqualTo(UpdateErrorKind.BAD_ANSWER)
        site.enqueue(MockResponse().setBody("""{"mobile": {}}"""))
        assertThat(error { feed().latest() }.kind).isEqualTo(UpdateErrorKind.NO_APK)
    }

    @Test
    fun aBrokenDownloadIsNeverLeftAsTheApk() {
        site.enqueue(MockResponse().setResponseCode(404))
        val missing = error { feed().download(apk(), target()) { _, _ -> } }
        assertThat(missing.kind).isEqualTo(UpdateErrorKind.DOWNLOAD_FAILED)
        assertThat(missing.detail).isEqualTo("HTTP 404")

        site.enqueue(MockResponse().setBody(Buffer().write(APK.copyOf(1000))))
        assertThat(error { feed().download(apk(), target()) { _, _ -> } }.kind).isEqualTo(UpdateErrorKind.DOWNLOAD_FAILED)

        site.enqueue(MockResponse().setBody(Buffer().write(APK)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertThat(error { feed().download(apk(), target()) { _, _ -> } }.kind).isAnyOf(UpdateErrorKind.DOWNLOAD_FAILED, UpdateErrorKind.NETWORK)

        assertThat(target().exists()).isFalse()
        assertThat(File(target().path + ".part").exists()).isFalse()
    }

    @Test
    fun noServer_isTheNetwork() {
        val gone = site.url("/shasha/latest.json")
        site.shutdown()
        val offline = UpdateFeed(OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(), gone)
        assertThat(error { offline.latest() }.kind).isEqualTo(UpdateErrorKind.NETWORK)
    }

    private fun manifest() = """
        {"tv": {"versionCode": 1100, "versionName": "1.8.0", "url": "shasha-tv.apk?v=1100", "sha256": "$APK_SHA",
                "size": ${APK.size}, "notes": "شاشة 1.8.0"},
         "mobile": {"versionCode": 1100, "versionName": "1.8.0", "url": "shasha-mobile.apk?v=1100",
                    "sha256": "${"0".repeat(64)}", "size": 1}}
    """.trimIndent()

    private companion object {
        val APK = ByteArray(700_000) { (it * 31).toByte() }
        val APK_SHA: String = MessageDigest.getInstance("SHA-256").digest(APK).joinToString("") { "%02x".format(it) }
    }
}
