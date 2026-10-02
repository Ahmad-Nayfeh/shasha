package com.ahmadnayfeh.shasha.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/** Records every call as a readable string and returns canned answers. */
class FakeController : RemoteController {
    val calls = CopyOnWriteArrayList<String>()
    var failure: Throwable? = null

    var status = RemoteStatus(
        profileName = "أحمد",
        profileKind = RemoteProfileKind.ADULT,
        canSearch = true,
        nowPlaying = RemoteNowPlaying(
            videoId = "dQw4w9WgXcQ", title = "فيديو تجريبي", channelName = "قناة", positionSeconds = 42,
            durationSeconds = 213, isPlaying = true, isLive = false, thumbnailUrl = "https://i.ytimg.com/vi/x/hq.jpg",
        ),
        timeLeftMinutes = 30,
        sessionState = RemoteSessionState.OK,
        videoLocked = false,
    )
    var searchResults = listOf(
        RemoteItem(RemoteItemType.VIDEO, "abcdefghijk", "نتيجة", "قناة", null, 61),
        RemoteItem(RemoteItemType.CHANNEL, "UC1234567890123456789012", "قناة", null, null, null),
    )
    var result = RemoteResult.ok("تم")
    var playResult = RemotePlayResult(
        true, "بانتظار القبول على الشاشة",
        RemotePlayRequest("req1", "فيديو تجريبي", RemotePlayState.PENDING, "بانتظار القبول على الشاشة", secondsLeft = 30),
    )
    var loginResult: ParentLoginResult = ParentLoginResult.Success
    var backup = """{"app":"شاشة","version":1}""".toByteArray()
    var lastBytes: ByteArray? = null
    var lastFileName: String? = null

    private fun <T> record(call: String, value: T): T {
        calls += call
        failure?.let { throw it }
        return value
    }

    override suspend fun status() = record("status", status)
    override suspend fun search(query: String) = record("search($query)", searchResults)
    override suspend fun playLink(url: String) = record("playLink($url)", playResult)
    override suspend fun control(cmd: PlaybackCommand) = record("control(${cmd.action},${cmd.seconds})", result)
    override suspend fun profiles() = record(
        "profiles",
        listOf(
            RemoteProfile("a", "أحمد", RemoteProfileKind.ADULT, needsPin = true, avatar = "person", isCurrent = true),
            RemoteProfile("c1", "سارة", RemoteProfileKind.CHILD, needsPin = false, avatar = "star", dailyQuotaMinutes = 60),
        ),
    )
    override suspend fun switchProfile(id: String, pin: String?) = record("switchProfile($id,$pin)", result)
    override suspend fun parentLogin(pin: String) = record("parentLogin($pin)", loginResult)
    override suspend fun setQuota(profileId: String, minutes: Int?) = record("setQuota($profileId,$minutes)", result)
    override suspend fun unlockTimeUp(extraMinutes: Int?) = record("unlockTimeUp($extraMinutes)", result)
    override suspend fun unlockVideo() = record("unlockVideo", result)
    override suspend fun answerConfirmation(id: String, approve: Boolean) = record("answerConfirmation($id,$approve)", result)
    override suspend fun allowlist(profileId: String) = record(
        "allowlist($profileId)",
        RemoteAllowlist(listOf(RemoteListEntry("UCkids", "قناة الأطفال")), listOf(RemoteListEntry("PLx", "أناشيد"))),
    )
    override suspend fun addToAllowlist(profileId: String, link: String) = record("addToAllowlist($profileId,$link)", result)
    override suspend fun removeFromAllowlist(profileId: String, id: String) = record("removeFromAllowlist($profileId,$id)", result)
    override suspend fun importTakeout(fileName: String, bytes: ByteArray): RemoteResult {
        lastFileName = fileName
        lastBytes = bytes
        return record("importTakeout($fileName,${bytes.size})", result)
    }
    override suspend fun subscriptions() = record("subscriptions", listOf(RemoteChannel("UCabc", "قناة", null)))
    override suspend fun addSubscription(link: String) = record("addSubscription($link)", result)
    override suspend fun removeSubscription(channelId: String) = record("removeSubscription($channelId)", result)
    override suspend fun exportBackup() = record("exportBackup", backup)
    override suspend fun importBackup(bytes: ByteArray): RemoteResult {
        lastBytes = bytes
        return record("importBackup(${bytes.size})", result)
    }

    var settingsPage = RemoteSettingsPage(
        listOf(
            RemoteSettingGroup(
                "serious", "الوضع الجاد",
                listOf(RemoteSetting("serious_mode_children", "الوضع الجاد لملفات الأطفال", RemoteSettingKind.SWITCH, JsonPrimitive(true), JsonPrimitive(true))),
            ),
        ),
    )
    var settingsResult: RemoteSettingsResult? = null

    override suspend fun settings() = record("settings", settingsPage)
    override suspend fun changeSetting(key: String, value: JsonElement, base: JsonElement?) =
        record(
            "changeSetting($key,$value" + (base?.let { ",base=$it" } ?: "") + ")",
            settingsResult ?: RemoteSettingsResult(true, "حُفظ", settingsPage),
        )
    override suspend fun resetSettings(group: String?) =
        record("resetSettings($group)", settingsResult ?: RemoteSettingsResult(true, "أُرجع", settingsPage))
    override suspend fun profilesPage() = record(
        "profilesPage",
        RemoteProfilesPage(
            listOf(
                RemoteProfileDetails("a", "أحمد", RemoteProfileKind.ADULT, "person", true, null, null, null, hideShorts = false, hideComments = false,
                    deleteBlockedReason = "على الشاشة"),
                RemoteProfileDetails("c1", "سارة", RemoteProfileKind.CHILD, "star", false, 60, 420, 1320, hideShorts = true, hideComments = true),
            ),
            quotaChoices = listOf(null, 15, 30, 60),
            avatars = listOf("person", "star"),
            childAvatars = listOf("star"),
            nameMaxLength = 20,
        ),
    )
    override suspend fun createChild(name: String, avatar: String) = record("createChild($name,$avatar)", result)
    override suspend fun editProfile(profileId: String, name: String?, avatar: String?) = record("editProfile($profileId,$name,$avatar)", result)
    override suspend fun deleteProfile(profileId: String) = record("deleteProfile($profileId)", result)
    override suspend fun setProfileFlags(profileId: String, hideShorts: Boolean?, hideComments: Boolean?, showSuggestions: Boolean?) =
        record("setProfileFlags($profileId,$hideShorts,$hideComments,$showSuggestions)", result)
    override suspend fun setSchedule(profileId: String, fromMinute: Int?, untilMinute: Int?) =
        record("setSchedule($profileId,$fromMinute,$untilMinute)", result)
    override suspend fun blocklist() = record("blocklist", RemoteBlocklist(listOf(RemoteListEntry("UCbad", "قناة")), listOf("رعب")))
    override suspend fun blockKeyword(keyword: String) = record("blockKeyword($keyword)", result)
    override suspend fun unblockKeyword(keyword: String) = record("unblockKeyword($keyword)", result)
    override suspend fun blockChannel(link: String) = record("blockChannel($link)", result)
    override suspend fun unblockChannel(channelId: String) = record("unblockChannel($channelId)", result)
    override suspend fun stats() = record(
        "stats",
        listOf(RemoteStats("c1", "سارة", RemoteProfileKind.CHILD, listOf(10, 20, 0, 30, 40, 5, 12), 117, 90, 30, listOf(RemoteChannelUsage("قناة", 50)), 0)),
    )
}
