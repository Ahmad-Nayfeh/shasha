package com.ahmadnayfeh.shasha.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** What decides whether one APK may replace another: its package, its versionCode and who signed it. */
data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    /** SHA-256 (lower-case hex) of each certificate that signs the APK's contents; empty when unknown. */
    val certificateDigests: Set<String>,
)

/**
 * The downloaded APK is checked before the system installer ever sees it (SPEC §6): the same app, a higher
 * versionCode, and exactly the same signing certificate(s) as the installed app. The last one is not negotiable:
 * Android refuses an update signed with another key, so the release key must never change (android.yml).
 */
object ApkVerifier {
    /** Null when [candidate] may replace [installed]; otherwise why not. A file the system cannot read is BAD_APK. */
    fun refusal(installed: ApkIdentity, candidate: ApkIdentity?): UpdateErrorKind? = when {
        candidate == null -> UpdateErrorKind.BAD_APK
        candidate.packageName != installed.packageName -> UpdateErrorKind.WRONG_PACKAGE
        candidate.versionCode <= installed.versionCode -> UpdateErrorKind.NOT_NEWER
        // Unknown signers on either side are never taken for a match.
        candidate.certificateDigests.isEmpty() || candidate.certificateDigests != installed.certificateDigests ->
            UpdateErrorKind.SIGNATURE_MISMATCH
        else -> null
    }

    /** SHA-256 of a certificate, lower-case hex (as `apksigner verify --print-certs` prints it). */
    fun digest(certificate: ByteArray): String = buildString {
        for (byte in MessageDigest.getInstance("SHA-256").digest(certificate)) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"
}

/** Reads [ApkIdentity]s with the PackageManager (minSdk 28: signing certificates, not the old signatures). */
class ApkInspector(private val context: Context) {
    fun installed(): ApkIdentity {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        return identity(info)
    }

    /** Null when the system cannot read [apk] as a package. */
    fun archive(apk: File): ApkIdentity? {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        } catch (e: RuntimeException) {
            null
        }
        return info?.let(::identity)
    }

    private fun identity(info: PackageInfo): ApkIdentity {
        // The certificates that sign the contents now (not the past ones a rotated key proved): they must be the same.
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        return ApkIdentity(
            packageName = info.packageName,
            versionCode = info.longVersionCode,
            certificateDigests = signers.mapTo(HashSet()) { ApkVerifier.digest(it.toByteArray()) },
        )
    }
}
