package com.ahmadnayfeh.shasha.mobile.common

import android.content.Context
import android.content.res.Configuration
import com.ahmadnayfeh.shasha.mobile.data.AppLanguage

/**
 * The app's language over the phone's configuration. The activity applies [overrideFor] before it reads any resource
 * (and is recreated when the language changes); services and notifications read their texts through [localize].
 * [AppLanguage.SYSTEM] changes nothing: the phone's language (or Android's per-app language) decides.
 */
object AppLocale {
    /** Only the locale (and with it the layout direction); the font scale is left "undefined" so the phone's stays. */
    fun overrideFor(language: AppLanguage): Configuration? = language.locale?.let { locale ->
        Configuration().apply {
            fontScale = 0f
            setLocale(locale)
        }
    }

    /** [context]'s resources read in [language]: for texts shown outside the activity (notifications). */
    fun localize(context: Context, language: AppLanguage): Context {
        val locale = language.locale ?: return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
    }
}
