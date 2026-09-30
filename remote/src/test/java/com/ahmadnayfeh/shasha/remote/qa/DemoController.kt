package com.ahmadnayfeh.shasha.remote.qa

import com.ahmadnayfeh.shasha.remote.ParentLoginResult
import com.ahmadnayfeh.shasha.remote.PlaybackAction
import com.ahmadnayfeh.shasha.remote.PlaybackCommand
import com.ahmadnayfeh.shasha.remote.RemoteAllowlist
import com.ahmadnayfeh.shasha.remote.RemoteChannel
import com.ahmadnayfeh.shasha.remote.RemoteController
import com.ahmadnayfeh.shasha.remote.RemoteItem
import com.ahmadnayfeh.shasha.remote.RemoteItemType
import com.ahmadnayfeh.shasha.remote.RemoteListEntry
import com.ahmadnayfeh.shasha.remote.RemoteNowPlaying
import com.ahmadnayfeh.shasha.remote.RemoteProfile
import com.ahmadnayfeh.shasha.remote.RemoteProfileKind
import com.ahmadnayfeh.shasha.remote.RemoteRejection
import com.ahmadnayfeh.shasha.remote.RemoteResult
import com.ahmadnayfeh.shasha.remote.RemoteSessionState
import com.ahmadnayfeh.shasha.remote.RemoteStatus
import kotlinx.serialization.json.Json

/**
 * A stateful stand-in for the TV, used to drive and screenshot the phone page (see QaServer.kt).
 * Invented content only. Parent PIN and adult PIN: 1234. Every call is reported to [log].
 */
internal class DemoController(private val log: (String) -> Unit) : RemoteController {
    private class Video(
        val id: String, val title: String, val channelId: String, val channel: String,
        val duration: Long?, val thumb: String, val live: Boolean = false,
    )

    private class Profile(
        val id: String, val name: String, val kind: RemoteProfileKind, val avatar: String,
        var quota: Int?, var usedMinutes: Int, var extraMinutes: Int = 0, var unlimitedToday: Boolean = false,
    )

    private val catalog = listOf(
        Video("oc3anD0c5a1", "رحلة إلى أعماق المحيط الهادئ | وثائقي كامل", "UCdocs00000000000000000a", "عالم الوثائقيات", 2710, DemoArt.ocean),
        Video("bl4ckH0le01", "كيف تعمل الثقوب السوداء؟ شرح مبسّط", "UCscience000000000000000b", "علوم بالعربي", 872, DemoArt.space),
        // Most real titles and channel names are English or mixed: the page must keep their word order.
        Video("f4ctsBH10en", "10 Facts About Black Holes!", "UCspaceExplained00000000", "Space Explained (English)", 612, DemoArt.space),
        Video("d3s3rtL1fe1", "أسرار الصحراء العربية: الحياة بين الكثبان", "UCtravel00000000000000000", "رحّالة", 1685, DemoArt.desert),
        Video("k4bs4R3c1p1", "طبخ الكبسة خطوة بخطوة", "UCkitchen0000000000000000", "مطبخ سلمى", 768, DemoArt.food),
        Video("c0d3L3ss0n1", "تعلّم البرمجة من الصفر — الدرس الأول", "UCcode0000000000000000000", "دروس البرمجة بالعربي", 3737, DemoArt.code),
        Video("am4z0nF0r3s", "غابات الأمازون من الأعلى بدقة 4K", "UCdocs00000000000000000a", "عالم الوثائقيات", 1360, DemoArt.forest),
        Video("n1ghtC1ty01", "مدن لا تنام: جولة ليلية في دبي", "UCtravel00000000000000000", "رحّالة", 1089, DemoArt.city),
        Video("l1v3Sp4c3xx", "بث مباشر: الأرض من محطة الفضاء الدولية", "UCscience000000000000000b", "علوم بالعربي", null, DemoArt.space, live = true),
    )
    private val kidsCatalog = listOf(
        Video("k1dsR4bb1t1", "حكاية الأرنب والسلحفاة", "UCkids00000000000000000a", "حكايات قبل النوم", 534, DemoArt.kids),
        Video("k1dsSc13nc", "لماذا السماء زرقاء؟ تجربة ممتعة", "UCkids00000000000000000b", "العلوم للصغار", 402, DemoArt.science),
    )

