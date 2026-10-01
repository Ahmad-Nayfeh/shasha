package com.ahmadnayfeh.shasha.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

/** «اللغة»: the app's language, whatever the TV's. The layout follows it: right to left in Arabic, left to right in English. */
enum class AppLanguage(val tag: String, val isRtl: Boolean) {
    ARABIC("ar", isRtl = true),
    ENGLISH("en", isRtl = false),
    ;

    /**
     * What the app's resources are read with. Arabic keeps Western digits (0-9), as everywhere in the app: a string
     * formatted with this locale never turns into Arabic-Indic digits.
     */
    val locale: Locale get() = Locale.forLanguageTag(if (this == ARABIC) "ar-u-nu-latn" else "en")

    companion object {
        val DEFAULT = ARABIC

        /** A stored tag; anything this build does not offer reads as the default. */
        fun of(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}

/** «المظهر»: dark (the brand's, the default) or light. */
enum class AppTheme(val tag: String) {
    DARK("dark"),
    LIGHT("light"),
    ;

    companion object {
        val DEFAULT = DARK

        fun of(tag: String?): AppTheme = entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}

/** The language and the theme on screen. */
@Immutable
data class Appearance(val language: AppLanguage = AppLanguage.DEFAULT, val theme: AppTheme = AppTheme.DEFAULT)

/**
 * The [Appearance] the whole process shows, kept in [BehaviorSettings] (so it is in the backup and in «إرجاع كل
 * الإعدادات»). The activity and the services need the language before they draw anything: the first [current] reads
 * the file once, blocking (a few bytes; it also warms the store [BehaviorSettings] reads); then [state] follows every
 * change (Settings, the phone, a restored backup, a reset).
 */
class AppearanceState(private val behavior: BehaviorSettings, scope: CoroutineScope) {
    private val _state = MutableStateFlow(Appearance())

    @Volatile
    private var read = false

    /** What is on screen now; the first call reads the file if nothing did yet. */
    fun current(): Appearance {
        ensureRead()
        return _state.value
    }

    /** Every change, from the stored value on (call [current] once first when the value matters right away). */
    val state: StateFlow<Appearance>
        get() {
            ensureRead()
            return _state.asStateFlow()
        }

    init {
        scope.launch {
            behavior.awaitLoaded()
            behavior.values.collect {
                _state.value = it.appearance
                read = true
            }
        }
    }

    private fun ensureRead() {
        if (read) return
        synchronized(this) {
            if (read) return
            _state.value = try {
                runBlocking { behavior.current() }.appearance
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A damaged file reads as the defaults (BehaviorSettings does the same).
                Appearance()
            }
            read = true
        }
    }
}
