package com.ahmadnayfeh.shasha.mobile.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SPEC §6: «ثبّت مفتاح التوقيع وإلا فشل التحديث فوق النسخة القديمة». The downloaded APK is refused before the system
 * installer sees it unless it is the same app, newer, and signed with exactly the installed app's certificate(s).
 */
class ApkVerifierTest {
    private val owner = ApkVerifier.digest("the owner's release certificate".toByteArray())
    private val other = ApkVerifier.digest("another key".toByteArray())
    private val installed = ApkIdentity("com.ahmadnayfeh.shasha", versionCode = 1041, certificateDigests = setOf(owner))

    @Test
    fun theSameAppNewerAndSignedWithTheSameKey_mayReplaceIt() {
        assertThat(ApkVerifier.refusal(installed, installed.copy(versionCode = 1042))).isNull()
    }

    @Test
    fun aSignatureMismatchIsRefused() {
        assertThat(ApkVerifier.refusal(installed, installed.copy(versionCode = 1042, certificateDigests = setOf(other))))
            .isEqualTo(UpdateErrorKind.SIGNATURE_MISMATCH)
        // An extra signer, a missing one, or signers the system could not read: never "the same".
        assertThat(ApkVerifier.refusal(installed, installed.copy(versionCode = 1042, certificateDigests = setOf(owner, other))))
            .isEqualTo(UpdateErrorKind.SIGNATURE_MISMATCH)
        assertThat(ApkVerifier.refusal(installed, installed.copy(versionCode = 1042, certificateDigests = emptySet())))
            .isEqualTo(UpdateErrorKind.SIGNATURE_MISMATCH)
        assertThat(ApkVerifier.refusal(installed.copy(certificateDigests = emptySet()), installed.copy(versionCode = 1042, certificateDigests = emptySet())))
            .isEqualTo(UpdateErrorKind.SIGNATURE_MISMATCH)
    }

    @Test
    fun anotherAppAnOlderBuildOrAnUnreadableFileIsRefused() {
        assertThat(ApkVerifier.refusal(installed, installed.copy(packageName = "com.example.other", versionCode = 1042)))
            .isEqualTo(UpdateErrorKind.WRONG_PACKAGE)
        assertThat(ApkVerifier.refusal(installed, installed)).isEqualTo(UpdateErrorKind.NOT_NEWER)
        assertThat(ApkVerifier.refusal(installed, installed.copy(versionCode = 1000))).isEqualTo(UpdateErrorKind.NOT_NEWER)
        assertThat(ApkVerifier.refusal(installed, null)).isEqualTo(UpdateErrorKind.BAD_APK)
    }

    @Test
    fun certificateDigestsAreSha256InLowerCaseHex() {
        // echo -n abc | sha256sum
        assertThat(ApkVerifier.digest("abc".toByteArray())).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