    private val profiles = listOf(
        Profile("ahmad", "أحمد", RemoteProfileKind.ADULT, "person", quota = null, usedMinutes = 95),
        Profile("sara", "سارة", RemoteProfileKind.CHILD, "star", quota = 60, usedMinutes = 22),
        Profile("yousef", "يوسف", RemoteProfileKind.CHILD, "rocket", quota = 45, usedMinutes = 45),
    )
    private val allowlists = mapOf(
        "sara" to Lists(
            mutableListOf(
                RemoteListEntry("UCkids00000000000000000a", "حكايات قبل النوم"),
                RemoteListEntry("UCkids00000000000000000b", "العلوم للصغار"),
                RemoteListEntry("UCkids00000000000000000c", "رسوم الحروف"),
            ),
            mutableListOf(
                RemoteListEntry("PLkidsLetters0001", "أناشيد الحروف العربية"),
                RemoteListEntry("PLkidsScience0002", "تجارب علمية ممتعة"),
            ),
        ),
        "yousef" to Lists(mutableListOf(RemoteListEntry("UCkids00000000000000000d", "ألغاز وحكايات")), mutableListOf()),
    )

    private class Lists(val channels: MutableList<RemoteListEntry>, val playlists: MutableList<RemoteListEntry>)

    private val subscriptions = mutableListOf(
        RemoteChannel("UCdocs00000000000000000a", "عالم الوثائقيات", DemoArt.avatar("#2EC4B6", "#0B6E99")),
        RemoteChannel("UCscience000000000000000b", "علوم بالعربي", DemoArt.avatar("#8C6DF0", "#3B2A8C")),
        RemoteChannel("UCtravel00000000000000000", "رحّالة", DemoArt.avatar("#FFB06B", "#E0823F")),
        RemoteChannel("UCkitchen0000000000000000", "مطبخ سلمى", null),
        RemoteChannel("UCcode0000000000000000000", "دروس البرمجة بالعربي", DemoArt.avatar("#48DCCD", "#10262A")),
        RemoteChannel("UChistory00000000000000000", "حكايات من التاريخ", null),
        RemoteChannel("UCtech000000000000000000x", "تقنية ببساطة", DemoArt.avatar("#FF8FA3", "#B8325A")),
    )

    private var currentId = "ahmad"
    private var playing: Video? = catalog[0]
    private var positionSeconds = 754.0
    private var isPlaying = true
    private var positionAt = System.nanoTime()
    private var videoUnlocked = false
    private var wrongPins = 0

    private val current get() = profiles.first { it.id == currentId }

    /** One call at a time, like the TV's main thread; each call is logged for the QA script. */
    private inline fun <T> locked(call: String, block: () -> T): T = synchronized(this) {
        log("CALL $call")
        block()
    }

    override suspend fun status(): RemoteStatus = locked("status") {
        val p = current
        val left = timeLeft(p)
        val video = playing
        return@locked RemoteStatus(
            profileName = p.name,
            profileKind = p.kind,
            canSearch = p.kind == RemoteProfileKind.ADULT,
            nowPlaying = video?.let {
                RemoteNowPlaying(it.id, it.title, it.channel, position().toLong(), it.duration, isPlaying, it.live, it.thumb)
            },
            timeLeftMinutes = left,
            sessionState = when {
                left == null -> RemoteSessionState.OK
                left <= 0 -> RemoteSessionState.TIME_UP
                left <= 5 -> RemoteSessionState.WARNING
                else -> RemoteSessionState.OK
            },
            videoLocked = p.kind == RemoteProfileKind.CHILD && video != null && !videoUnlocked,
        )
    }

    private fun timeLeft(p: Profile): Int? {
        val quota = p.quota ?: return null
        if (p.unlimitedToday) return null
        return (quota + p.extraMinutes - p.usedMinutes).coerceAtLeast(0)
    }

    private fun position(): Double {
        val video = playing ?: return 0.0
        if (!isPlaying || video.live) return positionSeconds
        val now = positionSeconds + (System.nanoTime() - positionAt) / 1e9
        return video.duration?.let { now.coerceAtMost(it.toDouble()) } ?: now
    }

    private fun setPosition(seconds: Double) {
        positionSeconds = seconds
        positionAt = System.nanoTime()
    }

