package dev.wckdboy.autobot.shared.tts

/**
 * English number words as Python's `num2words` writes them (cardinal, ordinal, year, decimal),
 * which misaki reads word by word. "and", commas and hyphens are kept as `num2words` has them;
 * the reader splits on them.
 */
internal object EnglishNumbers {
    private val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = listOf("", "thousand", "million", "billion", "trillion", "quadrillion", "quintillion")
    private val ords = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "four" to "fourth", "five" to "fifth", "six" to "sixth",
        "seven" to "seventh", "eight" to "eighth", "nine" to "ninth", "ten" to "tenth", "eleven" to "eleventh", "twelve" to "twelfth",
    )

    private fun belowHundred(n: Int): String = if (n < 20) ones[n] else tens[n / 10] + if (n % 10 != 0) "-" + ones[n % 10] else ""

    private fun belowThousand(n: Int): String = when {
        n < 100 -> belowHundred(n)
        n % 100 == 0 -> ones[n / 100] + " hundred"
        else -> ones[n / 100] + " hundred and " + belowHundred(n % 100)
    }

    fun cardinal(value: Long): String {
        if (value < 0) return "minus " + cardinal(-value)
        if (value == 0L) return "zero"
        val groups = mutableListOf<Int>()
        var v = value
        while (v > 0) {
            groups += (v % 1000).toInt()
            v /= 1000
        }
        val parts = mutableListOf<String>()
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) continue
            parts += belowThousand(g) + if (i > 0) " " + scales[i] else ""
        }
        // num2words joins a trailing group under a hundred with "and", others with ", ".
        val last = groups[0]
        return if (parts.size > 1 && last in 1..99) parts.dropLast(1).joinToString(", ") + " and " + parts.last() else parts.joinToString(", ")
    }

    fun ordinal(value: Long): String {
        val words = cardinal(value).split(' ').toMutableList()
        val pieces = words.last().split('-').toMutableList()
        val last = pieces.last()
        pieces[pieces.size - 1] = ords[last] ?: ((if (last.endsWith("y")) last.dropLast(1) + "ie" else last) + "th")
        words[words.size - 1] = pieces.joinToString("-")
        return words.joinToString(" ")
    }

    fun year(value: Long): String {
        val high = value / 100
        val low = value % 100
        if (high == 0L || (high % 10 == 0L && low < 10) || high >= 100) return cardinal(value)
        val lowText = when {
            low == 0L -> "hundred"
            low < 10 -> "oh-" + cardinal(low)
            else -> cardinal(low)
        }
        return cardinal(high) + " " + lowText
    }

    /** A decimal such as `2.50` (no sign, no thousands separators), as `num2words(float(s))`. */
    fun decimal(s: String): String? {
        val whole = s.substringBefore('.').ifEmpty { "0" }.toLongOrNull() ?: return null
        val fraction = s.substringAfter('.', "").trimEnd('0')
        if (fraction.any { it !in '0'..'9' }) return null
        if (fraction.isEmpty()) return cardinal(whole)
        return cardinal(whole) + " point " + fraction.map { ones[it.digitToInt()] }.joinToString(" ")
    }
}
