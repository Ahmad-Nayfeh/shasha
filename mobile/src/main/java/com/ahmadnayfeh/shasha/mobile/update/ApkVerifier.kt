package com.ahmadnayfeh.shasha.mobile.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
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
 * The downloaded APK is checked before the system installer ever sees it: the same app, a higher versionCode, and
 * exactly the same signing certificate(s) as the installed app. The last one is not negotiable: Android refuses an update
 * signed with another key, so the release key must never change (android.yml).
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

/** Reads [ApkIdentity]s with the PackageManager (signing certificates from Android 9, the old signatures before). */
class ApkInspector(private val context: Context) {
    fun installed(): ApkIdentity {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, FLAGS)
        }
        return identity(info)
    }

    /** Null when the system cannot read [apk] as a package. */
    fun archive(apk: File): ApkIdentity? {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.path, FLAGS)
            }
        } catch (e: RuntimeException) {
            null
        }
        return info?.let(::identity)
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val certificates: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The certificates that sign the contents now (not the past ones a rotated key proved): they must be the same.
            info.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        } else {
            info.signatures.orEmpty().map { it.toByteArray() }
        }
        return ApkIdentity(
            packageName = info.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            certificateDigests = certificates.mapTo(HashSet()) { ApkVerifier.digest(it) },
        )
    }

    private companion object {
        /** GET_SIGNING_CERTIFICATES (Android 9+) or GET_SIGNATURES (before). */
        val FLAGS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }
}
