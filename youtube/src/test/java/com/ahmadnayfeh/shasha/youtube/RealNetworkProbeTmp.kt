package com.ahmadnayfeh.shasha.youtube

import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeDownloader
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import java.io.File

/** TEMPORARY probe (deleted before the end): what NewPipe's search paging does against the real YouTube. */
class RealNetworkProbeTmp {

    companion object {
        val dir = File("/tmp/claude-0/-home-user-shasha/44f371c9-e5a8-5d0e-9471-fdd26ba4a3b1/scratchpad/shorts").also { it.mkdirs() }
        var counter = 0

        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
            val inner = NewPipeDownloader(OkHttpClient())
            val logging = object : Downloader() {
                override fun execute(request: Request): Response {
                    val r = inner.execute(request)
                    if ("/youtubei/v1/search" in request.url()) {
                        val n = counter++
                        File(dir, "search-%03d.json".format(n)).writeText(r.responseBody())
                        File(dir, "search-%03d.req".format(n)).writeText(request.url() + "\n" + String(request.dataToSend() ?: ByteArray(0)))
                    }
                    return r
                }
            }
            NewPipe.init(logging, Localization("ar", "SA"), ContentCountry("SA"))
        }

        private fun report(test: String, outcome: String) = println("REAL-RESULT | $test | $outcome")
    }

    private fun kinds(items: List<InfoItem>): String {
        val v = items.count { it is StreamInfoItem }
        val c = items.count { it is ChannelInfoItem }
        val p = items.count { it is PlaylistInfoItem }
        val shorts = items.count { it is StreamInfoItem && (it.isShortFormContent || it.url.contains("/shorts/")) }
        return "v=$v c=$c p=$p shorts=$shorts"
    }

    @Test
    fun pagingProbe() {
        val service = ServiceList.YouTube
        for ((query, filter) in listOf(
            "minecraft" to "all", "minecraft" to "videos",
            "قرآن" to "all", "قرآن" to "videos",
            "cats" to "all", "أخبار" to "all", "كرتون" to "all",
        )) {
            val handler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
            val extractor = service.getSearchExtractor(handler)
            extractor.fetchPage()
            var page = extractor.initialPage
            val seen = HashSet<String>()
            var total = 0
            var i = 0
            val log = StringBuilder()
            while (true) {
                val fresh = page.items.count { seen.add(it.url) }
                total += fresh
                log.append("[p$i ${page.items.size} items (${kinds(page.items)}) fresh=$fresh next=${page.nextPage != null}] ")
                if (page.errors.isNotEmpty()) log.append("errors=${page.errors.map { it.javaClass.simpleName + ":" + it.message?.take(60) }} ")
                if (!org.schabi.newpipe.extractor.Page.isValid(page.nextPage) || i >= 40) break
                try {
                    page = SearchInfo.getMoreItems(service, handler, page.nextPage)
                } catch (e: Exception) {
                    log.append("THROWN ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                    break
                }
                i++
            }
            report("paging $query/$filter", "pages=${i + 1} unique=$total :: $log")
        }
    }
}
