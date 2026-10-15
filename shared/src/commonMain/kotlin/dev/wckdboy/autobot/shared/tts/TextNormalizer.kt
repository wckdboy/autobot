package dev.wckdboy.autobot.shared.tts

/**
 * Plain text → text misaki reads well: web addresses and e-mails, abbreviations, dates and
 * times, units, ranges, fractions and symbols become words. Numbers, ordinals, years and
 * `$` / `£` / `€` amounts are left to misaki's own number reading. Markdown is expected to be
 * gone already (the chat's `VoiceText.speakable`).
 */
object TextNormalizer {
    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )
    private val MONTH_ABBREVIATIONS = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "jun" to 5, "jul" to 6, "aug" to 7, "sep" to 8, "sept" to 8, "oct" to 9,
        "nov" to 10, "dec" to 11,
    )

    private val url = Regex("\\b(?:https?://|www\\.)[^\\s<>\"]+", RegexOption.IGNORE_CASE)
    private val email = Regex("\\b([A-Za-z0-9._%+-]+)@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)\\b")
    private val domain = Regex("\\b([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*)\\.(com|org|net|io|ai|dev|app|edu|gov|co|uk|de|fr|me|tv|info)\\b")
    private val isoDate = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")
    private val slashDate = Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b")
    private val monthDay = Regex("\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(\\d{1,2})\\b(?![\\d:])")
    private val monthAbbreviation = Regex("\\b(Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept?|Oct|Nov|Dec)\\.\\s+(?=\\d)")
    private val time = Regex("\\b(\\d{1,2}):(\\d{2})(?::\\d{2})?(?:\\s*([AaPp])\\.?\\s?[Mm]\\b\\.?)?")
    private val clock = Regex("\\b(\\d{1,2})\\s*([AaPp])\\.?\\s?[Mm]\\b\\.?")
    private val suffixedAmount = Regex("([$£€]\\s?\\d+(?:\\.\\d+)?)\\s?([KkMB]|bn|mn)\\b")
    private val trailingEuro = Regex("\\b(\\d[\\d.,]*)\\s?€")
    private val otherCurrency = Regex("([¥₹₩₽])\\s?(\\d[\\d,]*(?:\\.\\d+)?)")
    private val degrees = Regex("(\\d)\\s?°\\s?([CF])\\b")
    private val range = Regex("(?<=\\d)(?:[-–]|\\s[-–—]\\s)(?=\\d)")
    private val unit = Regex("\\b(\\d+(?:[.,]\\d+)?)\\s?(km/h|km|kg|cm|mm|mg|ml|GB|MB|KB|TB|GHz|MHz|kHz|Hz|mph|kph|lbs?|oz|ft|mi|ms)\\b")
    private val fraction = Regex("(?<![\\d/])\\b(1|3)/(2|4)\\b(?![\\d/])")
    private val number = Regex("#(?=\\d)")
    private val approx = Regex("~(?=\\d)")
    private val spaces = Regex("[ \\t]+")
    private val multiPunct = Regex("([!?])[!?]+")

    private val TITLES = mapOf(
        "Mr" to "Mister", "Mrs" to "Missus", "Ms" to "Miz", "Dr" to "Doctor", "Prof" to "Professor", "Sr" to "Senior", "Jr" to "Junior",
        "Mt" to "Mount", "Gen" to "General", "Capt" to "Captain", "Lt" to "Lieutenant", "Sgt" to "Sergeant", "Rev" to "Reverend",
    )
    private val title = Regex("\\b(Mr|Mrs|Ms|Dr|Prof|Sr|Jr|Mt|Gen|Capt|Lt|Sgt|Rev)\\.(?=\\s|$)")
    private val street = Regex("\\b([A-Z][a-z]+)\\s+St\\.(?=\\s|$)")
    private val saint = Regex("\\bSt\\.\\s+(?=[A-Z])")
    private val ABBREVIATIONS = listOf(
        Regex("\\be\\.g\\.(?=\\s|,|$)", RegexOption.IGNORE_CASE) to "for example",
        Regex("\\bi\\.e\\.(?=\\s|,|$)", RegexOption.IGNORE_CASE) to "that is",
        Regex("\\betc\\.(?=\\s|,|$)") to "et cetera",
        Regex("\\bvs\\.?(?=\\s)") to "versus",
        Regex("\\bapprox\\.(?=\\s)") to "approximately",
        Regex("\\bNo\\.\\s?(?=\\d)") to "number ",
        Regex("\\bAve\\.(?=\\s|$)") to "Avenue",
        Regex("\\bRd\\.(?=\\s|$)") to "Road",
        Regex("\\bBlvd\\.(?=\\s|$)") to "Boulevard",
        Regex("\\bInc\\.(?=\\s|$)") to "Incorporated",
        Regex("\\bLtd\\.(?=\\s|$)") to "Limited",
    )
    private val CAN_END = setOf("et cetera", "Incorporated", "Limited", "Avenue", "Road", "Boulevard")
    private val UNITS = mapOf(
        "km/h" to ("kilometer per hour" to "kilometers per hour"), "km" to ("kilometer" to "kilometers"), "kg" to ("kilogram" to "kilograms"),
        "cm" to ("centimeter" to "centimeters"), "mm" to ("millimeter" to "millimeters"), "mg" to ("milligram" to "milligrams"),
        "ml" to ("milliliter" to "milliliters"), "GB" to ("gigabyte" to "gigabytes"), "MB" to ("megabyte" to "megabytes"),
        "KB" to ("kilobyte" to "kilobytes"), "TB" to ("terabyte" to "terabytes"), "GHz" to ("gigahertz" to "gigahertz"),
        "MHz" to ("megahertz" to "megahertz"), "kHz" to ("kilohertz" to "kilohertz"), "Hz" to ("hertz" to "hertz"),
        "mph" to ("mile per hour" to "miles per hour"), "kph" to ("kilometer per hour" to "kilometers per hour"),
        "lb" to ("pound" to "pounds"), "lbs" to ("pound" to "pounds"), "oz" to ("ounce" to "ounces"), "ft" to ("foot" to "feet"),
        "mi" to ("mile" to "miles"), "ms" to ("millisecond" to "milliseconds"),
    )
    private val FRACTIONS = mapOf("1/2" to "one half", "1/4" to "one quarter", "3/4" to "three quarters")
    private val SYMBOLS = mapOf(
        '½' to " and a half", '¼' to " and a quarter", '¾' to " and three quarters", '×' to " times ", '÷' to " divided by ",
        '=' to " equals ", '≈' to " approximately ", '≠' to " is not ", '≤' to " at most ", '≥' to " at least ", '→' to " to ",
        '←' to " from ", '°' to " degrees", '‰' to " per mille", '§' to " section ", '©' to " copyright ", '™' to "", '®' to "",
        '•' to ", ", '|' to ", ", '_' to " ", '*' to " ", '#' to " ", '`' to "", '^' to " ", '<' to " less than ", '>' to " greater than ",
    )

    fun normalize(input: String): String {
        var s = input.replace("\r\n", "\n").replace("...", "…").replace('’', '\'').replace('‘', '\'')
        s = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                // Emoji and other astral symbols carry no speech.
                if (c.isHighSurrogate()) {
                    i += 2
                    continue
                }
                if ((c.category == CharCategory.OTHER_SYMBOL && c !in SYMBOLS) || c == '\uFE0F' || c == '\u200D') {
                    i++
                    continue
                }
                append(c)
                i++
            }
        }
        s = email.replace(s) { m -> m.groupValues[1].replace(".", " dot ") + " at " + spokenDomain(m.groupValues[2]) }
        s = url.replace(s) { m ->
            val host = m.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
            val tail = m.value.substring(host.length)
            spokenDomain(host.substringAfter("://").removePrefix("www.").removePrefix("WWW.").substringBefore('/').substringBefore('?')) + tail
        }
        s = domain.replace(s) { m -> spokenDomain(m.value) }
        s = isoDate.replace(s) { m -> date(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[1]) ?: m.value }
        s = slashDate.replace(s) { m -> date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]) ?: m.value }
        s = monthAbbreviation.replace(s) { m -> MONTHS[MONTH_ABBREVIATIONS.getValue(m.groupValues[1].lowercase())] + " " }
        s = monthDay.replace(s) { m -> m.groupValues[1] + " " + ordinal(m.groupValues[2].toInt()) }
        s = time.replace(s) { m -> clockTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]) ?: m.value }
        s = clock.replace(s) { m -> m.groupValues[1] + " " + m.groupValues[2].uppercase() + "M" }
        s = suffixedAmount.replace(s) { m ->
            m.groupValues[1] + " " + when (m.groupValues[2]) {
                "K", "k" -> "thousand"
                "M", "mn" -> "million"
                else -> "billion"
            }
        }
        s = trailingEuro.replace(s) { m -> "€" + m.groupValues[1] }
        s = otherCurrency.replace(s) { m ->
            m.groupValues[2] + " " + when (m.groupValues[1]) {
                "¥" -> "yen"
                "₹" -> "rupees"
                "₩" -> "won"
                else -> "rubles"
            }
        }
        s = degrees.replace(s) { m -> m.groupValues[1] + " degrees " + if (m.groupValues[2] == "C") "Celsius" else "Fahrenheit" }
        s = unit.replace(s) { m ->
            val (one, many) = UNITS.getValue(m.groupValues[2])
            m.groupValues[1] + " " + if (m.groupValues[1] == "1") one else many
        }
        s = fraction.replace(s) { m -> FRACTIONS.getValue(m.value) }
        s = range.replace(s, " to ")
        s = number.replace(s, "number ")
        s = approx.replace(s, "about ")
        s = street.replace(s) { m -> m.groupValues[1] + " Street" + endingPeriod(s, m.range.last + 1) }
        s = saint.replace(s, "Saint ")
        s = title.replace(s) { m -> TITLES.getValue(m.groupValues[1]) }
        for ((re, words) in ABBREVIATIONS) {
            val text = s
            s = re.replace(text) { m -> words + if (words in CAN_END) endingPeriod(text, m.range.last + 1) else "" }
        }
        s = buildString { for (c in s) append(SYMBOLS[c] ?: c) }
        s = multiPunct.replace(s, "$1")
        return s.split('\n').joinToString("\n") { spaces.replace(it, " ").trim() }.trim()
    }

    /** "." when an abbreviation's full stop at [end] also ends the sentence (end of line, or a capital follows). */
    private fun endingPeriod(text: String, end: Int): String {
        val rest = text.substring(end)
        val next = rest.trimStart(' ', '\t').firstOrNull()
        return if (next == null || next == '\n' || (rest.startsWith(" ") && next.isUpperCase())) "." else ""
    }

    private fun spokenDomain(host: String): String = host.split('.').filter { it.isNotEmpty() }.joinToString(" dot ") { part ->
        // Short parts nobody pronounces as words are spelled (`io`, `ai`, `uk`).
        if (part.length <= 2 || part.lowercase() == "www") part.uppercase() else part
    }

    private fun ordinal(day: Int): String = day.toString() + when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }

    private fun date(month: Int, day: Int, year: String): String? {
        if (month !in 1..12 || day !in 1..31) return null
        return "${MONTHS[month - 1]} ${ordinal(day)}, $year"
    }

    private fun clockTime(hour: Int, minute: Int, meridiem: String): String? {
        if (hour > 24 || minute > 59) return null
        val suffix = if (meridiem.isEmpty()) "" else " " + meridiem.uppercase() + "M"
        val minutes = when {
            minute == 0 -> if (meridiem.isEmpty() && hour <= 12) " o'clock" else if (meridiem.isEmpty()) " hundred" else ""
            minute < 10 -> " oh $minute"
            else -> " $minute"
        }
        return "$hour$minutes$suffix"
    }
}
