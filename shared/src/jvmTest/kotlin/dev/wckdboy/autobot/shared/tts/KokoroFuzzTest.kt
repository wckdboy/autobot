package dev.wckdboy.autobot.shared.tts

import dev.wckdboy.autobot.shared.voice.VoiceText
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speech frontend runs on whatever the model wrote, called from Swift where an exception
 * ends the app: it must be total. Fixed nasty cases plus seeded random text.
 */
class KokoroFuzzTest {
    private fun resource(name: String) = javaClass.getResource("/tts/$name")!!.readText()

    private val frontend by lazy {
        KokoroFrontend.parse(
            resource("kokoro_config.json"), resource("us_gold.json"), resource("us_silver.json"), resource("gb_gold.json"), resource("gb_silver.json"),
        )
    }

    private val nasty = listOf(
        "",
        " ",
        "\n\n\n",
        "123456789012345678901234567890",
        "-123456789012345678901234567890",
        "Order #123456789012345678901234567890 is ready.",
        "It costs $123456789012345678901234567890.99 today.",
        "The 123456789012345678901234567890th time.",
        "9223372036854775808 and -9223372036854775808 and 18446744073709551616",
        "1,234,567,890,123,456,789,012,345 items",
        ".5 .55555555555555555555555 0.000000000000000000001",
        "1.2.3.4.5.6.7.8.9",
        "$$$ €€€ £0.00 ¥ ₹1,00,000",
        "😀😃😄 👩‍👩‍👧‍👦 🇺🇸 🏳️‍🌈",
        "مرحبا بالعالم 123 שלום עולם",
        "Hello ‮olleh‬ world ​‍﻿",
        "\uD83D", // a lone surrogate
        "Ω≈ç√∫˜µ≤≥÷ åß∂ƒ©˙∆˚¬…æ",
        "日本語のテキスト。中文文本！한국어 텍스트？",
        "A.B.C.D. e.g. i.e. etc. vs. Mr. Dr. St. No. 5",
        "'''\"\"\"‘’“”«»",
        "--- ... !!! ??? ;;; ::: ,,,",
        "2024-13-45 99/99/9999 25:99 pm 0:00 am",
        "x".repeat(20_000),
        "word ".repeat(5_000),
        "1".repeat(5_000),
        "3rd 2nd 1st 11th 112th 0th 00th 1000000000000000000000th",
        "USED used Used in IN the THE to TO",
        "https://example.com/a/b?c=d&e=f#g mail@example.com www.x.io",
        "ÀÉÎÕÜ àéîõü ÆØÅ æøå ẞß Ł ł",
    )

    private fun randomText(random: Random): String {
        val pools = listOf(
            ('a'..'z').joinToString(""), ('A'..'Z').joinToString(""), "0123456789", " .,!?;:'\"-–—…()[]$€£#%&/@\n\t",
            "αβγδεζηθ", "абвгдеж", "日本語中文한국어", "مرحبا", "שלום", "​‍‮﻿",
        )
        val length = random.nextInt(0, 400)
        return buildString {
            repeat(length) {
                when (random.nextInt(10)) {
                    0 -> appendCodePoint(random.nextInt(0x1F300, 0x1FAFF))
                    1 -> append(random.nextInt(0, 0xFFFF).toChar())
                    2 -> append("9".repeat(random.nextInt(15, 40)))
                    else -> pools[random.nextInt(pools.size)].let { append(it[random.nextInt(it.length)]) }
                }
            }
        }
    }

    private fun StringBuilder.appendCodePoint(cp: Int) = append(String(Character.toChars(cp)))

    private fun exercise(text: String) {
        for (voice in listOf("af_heart", "bf_emma")) {
            frontend.phonemes(text, voice)
            val chunks = frontend.chunks(text, voice, maxTokens = 64)
            assertTrue(chunks.all { it.ids.size <= 64 && it.styleIndex in 0 until KokoroFrontend.VOICE_ROWS })
            frontend.chunks(text, voice, maxTokens = 0) // too small a limit is raised, not rejected
        }
        VoiceText.chunks(VoiceText.speakable(text))
        VoiceText.chunks(text, maxChars = 0)
        VoiceText.transcript(text)
    }

    @Test
    fun nastyInputsNeverThrow() {
        nasty.forEach { text ->
            try {
                exercise(text)
            } catch (t: Throwable) {
                throw AssertionError("frontend threw on ${text.take(60)}", t)
            }
        }
    }

    @Test
    fun randomUnicodeNeverThrows() {
        val random = Random(20261015)
        repeat(400) {
            val text = randomText(random)
            try {
                exercise(text)
            } catch (t: Throwable) {
                throw AssertionError("frontend threw on ${text.take(80)}", t)
            }
        }
    }

    @Test
    fun hugeNumbersAreReadNotDropped() {
        // Past Long: read digit by digit instead of throwing NumberFormatException.
        assertTrue(frontend.phonemes("123456789012345678901234567890").isNotBlank())
        assertEquals("minus nine quintillion, two hundred and twenty-three quadrillion, three hundred and seventy-two trillion, " +
            "thirty-six billion, eight hundred and fifty-four million, seven hundred and seventy-five thousand, eight hundred and eight",
            EnglishNumbers.cardinal(Long.MIN_VALUE))
    }
}
