package com.ahmadnayfeh.shasha.remote

/** Arabic texts the server itself produces (the controller writes its own). Western digits. */
internal object ServerMessages {
    const val PAIRING = "رابط الاقتران غير صالح. امسح رمز QR الظاهر على الشاشة من جديد."
    const val PARENT_REQUIRED = "سجّل الدخول إلى قسم الأب أولاً."
    const val PARENT_EXPIRED = "انتهت جلسة قسم الأب. أدخل رمز PIN من جديد."
    const val PARENT_WELCOME = "أهلاً بك في قسم الأب."
    const val LOGGED_OUT = "خرجت من قسم الأب."
    const val BAD_REQUEST = "الطلب غير صالح."
    const val NOT_FOUND = "غير موجود."
    const val SERVER_ERROR = "حدث خطأ على الشاشة. حاول مرة أخرى."
    const val SEARCH_DISABLED = "البحث غير متاح في هذا الملف."
    const val EMPTY_QUERY = "اكتب ما تريد البحث عنه."
    const val EMPTY_LINK = "الصق رابط يوتيوب أولاً."
    const val EMPTY_PIN = "أدخل رمز PIN."
    const val EMPTY_FILE = "الملف فارغ."
    const val NO_FILE = "لم يصل أي ملف."
    const val TAKEOUT_TYPE = "اختر ملف subscriptions.csv أو ملف Takeout المضغوط (zip)."
    const val TAKEOUT_NOT_FOUND = "لم أجد ملف subscriptions.csv داخل الملف المضغوط."
    const val TAKEOUT_BROKEN = "تعذّرت قراءة الملف المضغوط."

    fun tooLarge(maxBytes: Long): String {
        val mb = maxBytes / (1024 * 1024)
        return if (mb >= 1) "الملف أكبر من الحد المسموح ($mb ميغابايت)." else "الطلب أكبر من الحد المسموح."
    }

    fun wrongPin(attemptsLeft: Int): String = when {
        attemptsLeft <= 0 -> "رمز PIN غير صحيح."
        attemptsLeft == 1 -> "رمز PIN غير صحيح. بقيت محاولة واحدة."
        attemptsLeft == 2 -> "رمز PIN غير صحيح. بقيت محاولتان."
        attemptsLeft <= 10 -> "رمز PIN غير صحيح. بقيت $attemptsLeft محاولات."
        else -> "رمز PIN غير صحيح. بقيت $attemptsLeft محاولة."
    }

    fun lockedOut(retryAfterSeconds: Long): String {
        val minutes = ((retryAfterSeconds + 59) / 60).coerceAtLeast(1)
        val wait = when {
            minutes == 1L -> "دقيقة واحدة"
            minutes == 2L -> "دقيقتين"
            minutes <= 10L -> "$minutes دقائق"
            else -> "$minutes دقيقة"
        }
        return "محاولات خاطئة كثيرة. حاول بعد $wait."
    }
}
