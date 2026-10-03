package com.ahmadnayfeh.shasha.mobile.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The updater against two local servers: "api" plays the releases API, "storage" the storage host the asset API
 * redirects to. The read token goes to the API, and never to the storage host.
 */
class GitHubReleasesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val api = MockWebServer()
    private val storage = MockWebServer()

    @After
    fun tearDown() {
        api.shutdown()
        storage.shutdown()
    }

    private fun releases(token: String = TOKEN) = GitHubReleases(OkHttpClient(), REPO, token, apiBase = api.url("/"))

    private fun asset(size: Long = APK.size.toLong(), url: String = api.url("/repos/$REPO/releases/assets/7").toString()) =
        ReleaseAsset(id = 7, name = "shasha-mobile-1.2.0-1042.apk", size = size, apiUrl = url)

    private fun target() = File(tmp.root, "updates/shasha-mobile-1.2.0-1042.apk")

    private fun error(block: suspend () -> Unit): UpdateException = runBlocking {
        try {
            block()
            throw AssertionError("expected an UpdateException")
        } catch (e: UpdateException) {
            e
        }
    }

    @Test
    fun theLatestReleaseIsAskedWithTheTokenAndGitHubsHeaders() = runBlocking<Unit> {
        api.enqueue(MockResponse().setBody(latestJson()))
        val release = releases().latest()!!
        assertThat(release.versionCode).isEqualTo(1042)
        val request = api.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/repos/$REPO/releases/latest")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer $TOKEN")
        assertThat(request.getHeader("Accept")).isEqualTo("application/vnd.github+json")
        assertThat(request.getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28")
    }

    @Test
    fun theDownloadFollowsTheRedirect_withoutTheAuthorizationHeader() = runBlocking<Unit> {
        storage.enqueue(MockResponse().setBody(Buffer().write(APK)))
        api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/release-assets/7?X-Amz-Signature=abc").toString()))
        val progress = mutableListOf<Long>()

        releases().download(asset(), target()) { done, total ->
            progress += done
            assertThat(total).isEqualTo(APK.size.toLong())
        }

        val first = api.takeRequest()
        assertThat(first.path).isEqualTo("/repos/$REPO/releases/assets/7")
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer $TOKEN")
        assertThat(first.getHeader("Accept")).isEqualTo("application/octet-stream")
        val second = storage.takeRequest()
        assertThat(second.path).isEqualTo("/release-assets/7?X-Amz-Signature=abc")
        assertThat(second.getHeader("Authorization")).isNull()
        assertThat(second.headers.names().map { it.lowercase() }).doesNotContain("authorization")
        assertThat(api.requestCount).isEqualTo(1)

        assertThat(target().readBytes()).isEqualTo(APK)
        assertThat(File(target().path + ".part").exists()).isFalse()
        assertThat(progress.first()).isEqualTo(0)
        assertThat(progress.last()).isEqualTo(APK.size.toLong())
    }

    @Test
    fun anAssetOutsideTheApiHost_neverGetsTheToken() {
        val e = error { releases().download(asset(url = storage.url("/repos/$REPO/releases/assets/7").toString()), target()) { _, _ -> } }
        assertThat(e.kind).isEqualTo(UpdateErrorKind.BAD_ANSWER)
        assertThat(storage.requestCount).isEqualTo(0)
        assertThat(api.requestCount).isEqualTo(0)
    }

    @Test
    fun githubsAnswersAreNamed() {
        for ((code, kind) in listOf(401 to UpdateErrorKind.TOKEN_REJECTED, 403 to UpdateErrorKind.FORBIDDEN, 429 to UpdateErrorKind.FORBIDDEN, 404 to UpdateErrorKind.NOT_FOUND, 502 to UpdateErrorKind.BAD_ANSWER)) {
            api.enqueue(MockResponse().setResponseCode(code).setBody("""{"message": "no"}"""))
            val e = error { releases().latest() }
            assertThat(e.kind).isEqualTo(kind)
            assertThat(e.detail).isEqualTo("HTTP $code")
        }
    }

    @Test
    fun aBrokenDownloadIsNeverLeftAsTheApk() {
        // The storage host refuses (an expired signature), then a transfer that stops half-way.
        storage.enqueue(MockResponse().setResponseCode(403))
        api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/release-assets/7").toString()))
        assertThat(error { releases().download(asset(), target()) { _, _ -> } }.kind).isEqualTo(UpdateErrorKind.DOWNLOAD_FAILED)

        storage.enqueue(MockResponse().setBody(Buffer().write(APK.copyOf(1000))))
        api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/release-assets/7").toString()))
        assertThat(error { releases().download(asset(), target()) { _, _ -> } }.kind).isEqualTo(UpdateErrorKind.DOWNLOAD_FAILED)

        storage.enqueue(MockResponse().setBody(Buffer().write(APK)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/release-assets/7").toString()))
        assertThat(error { releases().download(asset(), target()) { _, _ -> } }.kind).isAnyOf(UpdateErrorKind.DOWNLOAD_FAILED, UpdateErrorKind.NETWORK)

        assertThat(target().exists()).isFalse()
        assertThat(File(target().path + ".part").exists()).isFalse()
    }

    @Test
    fun noServer_isTheNetwork() {
        val gone = api.url("/")
        api.shutdown()
        val offline = GitHubReleases(OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(), REPO, TOKEN, apiBase = gone)
        assertThat(error { offline.latest() }.kind).isEqualTo(UpdateErrorKind.NETWORK)
    }

    @Test
    fun withoutAToken_thereIsNothingToAsk() {
        assertThat(releases(token = "").hasToken).isFalse()
        assertThat(releases(token = "  ").hasToken).isFalse()
        assertThat(releases().hasToken).isTrue()
    }

    private fun latestJson() = """
        {"tag_name": "v1.0.0-1042", "draft": false, "prerelease": false, "body": "## شاشة 1.0.0 (1042)",
         "assets": [{"id": 7, "name": "shasha-mobile-1.2.0-1042.apk", "size": ${APK.size}, "state": "uploaded",
                     "url": "${api.url("/repos/$REPO/releases/assets/7")}"}]}
    """.trimIndent()

    private companion object {
        const val REPO = "Ahmad-Nayfeh/shasha"
        const val TOKEN = "github_pat_test_read_only"
        val APK = ByteArray(700_000) { (it * 31).toByte() }
    }
}
