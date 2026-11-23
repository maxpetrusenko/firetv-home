package app.firetvhome

/** Display-only cleanup of booking codes in titles ("... conf ABC123", "PNR XYZ789"). Pure, no Android types. */
object BookingCodes {
    // Keyword is case-insensitive, the code itself must be 5-8 uppercase letters/digits, so "Conference call" and "Confirm dentist" never match.
    private val CODE = Regex("(?i:\\b(?:conf(?:irmation)?|pnr)\\b)\\s*[.:#]?\\s*[A-Z0-9]{5,8}\\b")
    private val EMPTY_BRACKETS = Regex("\\(\\s*\\)|\\[\\s*]")
    private val SEP_RUN = Regex("(?:\\s*[-\u2013\u2014,;|\u00B7]\\s+|\\s+[-\u2013\u2014,;|\u00B7]\\s*){2,}")
    private val EDGE = Regex("^[\\s\\-\u2013\u2014,;:|\u00B7]+|[\\s\\-\u2013\u2014,;:|\u00B7]+$")

    fun strip(title: String, enabled: Boolean = true): String {
        if (!enabled || !CODE.containsMatchIn(title)) return title
        var t = CODE.replace(title, "")
        t = EMPTY_BRACKETS.replace(t, "")
        t = SEP_RUN.replace(t) { if (it.value.all { c -> c == ',' || c.isWhitespace() }) ", " else " - " }
        t = t.replace(Regex("\\s{2,}"), " ")
        t = EDGE.replace(t, "")
        return t.ifEmpty { title }
    }
}