    override suspend fun search(query: String): List<RemoteItem> = locked("search($query)") {
        if (current.kind != RemoteProfileKind.ADULT) throw RemoteRejection("البحث غير متاح في ملف الطفل.")
        val videos = catalog.map { RemoteItem(RemoteItemType.VIDEO, it.id, it.title, it.channel, it.thumb, it.duration, it.live) }
        return@locked listOf(
            RemoteItem(RemoteItemType.CHANNEL, "UCdocs00000000000000000a", "عالم الوثائقيات", "1.2 مليون مشترك", DemoArt.avatar("#2EC4B6", "#0B6E99"), null),
        ) + videos.take(4) +
            RemoteItem(RemoteItemType.PLAYLIST, "PLspaceSeries0001", "سلسلة الفضاء الكاملة", "علوم بالعربي · 12 فيديو", DemoArt.space, null) +
            videos.drop(4)
    }

    override suspend fun playLink(url: String): RemoteResult = locked("playLink($url)") {
        val p = current
        if (timeLeft(p) == 0) return@locked RemoteResult.error("انتهى وقت المشاهدة اليوم في ملف «${p.name}». افتح الوقت من قسم الأب.")
        val id = VIDEO_ID.find(url)?.groupValues?.get(1)
        val playlist = PLAYLIST.find(url)?.groupValues?.get(1)
        // A playlist link starts its first video (the demo knows two playlists).
        val video = (catalog + kidsCatalog).firstOrNull { it.id == id } ?: when (playlist) {
            "PLspaceSeries0001" -> catalog[1]
            "PLkidsLetters0001", "PLkidsScience0002" -> kidsCatalog[1]
            else -> null
        }
        if (p.kind == RemoteProfileKind.CHILD) {
            val lists = allowlists[p.id]
            val allowedPlaylist = id == null && lists?.playlists.orEmpty().any { it.id == playlist }
            if (video == null || (video.channelId !in lists?.channels.orEmpty().map { it.id } && !allowedPlaylist)) {
                return@locked RemoteResult.error("هذا الرابط ليس في قائمة السماح لملف «${p.name}»، فلن يظهر على الشاشة.")
            }
        }
        val chosen = video ?: when {
            id != null -> Video(id, "فيديو من يوتيوب", "UCunknown000000000000000", "يوتيوب", 600, DemoArt.city)
            else -> return@locked RemoteResult.error("هذا ليس رابط فيديو من يوتيوب.")
        }
        playing = chosen
        isPlaying = true
        videoUnlocked = false
        setPosition(0.0)
        return@locked RemoteResult.ok("يُعرض الآن على الشاشة: «${chosen.title}»")
    }

    override suspend fun control(cmd: PlaybackCommand): RemoteResult = locked("control(${cmd.action},${cmd.seconds})") {
        val video = playing ?: return@locked RemoteResult.error("لا شيء يُعرض الآن.")
        when (cmd.action) {
            PlaybackAction.PLAY -> { setPosition(position()); isPlaying = true }
            PlaybackAction.PAUSE -> { setPosition(position()); isPlaying = false }
            PlaybackAction.TOGGLE -> { setPosition(position()); isPlaying = !isPlaying }
            PlaybackAction.SEEK_BY -> {
                if (video.live) return@locked RemoteResult.error("لا يمكن التقديم في البث المباشر.")
                setPosition((position() + cmd.seconds).coerceIn(0.0, (video.duration ?: 0).toDouble()))
            }
            PlaybackAction.STOP -> {
                if (current.kind == RemoteProfileKind.CHILD && !videoUnlocked) {
                    return@locked RemoteResult.error("الفيديو مقفول حتى ينتهي. افتحه من قسم الأب.")
                }
                playing = null
                return@locked RemoteResult.ok("أُوقف التشغيل.")
            }
        }
        return@locked RemoteResult.ok("")
    }

    override suspend fun profiles(): List<RemoteProfile> = locked("profiles") {
        val leavingChild = current.kind == RemoteProfileKind.CHILD
        return@locked profiles.map {
            RemoteProfile(
                id = it.id, name = it.name, kind = it.kind,
                needsPin = it.id != currentId && (it.kind == RemoteProfileKind.ADULT || leavingChild),
                avatar = it.avatar, isCurrent = it.id == currentId, dailyQuotaMinutes = it.quota,
            )
        }
    }

