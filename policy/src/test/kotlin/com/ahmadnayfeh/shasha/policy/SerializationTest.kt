package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class SerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun profile_roundTrip() {
        val p = child().withBlockedChannel().withBlockedKeywords("رعب", "Horror")
        val text = json.encodeToString(Profile.serializer(), p)
        assertThat(json.decodeFromString(Profile.serializer(), text)).isEqualTo(p)
        assertThat(text).contains("\"kind\":\"CHILD\"")
    }

    @Test
    fun profile_minimalJson_usesDefaults() {
        val p = json.decodeFromString(
            Profile.serializer(),
            """{"id":"a","name":"أحمد","kind":"ADULT","avatar":"person","futureField":1}""",
        )
        assertThat(p).isEqualTo(Profile.newAdult("أحمد", id = "a"))
    }

    @Test
    fun profile_savedBeforeSuggestionsExisted_getsTheDefaultOfItsKind() {
        // What PolicyJson wrote before «إظهار الفيديوهات المقترحة»: every field but that one.
        fun stored(kind: String, avatar: String) = """{"id":"p","name":"س","kind":"$kind","avatar":"$avatar","dailyQuotaMinutes":60,""" +
            """"schedule":null,"hideShorts":true,"hideComments":true,"allowlist":{"channels":[],"playlists":[]},"blocklist":{"channels":[],"titleKeywords":[]}}"""
        val adult = PolicyJson.json.decodeFromString(Profile.serializer(), stored("ADULT", "person"))
        val child = PolicyJson.json.decodeFromString(Profile.serializer(), stored("CHILD", "star"))
        assertThat(adult.showSuggestions).isTrue()
        assertThat(child.showSuggestions).isFalse()
        // The rest reads as it was written.
        assertThat(child.hideShorts).isTrue()
        assertThat(child.dailyQuotaMinutes).isEqualTo(60)
        // A minimal child, as a hand-written backup could hold it.
        val minimal = json.decodeFromString(Profile.serializer(), """{"id":"c","name":"x","kind":"CHILD","avatar":"star"}""")
        assertThat(minimal.showSuggestions).isFalse()
    }

    @Test
    fun profile_suggestionsChoice_roundTrips_inBothKinds() {
        for (p in listOf(adult().copy(showSuggestions = false), child().copy(showSuggestions = true), adult(), child())) {
            val text = PolicyJson.json.encodeToString(Profile.serializer(), p)
            assertThat(text).contains("\"showSuggestions\":${p.showSuggestions}")
            assertThat(PolicyJson.json.decodeFromString(Profile.serializer(), text)).isEqualTo(p)
        }
    }

    @Test
    fun profile_invalidValues_rejectedOnDecode() {
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(
                Profile.serializer(),
                """{"id":"a","name":"x","kind":"CHILD","avatar":"star","schedule":{"allowedFromMinute":1500,"allowedUntilMinute":0}}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(
                Profile.serializer(),
                """{"id":"a","name":"x","kind":"CHILD","avatar":"star","dailyQuotaMinutes":-3}""",
            )
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(Profile.serializer(), """{"id":"a","name":"x","kind":"TEEN","avatar":"star"}""")
        }
    }

    @Test
    fun overrides_roundTrip() {
        val o = Overrides(
            quota = listOf(QuotaOverride("k", 20_000, extraSeconds = 900), QuotaOverride("k", 20_000, unlimited = true)),
            schedule = listOf(ScheduleOverride("k", 1_790_000_000_000)),
        )
        assertThat(json.decodeFromString(Overrides.serializer(), json.encodeToString(Overrides.serializer(), o))).isEqualTo(o)
    }

    @Test
    fun limiterState_roundTrip() {
        val states = listOf(
            PinAttemptState(),
            PinAttemptState(3, null),
            PinAttemptState(0, 1_790_000_000_000),
            PinAttemptState(0, 1_790_000_000_000, lockouts = 3, lockedUntilElapsedMs = 86_400_000, bootId = 41),
        )
        for (state in states) {
            val text = json.encodeToString(PinAttemptState.serializer(), state)
            assertThat(json.decodeFromString(PinAttemptState.serializer(), text)).isEqualTo(state)
        }
        // What the app saved before lockouts escalated still reads.
        assertThat(json.decodeFromString(PinAttemptState.serializer(), """{"consecutiveFailures":2,"lockedUntilEpochMs":1790000000000}"""))
            .isEqualTo(PinAttemptState(2, 1_790_000_000_000))
    }

    @Test
    fun usageRecordsAndSurrenders_roundTrip() {
        val record = UsageRecord("k", 20_000, UsageKind.PLAYBACK, 120, "UC1", "قناة")
        val bare = UsageRecord("k", 20_000, UsageKind.FOREGROUND, 60)
        val event = SurrenderEvent("adult", 1_790_000_000_000, "abc")
        assertThat(json.decodeFromString(UsageRecord.serializer(), json.encodeToString(UsageRecord.serializer(), record))).isEqualTo(record)
        assertThat(json.decodeFromString(UsageRecord.serializer(), json.encodeToString(UsageRecord.serializer(), bare))).isEqualTo(bare)
        assertThat(json.decodeFromString(SurrenderEvent.serializer(), json.encodeToString(SurrenderEvent.serializer(), event))).isEqualTo(event)
    }

    // ---- PolicyJson ----------------------------------------------------------------------------

    @Test
    fun policyJson_writesEveryField_soChangingADefaultLaterCannotChangeSavedData() {
        val text = PolicyJson.json.encodeToString(Profile.serializer(), Profile.newAdult("أحمد", id = "a"))
        for (field in listOf("dailyQuotaMinutes", "schedule", "hideShorts", "hideComments", "allowlist", "blocklist", "showSuggestions")) {
            assertThat(text).contains("\"$field\"")
        }
        assertThat(PolicyJson.json.decodeFromString(Profile.serializer(), text)).isEqualTo(Profile.newAdult("أحمد", id = "a"))
        val quota = PolicyJson.json.encodeToString(QuotaOverride.serializer(), QuotaOverride("k", 1))
        assertThat(quota).contains("\"unlimited\":false")
    }

    @Test
    fun policyJson_decodeEach_skipsOnlyTheBadItems() {
        val good = PolicyJson.json.encodeToString(Profile.serializer(), child())
        val text = """[$good, {"id":"x","name":"x","kind":"CHILD","avatar":"star","dailyQuotaMinutes":1441},
            {"id":"y","name":"y","kind":"TEEN","avatar":"star"}, 5, {"id":"z","name":"ز","kind":"ADULT","avatar":"person","new":1}]"""
        val decoded = PolicyJson.decodeEach(Profile.serializer(), text)
        assertThat(decoded.items).containsExactly(child(), Profile.newAdult("ز", id = "z")).inOrder()
        assertThat(decoded.skipped).isEqualTo(3)
        assertThrows(SerializationException::class.java) { PolicyJson.decodeEach(Profile.serializer(), """{"id":"a"}""") }
        assertThrows(SerializationException::class.java) { PolicyJson.decodeEach(Profile.serializer(), "not json") }
    }

    @Test
    fun policyJson_decodeOrNull() {
        val good = PolicyJson.json.encodeToString(Profile.serializer(), adult())
        assertThat(PolicyJson.decodeOrNull(Profile.serializer(), good)).isEqualTo(adult())
        assertThat(PolicyJson.decodeOrNull(Profile.serializer(), """{"id":"a","name":"x","kind":"CHILD","avatar":"s","schedule":{"allowedFromMinute":1500,"allowedUntilMinute":0}}""")).isNull()
        assertThat(PolicyJson.decodeOrNull(Overrides.serializer(), """{"quota":[{"profileId":"k","epochDay":1,"extraSeconds":-1}]}""")).isNull()
        assertThat(PolicyJson.decodeOrNull(Profile.serializer(), "")).isNull()
    }
}
