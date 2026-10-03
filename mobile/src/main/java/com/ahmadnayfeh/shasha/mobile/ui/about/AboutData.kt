package com.ahmadnayfeh.shasha.mobile.ui.about

import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.mobile.R

/** The developer's other apps and the links to them. */
object AboutLinks {
    const val SHADRA_WEBSITE = "https://shadhra.app/"
    const val SHADRA_PLAY = "https://play.google.com/store/apps/details?id=app.shadhra"
    const val SILAH = "https://github.com/Ahmad-Nayfeh/silah"
    const val MASARIF = "https://github.com/Ahmad-Nayfeh/masarif"
    const val KHATWA = "https://github.com/Ahmad-Nayfeh/khatwa"

    /** SponsorBlock's own page, linked from its attribution. */
    const val SPONSORBLOCK = "https://sponsor.ajay.app/"
}

/** One link of an app of the developer: its label and where it goes. */
data class AppLink(@StringRes val label: Int, val url: String)

/** One of the developer's apps ([name] is a name, never translated). */
data class OtherApp(val name: String, val links: List<AppLink>)

object OtherApps {
    val all: List<OtherApp> = listOf(
        OtherApp("Shadra", listOf(AppLink(R.string.about_link_website, AboutLinks.SHADRA_WEBSITE), AppLink(R.string.about_link_play, AboutLinks.SHADRA_PLAY))),
        OtherApp("Silah", listOf(AppLink(R.string.about_link_open, AboutLinks.SILAH))),
        OtherApp("Masarif", listOf(AppLink(R.string.about_link_open, AboutLinks.MASARIF))),
        OtherApp("Khatwa", listOf(AppLink(R.string.about_link_open, AboutLinks.KHATWA))),
    )
}

/** One line of the licences list: the project, its licence (SPDX name, as the project states it) and what it does here. */
data class LicenseEntry(val name: String, val license: String, @StringRes val role: Int)

/**
 * What the APK contains and under which licence (the same list as the TV app's, which was checked against each
 * dependency's POM, without what this app does not carry: the phone remote's server, the QR code, the logging interface).
 */
object Licenses {
    val libraries: List<LicenseEntry> = listOf(
        LicenseEntry("NewPipeExtractor", "GPL-3.0-or-later", R.string.license_role_newpipe),
        LicenseEntry("AndroidX · Jetpack Compose · Media3", "Apache-2.0", R.string.license_role_androidx),
        LicenseEntry("OkHttp", "Apache-2.0", R.string.license_role_okhttp),
        LicenseEntry("Kotlin · kotlinx", "Apache-2.0", R.string.license_role_kotlinx),
        LicenseEntry("Coil", "Apache-2.0", R.string.license_role_coil),
        LicenseEntry("Mozilla Rhino", "MPL-2.0", R.string.license_role_rhino),
        LicenseEntry("jsoup", "MIT", R.string.license_role_jsoup),
        LicenseEntry("nanojson", "Apache-2.0", R.string.license_role_nanojson),
        LicenseEntry("protobuf-javalite", "BSD-3-Clause", R.string.license_role_protobuf),
        LicenseEntry("Okio · Guava · JSpecify", "Apache-2.0", R.string.license_role_helpers),
        LicenseEntry("desugar_jdk_libs", "GPL-2.0 WITH Classpath-exception-2.0", R.string.license_role_desugar),
        LicenseEntry("IBM Plex Sans Arabic", "OFL-1.1", R.string.license_role_plex),
    )
}