    override suspend fun switchProfile(id: String, pin: String?): RemoteResult = locked("switchProfile($id,$pin)") {
        val target = profiles.firstOrNull { it.id == id } ?: return@locked RemoteResult.error("هذا الملف غير موجود.")
        if (target.id == currentId) return@locked RemoteResult.ok("«${target.name}» هو الملف المفتوح الآن.")
        val needsPin = target.kind == RemoteProfileKind.ADULT || current.kind == RemoteProfileKind.CHILD
        if (needsPin && pin != PIN) return@locked RemoteResult.error(if (pin == null) "هذا الملف يحتاج رمز PIN." else "رمز PIN غير صحيح.")
        currentId = target.id
        playing = null // the TV goes back to the new profile's home screen
        videoUnlocked = false
        return@locked RemoteResult.ok("الشاشة الآن على ملف «${target.name}».")
    }

    override suspend fun parentLogin(pin: String): ParentLoginResult = locked("parentLogin($pin)") {
        if (wrongPins >= 5) return@locked ParentLoginResult.LockedOut(retryAfterSeconds = 300)
        if (pin == PIN) {
            wrongPins = 0
            return@locked ParentLoginResult.Success
        }
        wrongPins++
        return@locked if (wrongPins >= 5) ParentLoginResult.LockedOut(300) else ParentLoginResult.WrongPin(5 - wrongPins)
    }

    override suspend fun setQuota(profileId: String, minutes: Int?): RemoteResult = locked("setQuota($profileId,$minutes)") {
        val p = profiles.firstOrNull { it.id == profileId } ?: return@locked RemoteResult.error("هذا الملف غير موجود.")
        p.quota = minutes
        return@locked RemoteResult.ok(
            if (minutes == null) "«${p.name}»: بلا حد يومي." else "حُفظت الحصة اليومية لـ «${p.name}»: ${minutesText(minutes)}.",
        )
    }

    override suspend fun unlockTimeUp(extraMinutes: Int?): RemoteResult = locked("unlockTimeUp($extraMinutes)") {
        val p = current
        if (p.quota == null) return@locked RemoteResult.ok("ملف «${p.name}» بلا حد يومي أصلاً.")
        return@locked if (extraMinutes == null) {
            p.unlimitedToday = true
            RemoteResult.ok("فُتح الوقت لـ «${p.name}» حتى نهاية اليوم.")
        } else {
            val left = timeLeft(p) ?: 0
            if (left == 0) p.extraMinutes += p.usedMinutes - p.quota!! - p.extraMinutes // start from zero, not from the debt
            p.extraMinutes += extraMinutes
            RemoteResult.ok("أُضيفت ${minutesText(extraMinutes)} إلى وقت «${p.name}».")
        }
    }

    override suspend fun unlockVideo(): RemoteResult = locked("unlockVideo") {
        if (playing == null || current.kind != RemoteProfileKind.CHILD || videoUnlocked) {
            return@locked RemoteResult.error("لا يوجد فيديو مقفول الآن.")
        }
        videoUnlocked = true
        return@locked RemoteResult.ok("فُكّ قفل الفيديو. يمكن الخروج منه الآن.")
    }

    override suspend fun allowlist(profileId: String): RemoteAllowlist = locked("allowlist($profileId)") {
        val lists = allowlists[profileId] ?: throw RemoteRejection("قائمة السماح لملفات الأطفال فقط.")
        return@locked RemoteAllowlist(lists.channels.toList(), lists.playlists.toList())
    }

    override suspend fun addToAllowlist(profileId: String, link: String): RemoteResult = locked("addToAllowlist($profileId,$link)") {
        val lists = allowlists[profileId] ?: return@locked RemoteResult.error("قائمة السماح لملفات الأطفال فقط.")
        val name = profiles.first { it.id == profileId }.name
        PLAYLIST.find(link)?.let { m ->
            val entry = RemoteListEntry(m.groupValues[1], "قائمة تشغيل جديدة")
            if (lists.playlists.any { it.id == entry.id }) return@locked RemoteResult.error("هذه القائمة موجودة من قبل.")
            lists.playlists += entry
            return@locked RemoteResult.ok("أُضيفت «${entry.title}» إلى قائمة السماح لـ «$name».")
        }
        val handle = HANDLE.find(link)?.groupValues?.get(1) ?: return@locked RemoteResult.error("هذا ليس رابط قناة أو قائمة تشغيل.")
        val entry = RemoteListEntry("UC" + handle.hashCode().toUInt().toString().padStart(22, '0').take(22), handle)
        if (lists.channels.any { it.id == entry.id }) return@locked RemoteResult.error("هذه القناة موجودة من قبل.")
        lists.channels += entry
        return@locked RemoteResult.ok("أُضيفت «$handle» إلى قائمة السماح لـ «$name».")
    }

