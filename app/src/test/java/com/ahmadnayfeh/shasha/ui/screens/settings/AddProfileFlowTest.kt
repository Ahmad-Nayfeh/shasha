package com.ahmadnayfeh.shasha.ui.screens.settings

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.ProfileKind
import com.ahmadnayfeh.shasha.ui.components.EDITOR_TAG
import com.ahmadnayfeh.shasha.ui.screens.setup.SetupScreen
import com.ahmadnayfeh.shasha.ui.screens.setup.SetupStep
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The owner's report: adding a new profile went wrong. The whole of «الإعدادات ← الملفات ← إضافة ملف طفل» with the remote only:
 * the dialog opened with the focus inside the name's text field, so the TV's keyboard covered the dialog at once, came back
 * each time the focus passed the name, and its action key left the focus in the field (no way on to «إنشاء» but closing the
 * keyboard and finding the button). Now the focus starts on the name's box (no keyboard), OK opens the keyboard, its action
 * key goes on to «إنشاء», and the child is stored with the child defaults and the household's blocks, then its page opens.
 * The first start's child step is driven the same way.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class AddProfileFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: ShashaDatabase
    private lateinit var profiles: ProfileRepository
    private val opened = mutableListOf<String>()
    private var hosting by mutableStateOf(true)

    private val blocked = ListedChannel("UCblockedblockedblocked00", "قناة محظورة")

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        profiles = ProfileRepository(db)
        val owner = profiles.ensureOwner("الكبار")
        profiles.update(owner.id) { it.copy(blocklist = it.blocklist.withChannel(blocked)) }
        Unit
    }

    @After
    fun tearDown() {
        hosting = false
        compose.waitForIdle()
        db.close()
    }

    private fun showProfiles() {
        val vm = ProfilesViewModel(profiles)
        compose.setContent {
            ShashaTheme {
                if (hosting) ProfilesContent(vm, onOpenProfile = { opened += it })
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("profile_$ADULT")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun press(key: Key, on: String? = null) {
        val target = if (on == null) compose.onRoot() else compose.onNodeWithTag(on)
        target.performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun nameEditor(): SemanticsNodeInteraction =
        compose.onNode(hasTestTag(EDITOR_TAG) and hasAnyAncestor(hasTestTag("new_child_name")), useUnmergedTree = true)

    private fun systemKeyboardShown(): Boolean {
        val imm = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return shadowOf(imm).isSoftInputVisible
    }

    private fun openDialogWithTheRemote() {
        showProfiles()
        compose.onNodeWithTag("profile_$ADULT").assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithTag("profiles_add_child").assertIsFocused()
        press(Key.DirectionCenter)
        compose.onNodeWithTag("new_child_dialog").assertExists()
    }

    @Test
    fun addingAChild_withTheRemoteOnly_storesIt_andOpensItsPage() {
        openDialogWithTheRemote()
        // The dialog opens on the name's box: no keyboard over it yet.
        compose.onNodeWithTag("new_child_name").assertIsFocused()
        nameEditor().assertIsNotFocused()
        assertThat(systemKeyboardShown()).isFalse()

        // OK opens the keyboard in the field; its action key goes on to «إنشاء».
        press(Key.DirectionCenter, on = "new_child_dialog")
        nameEditor().assertIsFocused()
        nameEditor().performTextInput("  سارة ")
        nameEditor().performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag("new_child_create").assertIsFocused()

        press(Key.DirectionCenter, on = "new_child_dialog")
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        compose.onNodeWithTag("new_child_dialog").assertDoesNotExist()

        val stored = runBlocking { profiles.all() }
        assertThat(stored.map { it.name }).containsExactly("الكبار", "سارة").inOrder()
        val child = stored.single { it.isChild }
        assertThat(opened).containsExactly(child.id)
        assertThat(child.kind).isEqualTo(ProfileKind.CHILD)
        assertThat(child.avatar).isEqualTo(Profile.DEFAULT_CHILD_AVATAR)
        assertThat(child.dailyQuotaMinutes).isEqualTo(Profile.DEFAULT_CHILD_QUOTA_MINUTES)
        assertThat(child.schedule).isEqualTo(Profile.DEFAULT_CHILD_SCHEDULE)
        assertThat(child.hideShorts).isTrue()
        assertThat(child.showSuggestions).isFalse()
        assertThat(child.blocklist.channels).containsExactly(blocked)
    }

    private fun focused(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()

    /** Down from the name, past the pictures, to the buttons' row; then along it to [tag]. */
    private fun downToButton(tag: String) {
        var presses = 0
        while (!focused("new_child_create") && !focused("new_child_cancel")) {
            check(++presses <= 4) { "the D-pad never reached «إنشاء» or «إلغاء»" }
            press(Key.DirectionDown, on = "new_child_dialog")
        }
        // Arabic: «إنشاء» first (on the right), «إلغاء» to its left.
        if (!focused(tag)) press(if (tag == "new_child_cancel") Key.DirectionLeft else Key.DirectionRight, on = "new_child_dialog")
        compose.onNodeWithTag(tag).assertIsFocused()
    }

    @Test
    fun theDpadReachesCreate_andCancel_andBackToTheName_withoutOpeningTheKeyboard() {
        openDialogWithTheRemote()
        downToButton("new_child_create")
        downToButton("new_child_cancel")
        var presses = 0
        while (!focused("new_child_name")) {
            check(++presses <= 4) { "the D-pad never came back to the name" }
            press(Key.DirectionUp, on = "new_child_dialog")
        }
        nameEditor().assertIsNotFocused()
        assertThat(systemKeyboardShown()).isFalse()
    }

    @Test
    fun createWithoutAName_saysSo_andGoesBackToTheName_withoutTheKeyboard() {
        openDialogWithTheRemote()
        downToButton("new_child_create")
        press(Key.DirectionCenter, on = "new_child_dialog")
        compose.onNodeWithTag("new_child_name_missing").assertExists()
        compose.onNodeWithTag("new_child_name").assertIsFocused()
        nameEditor().assertIsNotFocused()
        assertThat(runBlocking { profiles.all() }.count { it.isChild }).isEqualTo(0)
        assertThat(opened).isEmpty()
    }

    @Test
    fun cancel_closesTheDialog_andStoresNothing() {
        openDialogWithTheRemote()
        downToButton("new_child_cancel")
        press(Key.DirectionCenter, on = "new_child_dialog")
        compose.onNodeWithTag("new_child_dialog").assertDoesNotExist()
        assertThat(runBlocking { profiles.all() }.count { it.isChild }).isEqualTo(0)
    }

    @Test
    fun twoChildrenWithTheSameName_areTwoProfiles() = runBlocking {
        val vm = ProfilesViewModel(profiles)
        val first = vm.createChild("سارة", "cat")!!
        val second = vm.createChild("سارة", "cat")!!
        assertThat(first.id).isNotEqualTo(second.id)
        assertThat(profiles.all().count { it.isChild }).isEqualTo(2)
        assertThat(vm.createChild("   ", "cat")).isNull()
    }

    /** The first start's «ملف طفل» step: the same remote-only walk, no keyboard before OK. */
    @Test
    fun theFirstStartsChildStep_withTheRemoteOnly() {
        val children = mutableListOf<Pair<String, String>>()
        compose.setContent {
            ShashaTheme {
                SetupScreen(
                    SetupStep.CHILD,
                    busy = false,
                    ownerName = "",
                    ownerAvatar = Profile.DEFAULT_ADULT_AVATAR,
                    onStart = {},
                    onPin = {},
                    onOwner = { _, _ -> },
                    onChild = { name, avatar -> children += name to avatar },
                    onSkipChild = {},
                )
            }
        }
        compose.waitForIdle()
        val editor = compose.onNode(hasTestTag(EDITOR_TAG) and hasAnyAncestor(hasTestTag("setup_name")), useUnmergedTree = true)
        compose.onNodeWithTag("setup_name").assertIsFocused()
        editor.assertIsNotFocused()
        assertThat(systemKeyboardShown()).isFalse()

        press(Key.DirectionCenter)
        editor.assertIsFocused()
        editor.performTextInput("سارة")
        editor.performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag("setup_primary").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(children.map { it.first }).containsExactly("سارة")
    }

    private companion object {
        const val ADULT = com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
    }
}
