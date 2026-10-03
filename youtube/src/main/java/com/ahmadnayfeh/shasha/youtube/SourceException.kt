package com.ahmadnayfeh.shasha.youtube

/**
 * The only exception type that leaves this module. The app shows one unified error screen for it.
 *
 * @property technicalName the original exception's simple name, "HTTP 403", or a short tag for
 * failures detected by our own checks (e.g. "MuxedOnly360p").
 */
class SourceException(
    val kind: SourceErrorKind,
    val technicalName: String,
    message: String?,
    cause: Throwable?,
) : Exception(message, cause) {
    override fun toString(): String = "SourceException($kind, $technicalName): $message"
}

enum class SourceErrorKind {
    CONTENT_UNAVAILABLE,
    PRIVATE,
    AGE_RESTRICTED,
    GEO_RESTRICTED,
    PAID,
    ACCOUNT_TERMINATED,

    /** "Sign in to confirm you're not a bot". */
    BOT_CHECK,
    BLOCKED_403,
    RATE_LIMITED,

    /** Only ≤360p muxed streams came back: the signature of a broken extraction. */
    LOW_QUALITY_ONLY,

    /** Offline / DNS / timeout: not YouTube's fault. */
    NETWORK,

    /** Parsing or unknown extractor failure. */
    EXTRACTION_BROKEN,

    /** This device's video decoder failed to start or to decode the picture: not YouTube's fault, and no update helps. */
    DEVICE_DECODER,
}
