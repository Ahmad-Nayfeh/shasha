package com.ahmadnayfeh.shasha.ui.theme

import android.content.Context
import android.content.res.Configuration
import com.ahmadnayfeh.shasha.data.AppLanguage

/**
 * The app's language over the TV's configuration. The activity applies [overrideFor] before it reads any resource
 * (and is recreated when the language changes); services, notifications and the guard's cover read their texts through
 * [localize], built for the language of the moment.
 */
object AppLocale {
    /**
     * Only the locale (and with it the layout direction): what the activity sets over the TV's configuration. The
     * font scale is "undefined" so the TV's text size stays as the viewer set it.
     */
    fun overrideFor(language: AppLanguage): Configuration = Configuration().apply {
        fontScale = 0f
        setLocale(language.locale)
    }

    /** [context]'s resources read in [language]: for texts shown outside the activity (notifications, overlays). */
    fun localize(context: Context, language: AppLanguage): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(language.locale) })
}
