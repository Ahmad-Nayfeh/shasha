package com.ahmadnayfeh.shasha.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.PromoEnglish
import com.ahmadnayfeh.shasha.PromoEnglish.en
import com.ahmadnayfeh.shasha.PromoEnglish.pick
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.TV_1080P_EN
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.search.VoiceError
import com.ahmadnayfeh.shasha.search.VoiceState
import com.ahmadnayfeh.shasha.ui.UiFixtures.NOW
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.main.MainShell
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.search.SearchAssist
import com.ahmadnayfeh.shasha.ui.screens.search.SearchCallbacks
import com.ahmadnayfeh.shasha.ui.screens.search.SearchScreen
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** v1.1 search: the microphone, suggestions while typing, recent searches, the microphone's other readings and its failures. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class SearchVoiceScreenshotTest {
    @get:Rule
    val compose = PromoEnglish.composeRule()

    /**
     * [focus]: the tag of the element to show focused. Moving there with D-pad keys does not work here: the search box
     * keeps arrow keys from anything that is not a D-pad device (on the TV the remote's LEFT/DOWN leave it).
     */
    @OptIn(ExperimentalCoilApi::class)
    private fun snap(name: String, focus: String? = null, language: AppLanguage = AppLanguage.ARABIC, content: @Composable () -> Unit) {
        PromoEnglish.assumeRecorded(name)
        val pictures = AsyncImagePreviewHandler { request -> UiFixtures.fakeThumbnail(request.data.toString()).asImage() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) {
                ShashaTheme(language = PromoEnglish.language(language)) { MainShell(Tab.SEARCH, {}, remember { Tab.entries.associateWith { androidx.compose.ui.focus.FocusRequester() } }) { content() } }
            }
        }
        compose.waitForIdle()
        focus?.let {
            compose.onNodeWithTag(it).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
        }
        PromoEnglish.capture(compose, name)
    }

    @Composable
    private fun Search(query: String, text: String, assist: SearchAssist, results: PagedState<ItemUi> = PagedState(loadingFirst = false)) = SearchScreen(
        query = query,
        filter = SearchFilter.ALL,
        results = results,
        subscribed = remember { mutableStateOf(UiFixtures.subscribedIds) },
        watched = remember { mutableStateOf(emptyMap()) },
        nowMs = NOW,
        focus = remember { ContentFocus() },
        extractorVersion = "v0.26.5",
        callbacks = SearchCallbacks({}, {}, {}, {}, {}, {}, {}, {}),
        text = text,
        // The English video speaks English to the microphone: «EN» beside it, not «ع».
        assist = assist.copy(voiceLanguage = pick(assist.voiceLanguage, "en-US")),
    )

    // In English the chips are wider: shorter searches, so that the four of them and «Clear» fit the line.
    private val recent = pick(listOf("وثائقي المحيط", "تعلم البرمجة", "Minecraft", "كبسة"), listOf("ocean life", "coding", "Minecraft", "kapsa"))

    @Test
    fun idle() = snap("search_idle") {
        Search("", "", SearchAssist(recent = recent))
    }

    /** English interface (left to right): the microphone's language button reads «AR» / «EN», not «ع». */
    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun idleEnglish() = snap("search_idle_en", language = AppLanguage.ENGLISH) {
        Search("", "", SearchAssist(recent = listOf("ocean documentary", "learn programming", "Minecraft", "kapsa")))
    }

    @Test
    fun idleMicFocused() = snap("search_idle_mic", focus = "search_mic") {
        Search("", "", SearchAssist(recent = recent))
    }

    @Test
    fun listening() = snap("search_listening") {
        Search("", "", SearchAssist(voice = VoiceState.Listening(level = 0.6f, partial = en("وثائقي عن أعماق"), hearing = true)))
    }

    @Test
    fun listeningEnglish() = snap("search_listening_en") {
        Search("", "", SearchAssist(voice = VoiceState.Listening(), voiceLanguage = "en-US"))
    }

    @Test
    fun suggestions() = snap("search_suggestions", focus = "assist_0") {
        Search(
            "", "وثائقي",
            SearchAssist(suggestions = listOf("وثائقي الحيوانات", "وثائقي عن الفضاء", "وثائقي المحيط", "وثائقي تاريخ", "وثائقيات ناشيونال جيوغرافيك", "وثائقي 4k", "وثائقي البحر", "وثائقي الصحراء")),
        )
    }

    @Test
    fun alternatives() = snap("search_voice_alternatives") {
        Search(
            "وثائقي المحيط", "وثائقي المحيط",
            SearchAssist(alternatives = listOf("وثائقي المحيطات", "وثائق المحيط")),
            PagedState(items = UiFixtures.searchResults, loadingFirst = false),
        )
    }

    @Test
    fun errorNetwork() = snap("search_voice_error") {
        Search("", "", SearchAssist(voice = VoiceState.Failed(VoiceError.NETWORK)))
    }

    @Test
    fun errorPermission() = snap("search_voice_permission") {
        Search("", "", SearchAssist(voice = VoiceState.Failed(VoiceError.PERMISSION)))
    }
}
