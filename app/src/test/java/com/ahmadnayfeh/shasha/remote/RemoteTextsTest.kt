package com.ahmadnayfeh.shasha.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the phone's page is told when the TV cannot play a video. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RemoteTextsTest {
    private val texts = RemoteTexts(ApplicationProvider.getApplicationContext<Context>().resources)

    private fun error(kind: SourceErrorKind) = SourceException(kind, "x", "message", null)

    @Test
    fun theTvsOwnDecoderFailingIsNotYouTubeChangingSomething() {
        val message = texts.sourceError(error(SourceErrorKind.DEVICE_DECODER))
        assertThat(message).isEqualTo("تعذّر على الشاشة تشغيل هذا الفيديو. جرّب فيديو آخر؛ وإن تكرر فأعد تشغيل الشاشة.")
        assertThat(message).doesNotContain("يوتيوب")
        assertThat(message).doesNotContain("حدّث")
    }

    @Test
    fun theOtherKindsAreAsTheyWere() {
        assertThat(texts.sourceError(error(SourceErrorKind.NETWORK))).isEqualTo("الشاشة غير متصلة بالإنترنت الآن.")
        assertThat(texts.sourceError(error(SourceErrorKind.EXTRACTION_BROKEN)))
            .startsWith("يوتيوب غيّر شيئاً. حدّث التطبيق. تعذّر فهم ردّ يوتيوب.")
        assertThat(texts.sourceError(error(SourceErrorKind.PRIVATE))).isEqualTo("هذا الفيديو خاص، ولا يُشاهَد إلا بحساب مسموح له.")
    }

    @Test
    fun everyKindHasAnAnswerForThePhone() {
        for (kind in SourceErrorKind.entries) assertThat(texts.sourceError(error(kind))).isNotEmpty()
    }
}
