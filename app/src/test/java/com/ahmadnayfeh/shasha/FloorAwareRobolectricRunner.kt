package com.ahmadnayfeh.shasha

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.RobolectricTestRunner.RobolectricFrameworkMethod
import java.io.File

/**
 * Robolectric at an SDK below the app's own minSdk cannot start: it parses the app's merged manifest, and the framework of
 * that level refuses it («Requires newer sdk version #28 (current version is #25)»). With this runner a test written for
 * SDK 25 (Fire OS 6, Android 7.1) simply runs from the day the app's `minSdk` is 25, and until then only at the higher
 * levels it lists, so it never has to be edited when `minSdk` changes. Use it as `@RunWith(FloorAwareRobolectricRunner::class)`
 * with `@Config(sdk = [25, 35])`: the highest level always runs.
 */
class FloorAwareRobolectricRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun getChildren(): MutableList<FrameworkMethod> = super.getChildren()
        .filter { method -> (method as? RobolectricFrameworkMethod)?.sdk?.apiLevel?.let { it >= APP_MIN_SDK } ?: true }
        .toMutableList()

    private companion object {
        /** The module's own `minSdk = N` in build.gradle.kts (the tests run in the app module's directory). */
        val APP_MIN_SDK: Int = Regex("""minSdk\s*=\s*(\d+)""").find(File("build.gradle.kts").readText())?.groupValues?.get(1)?.toInt() ?: 1
    }
}
