package com.ahmadnayfeh.shasha.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.contentFocus
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SPEC acceptance: in a child profile search does not exist in the interface at all. The rail has no search
 * item, no text on screen says «البحث», and neither the profile's navigation graph nor its tab graph has a
 * search destination to reach, even by code. The adult-only destinations are absent too.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class ChildProfileNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private lateinit var tabs: NavHostController
    private var exits = 0

    private val child = Profile.newChild("سارة", id = "kid")

    private fun show(profile: Profile) {
        compose.setContent {
            nav = rememberNavController()
            tabs = rememberNavController()
            ShashaTheme {
                ProfileNavHost(
                    profile = profile,
                    nav = nav,
                    onSwitchProfile = {},
                    onExitProfile = { exits++ },
                    tabsNav = tabs,
                    tabContent = { tab, focus, _ ->
                        TakeFocusWhenReady(focus, ready = true)
                        Box(Modifier.fillMaxSize().contentFocus(focus).focusGroup(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(120.dp).focusable().testTag("content_${tab.name}"))
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun NavGraph.routes(): List<String> = map { it.route.orEmpty() }

    @Test
    fun child_hasNoSearch_neitherOnScreenNorInTheNavGraph() {
        show(child)

        // On screen: the two tabs and «خروج», nothing else.
        compose.onNodeWithTag("content_HOME").assertIsFocused()
        compose.onNodeWithTag("rail_HOME").assertExists()
        compose.onNodeWithTag("rail_ALLOWED_CHANNELS").assertExists()
        compose.onNodeWithTag("rail_exit").assertExists()
        listOf(Tab.SEARCH, Tab.SUBSCRIPTIONS, Tab.WATCH_LATER, Tab.HISTORY, Tab.SETTINGS).forEach {
            compose.onNodeWithTag("rail_${it.name}").assertDoesNotExist()
        }
        compose.onAllNodesWithText("البحث", substring = true).assertCountEquals(0)

        // In the graphs: no destination leads to a search.
        assertThat(tabs.graph.findNode(Tab.SEARCH.route)).isNull()
        assertThat(tabs.graph.routes()).containsExactly(Tab.HOME.route, Tab.ALLOWED_CHANNELS.route)
        assertThat(nav.graph.routes().filter { "search" in it.lowercase() }).isEmpty()
        val adultOnly = listOf("ImportSubscriptions", "Profiles", "ProfileEdit", "Allowlist", "Blocklist", "ChangePin", "Stats")
        assertThat(nav.graph.routes().filter { route -> adultOnly.any { route.endsWith(".$it") || ".$it/" in route || ".$it?" in route } }).isEmpty()

        // Not reachable by code either: the graph does not know the route.
        compose.runOnUiThread {
            assertThrows(IllegalArgumentException::class.java) { tabs.navigate(Tab.SEARCH.route) }
        }
    }

    @Test
    fun child_theRailWalksOnlyItsTwoTabs_andExitAsksTheParent() {
        show(child)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("rail_HOME").assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithTag("rail_ALLOWED_CHANNELS").assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithTag("rail_exit").assertIsFocused()
        press(Key.DirectionDown) // nothing below
        compose.onNodeWithTag("rail_exit").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(exits).isEqualTo(1)
    }

    @Test
    fun adult_hasSearch_andItsOwnDestinations() {
        show(Profile.newAdult("الكبار", id = "adult"))
        compose.onNodeWithTag("rail_SEARCH").assertExists()
        compose.onNodeWithTag("rail_switch_profile").assertExists()
        compose.onNodeWithTag("rail_exit").assertDoesNotExist()
        assertThat(tabs.graph.findNode(Tab.SEARCH.route)).isNotNull()
        assertThat(nav.graph.routes().any { it.endsWith(".ImportSubscriptions") }).isTrue()
        assertThat(nav.graph.routes().any { it.contains(".Stats") }).isTrue()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
}
