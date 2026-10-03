package com.ahmadnayfeh.shasha.update

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

/**
 * Reads [ApkIdentity]s with the PackageManager. Every call is safe from API 25: the signing certificates and the
 * long version code arrived with API 28 (Android 9), so below that the same facts are read from the old
 * `GET_SIGNATURES` / `signatures` / `versionCode` (deprecated, but the only way there). An APK and the installed app are
 * always read the same way on one device, so their digests are comparable.
 */
class ApkInspector(private val context: Context) {
    fun installed(): ApkIdentity {
        val pm = context.packageManager
        val flags = signatureFlags()
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }
        return identity(info)
    }

    /** Null when the system cannot read [apk] as a package. */
    fun archive(apk: File): ApkIdentity? {
        val pm = context.packageManager
        val flags = signatureFlags()
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.path, flags)
            }
        } catch (e: RuntimeException) {
            null
        }
        return info?.let(::identity)
    }

    private fun identity(info: PackageInfo): ApkIdentity = ApkIdentity(
        packageName = info.packageName,
        versionCode = PackageInfoCompat.getLongVersionCode(info),
        certificateDigests = certificates(info).mapTo(HashSet()) { ApkVerifier.digest(it) },
    )

    private companion object {
        /** What to ask the PackageManager for: the signing certificates (API 28+), else the signatures. */
        fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        /**
         * The certificates that sign the contents now (from API 28 not the past ones a rotated key proved): they must
         * be the same. Below API 28 an APK has no key rotation, and its signatures are those certificates.
         */
        fun certificates(info: PackageInfo): List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().map { it.toByteArray() }
        }
    }
}
