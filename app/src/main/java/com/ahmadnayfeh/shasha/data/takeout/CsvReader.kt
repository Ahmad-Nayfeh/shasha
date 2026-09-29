package com.ahmadnayfeh.shasha.data.takeout

/**
 * RFC 4180 records, read leniently: quoted fields may hold commas, line breaks and doubled quotes
 * (""), records end at CRLF, LF or CR, a stray quote inside an unquoted field is kept as text, and an
 * unterminated quote runs to the end of the input.
 *
 * Memory stays bounded whatever the input: a record keeps at most [maxFields] fields and a field at
 * most [maxFieldChars] characters (a subscriptions row has three short ones).
 */
internal class CsvReader(
    private val text: String,
    private val maxFields: Int = 32,
    private val maxFieldChars: Int = 2048,
) {
    fun records(): Sequence<List<String>> = sequence {
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        fun append(c: Char) {
            if (field.length < maxFieldChars) field.append(c)
        }

        fun endField() {
            if (fields.size < maxFields) fields.add(field.toString())
            field.setLength(0)
        }

        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    append('"')
                    i++
                }
                quoted && c == '"' -> quoted = false
                quoted -> append(c)
                // A quote opens a quoted field only at its start (blanks before it are dropped).
                c == '"' && field.isBlank() -> {
                    field.setLength(0)
                    quoted = true
                }
                c == ',' -> endField()
                c == '\n' || c == '\r' -> {
                    endField()
                    yield(fields)
                    fields = ArrayList()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> append(c)
            }
            i++
        }
        if (quoted || field.isNotEmpty() || fields.isNotEmpty()) {
            endField()
            yield(fields)
        }
    }
}
