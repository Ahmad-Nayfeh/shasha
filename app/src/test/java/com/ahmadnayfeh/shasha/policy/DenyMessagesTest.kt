package com.ahmadnayfeh.shasha.policy

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DenyMessagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyReason_hasItsOwnArabicSentence() {
        val texts = DenyReason.entries.associateWith { context.getString(it.messageRes()) }
        assertThat(texts.values.toSet()).hasSize(DenyReason.entries.size)
        texts.values.forEach { text ->
            assertThat(text.any { it in '؀'..'ۿ' }).isTrue()
            assertThat(text.none { it in '٠'..'٩' }).isTrue() // Western digits only
        }
        assertThat(PlayCheck.Denied(DenyReason.TIME_UP).message).isEqualTo(DenyReason.TIME_UP.messageRes())
    }
}