    override suspend fun removeFromAllowlist(profileId: String, id: String): RemoteResult = locked("removeFromAllowlist($profileId,$id)") {
        val lists = allowlists[profileId] ?: return@locked RemoteResult.error("قائمة السماح لملفات الأطفال فقط.")
        val entry = (lists.channels + lists.playlists).firstOrNull { it.id == id } ?: return@locked RemoteResult.error("غير موجود في القائمة.")
        lists.channels.remove(entry)
        lists.playlists.remove(entry)
        return@locked RemoteResult.ok("أُزيلت «${entry.title}» من قائمة السماح.")
    }

    override suspend fun importTakeout(fileName: String, bytes: ByteArray): RemoteResult = locked("importTakeout($fileName,${bytes.size})") {
        val rows = bytes.decodeToString().lines().mapNotNull { line ->
            val cells = line.split(',')
            val id = cells.firstOrNull()?.trim()?.takeIf { it.matches(Regex("UC[0-9A-Za-z_-]{22}")) } ?: return@mapNotNull null
            RemoteChannel(id, cells.getOrNull(2)?.trim().orEmpty().ifEmpty { id }, null)
        }
        if (rows.isEmpty()) return@locked RemoteResult.error("لم أجد أي قناة في الملف. تأكد أنه subscriptions.csv من Google Takeout.")
        val added = rows.filter { r -> subscriptions.none { it.channelId == r.channelId } }
        subscriptions += added
        return@locked RemoteResult.ok("استُورد ${countText(added.size)} من Google Takeout.")
    }

    override suspend fun subscriptions(): List<RemoteChannel> = locked("subscriptions") {
        return@locked subscriptions.toList()
    }

    override suspend fun addSubscription(link: String): RemoteResult = locked("addSubscription($link)") {
        val handle = HANDLE.find(link)?.groupValues?.get(1) ?: return@locked RemoteResult.error("هذا ليس رابط قناة من يوتيوب.")
        if (subscriptions.any { it.name == handle }) return@locked RemoteResult.error("أنت مشترك في «$handle» من قبل.")
        subscriptions.add(0, RemoteChannel("UC" + handle.hashCode().toUInt().toString().padStart(22, '0').take(22), handle, null))
        return@locked RemoteResult.ok("اشتركت في «$handle».")
    }

    override suspend fun removeSubscription(channelId: String): RemoteResult = locked("removeSubscription($channelId)") {
        val channel = subscriptions.firstOrNull { it.channelId == channelId } ?: return@locked RemoteResult.error("لست مشتركاً في هذه القناة.")
        subscriptions.remove(channel)
        return@locked RemoteResult.ok("أُلغي الاشتراك في «${channel.name}».")
    }

    override suspend fun exportBackup(): ByteArray = locked("exportBackup") {
        return@locked """{"app":"shasha","version":1,"profiles":${profiles.size},"subscriptions":${subscriptions.size}}""".toByteArray()
    }

    override suspend fun importBackup(bytes: ByteArray): RemoteResult = locked("importBackup(${bytes.size})") {
        val ok = runCatching { Json.parseToJsonElement(bytes.decodeToString()) }.isSuccess
        return@locked if (ok) RemoteResult.ok("استُعيدت النسخة الاحتياطية.") else RemoteResult.error("هذا الملف ليس نسخة احتياطية من «شاشة».")
    }

    private fun minutesText(n: Int) = when {
        n == 1 -> "دقيقة واحدة"
        n == 2 -> "دقيقتان"
        n in 3..10 -> "$n دقائق"
        else -> "$n دقيقة"
    }

    private fun countText(n: Int) = when {
        n == 0 -> "لا شيء جديد"
        n == 1 -> "اشتراك واحد"
        n == 2 -> "اشتراكان"
        n in 3..10 -> "$n اشتراكات"
        else -> "$n اشتراكاً"
    }

    private companion object {
        const val PIN = "1234"
        val VIDEO_ID = Regex("""(?:v=|youtu\.be/|shorts/|live/)([0-9A-Za-z_-]{11})""")
        val PLAYLIST = Regex("""[?&]list=([0-9A-Za-z_-]+)""")
        val HANDLE = Regex("""youtube\.com/(?:@|c/|user/|channel/)([^/?#\s]+)""")
    }
}
