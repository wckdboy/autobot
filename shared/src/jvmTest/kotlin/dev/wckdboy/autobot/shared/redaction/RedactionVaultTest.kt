package dev.wckdboy.autobot.shared.redaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionVaultTest {

    @Test
    fun sameValueSamePlaceholderAcrossTurns() {
        val vault = RedactionVault()
        val first = vault.redact("my sister Hannah: hannah@posteo.de, +49 171 2345678")
        assertEquals("my sister [PERSON_1]: [EMAIL_1], [PHONE_1]", first.text)
        assertEquals(setOf("[PERSON_1]", "[EMAIL_1]", "[PHONE_1]"), first.placeholders)

        // A later turn: same values keep their placeholders, new ones count up per kind.
        val second = vault.redact("Ask Hannah (HANNAH@posteo.de) and lea@mail.com, call +49 1712345678")
        assertEquals("Ask [PERSON_1] ([EMAIL_1]) and [EMAIL_2], call [PHONE_1]", second.text)
        assertEquals(4, vault.size)
    }

    @Test
    fun aKnownValueIsReplacedWhereDetectionAloneWouldMissIt() {
        val vault = RedactionVault()
        vault.redact("my colleague is named Quentin")
        // "Quentin" opening a sentence, with no cue: still the same person.
        assertEquals("[PERSON_1] replied: ok", vault.redact("Quentin replied: ok").text)
        // Not inside another word.
        assertEquals("Quentins", vault.redact("Quentins").text)
    }

    @Test
    fun partsOfAFullNameGetTheirOwnPlaceholders() {
        val vault = RedactionVault()
        assertEquals("my boss, [PERSON_1], said no", vault.redact("my boss, Grace Okafor, said no").text)
        assertEquals("[PERSON_2] agreed later; [PERSON_1] too", vault.redact("Okafor agreed later; Grace Okafor too").text)
    }

    @Test
    fun restoreTurnsPlaceholdersBack() {
        val vault = RedactionVault()
        vault.redact("write to hannah@posteo.de for my sister Hannah")
        assertEquals("Sure, I'll mail hannah@posteo.de (Hannah).", vault.restore("Sure, I'll mail [EMAIL_1] ([PERSON_1])."))
        // Unknown placeholders and look-alikes stay as they are.
        assertEquals("[PERSON_9], [EMAIL_1x], [email_1], array[0]", vault.restore("[PERSON_9], [EMAIL_1x], [email_1], array[0]"))
        // URL-encoded placeholders (in a link the model built) come back URL-encoded.
        assertEquals("https://x.org/?to=hannah%40posteo.de", vault.restore("https://x.org/?to=%5BEMAIL_1%5D"))
    }

    @Test
    fun restoreForJsonEscapesTheValue() {
        val vault = RedactionVault()
        vault.addTerms(listOf(PiiKind.PERSON to "Dwayne \"The Rock\""))
        vault.redact("ask Dwayne \"The Rock\" now")
        val json = vault.restore("""{"to":"[PERSON_1]"}""", json = true)
        assertEquals("Dwayne \"The Rock\"", Json.parseToJsonElement(json).jsonObject["to"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolArgumentsAreRedactedPerStringValue() {
        val vault = RedactionVault()
        val args = """{"path":"notes/mail.txt","content":"Hi Lukas,\nmy number is +49 171 2345678","count":3}"""
        val out = vault.redactJson(args)
        val obj = Json.parseToJsonElement(out.text).jsonObject
        assertEquals("notes/mail.txt", obj["path"]!!.jsonPrimitive.content)
        assertEquals("Hi [PERSON_1],\nmy number is [PHONE_1]", obj["content"]!!.jsonPrimitive.content)
        assertEquals("3", obj["count"]!!.jsonPrimitive.content)
        assertEquals(setOf("[PERSON_1]", "[PHONE_1]"), out.placeholders)
        // Nothing personal: the arguments go out byte for byte.
        val plain = """{"path": "a.txt" ,"content":"hello"}"""
        assertEquals(plain, vault.redactJson(plain).text)
        // Not JSON: redacted as text.
        assertEquals("to [EMAIL_1]", vault.redactJson("to lea@mail.com").text)
    }

    @Test
    fun memoryTermsGetANumberOnlyWhenTheyOccur() {
        val vault = RedactionVault()
        vault.addTerms(listOf(PiiKind.PRIVATE to "Siemens", PiiKind.PRIVATE to "8812345", PiiKind.PERSON to "Rex"))
        assertEquals(0, vault.size)
        assertEquals("I work at [PRIVATE_1].", vault.redact("I work at Siemens.").text)
        assertEquals("customer [PRIVATE_2], ref 88123456", vault.redact("customer 8812345, ref 88123456").text)
        assertEquals(2, vault.size)
        // A memory word inside code or a link stays: it is likely code, not a fact about the user.
        assertEquals("`Siemens.connect()` and https://siemens.com/Siemens", vault.redact("`Siemens.connect()` and https://siemens.com/Siemens").text)
        // Numbers count anywhere, also written with spaces.
        assertEquals("id [PRIVATE_2]", vault.redact("id 881 2345").text)
    }

    @Test
    fun codeAndLinksKeepTheirNamesButNotTheirEmails() {
        val vault = RedactionVault()
        val text = "my friend Daniel wrote:\n```kotlin\nval author = \"Daniel\" // daniel@posteo.de\n```\nsee https://github.com/Daniel/app"
        val out = vault.redact(text).text
        assertTrue(out, out.startsWith("my friend [PERSON_1] wrote:"))
        assertTrue(out, "val author = \"Daniel\" // [EMAIL_1]" in out)
        assertTrue(out, out.endsWith("https://github.com/Daniel/app"))
    }

    @Test
    fun falsePositiveGuards() {
        val vault = RedactionVault()
        val text = """
            Upgrade to Kotlin 2.1.20 and AGP 8.7.3, then run `./gradlew :shared:jvmTest` on 2026-10-17.
            Server: http://192.168.1.20:8080/v1/chat?session=0171234567 — build 1.6.6 (18), issue #4821.
            ```json
            {"card": "4242424242424242", "phone": "0171 2345678", "name": "Hannah"}
            ```
        """.trimIndent()
        assertEquals(text, vault.redact(text).text)
        assertEquals(0, vault.size)
    }

    @Test
    fun placeholdersInTheInputAreLeftAlone() {
        val vault = RedactionVault()
        assertEquals("[PERSON_1] and [EMAIL_3]", vault.redact("[PERSON_1] and [EMAIL_3]").text)
        // They stand for themselves, and hide nothing.
        assertEquals("[PERSON_1] and [EMAIL_3]", vault.restore("[PERSON_1] and [EMAIL_3]"))
        assertNull(vault.kindOf("[PERSON_1]"))
    }

    @Test
    fun aPlaceholderTheUserWroteNeverStandsForSomeoneElse() {
        // Written first: its number is reserved, so a real name gets another one.
        val vault = RedactionVault()
        val template = vault.redact("Fill in this template: Dear [PERSON_1], thanks!").text
        assertEquals("Fill in this template: Dear [PERSON_1], thanks!", template)
        assertEquals("my sister [PERSON_2] wants it", vault.redact("my sister Hannah wants it").text)
        assertEquals("Dear [PERSON_1], Hannah says hi", vault.restore("Dear [PERSON_1], [PERSON_2] says hi"))
        assertEquals(PiiKind.PERSON, vault.kindOf("[PERSON_2]"))

        // Written after a real name took the number: the text gets its own placeholder.
        val later = RedactionVault()
        assertEquals("my sister [PERSON_1] wants it", later.redact("my sister Hannah wants it").text)
        val pasted = later.redact("Use the form 'Dear [PERSON_1],' for x[PERSON_1].").text
        assertFalse(pasted, "[PERSON_1]" in pasted)
        assertEquals("Use the form 'Dear [PERSON_1],' for x[PERSON_1].", later.restore(pasted))
        assertEquals("Hannah", later.restore("[PERSON_1]"))
        assertNull(later.kindOf(RedactionVault.PLACEHOLDER.find(pasted)!!.value))
    }

    @Test
    fun cachedResultsFollowNewVocabulary() {
        val vault = RedactionVault()
        val history = "Quentin said hi"
        assertEquals(history, vault.redact(history).text)
        vault.redact("a man named Quentin")
        // The same text again: redone, now that Quentin is known.
        assertEquals("[PERSON_1] said hi", vault.redact(history).text)
    }

    @Test
    fun kindsAndValues() {
        val vault = RedactionVault()
        vault.redact("IBAN DE89 3704 0044 0532 0130 00, card 4111 1111 1111 1111")
        assertEquals(PiiKind.IBAN, vault.kindOf("[IBAN_1]"))
        assertEquals("4111 1111 1111 1111", vault.valueOf("[CARD_1]"))
        // The same IBAN without spaces is the same IBAN.
        assertEquals("[IBAN_1]", vault.redact("DE89370400440532013000").text)
        assertFalse(vault.redact("4111111111111111").text.contains("4111"))
    }

    @Test
    fun receiptLabelsInAFixedOrder() {
        val rows = dev.wckdboy.autobot.shared.session.Receipts.redactions(mapOf("EMAIL" to 2, "FUTURE" to 1, "PERSON" to 1, "PHONE" to 0))
        assertEquals(listOf("1 name", "2 emails", "1 future"), rows.map { it.label })
        assertTrue(dev.wckdboy.autobot.shared.session.Receipts.redactions(null).isEmpty())
        val receipt = dev.wckdboy.autobot.shared.session.Receipts.summarize(
            dev.wckdboy.autobot.agent.core.session.SessionEvent.TurnReceipt(turn = 1, network = emptyList(), redacted = emptyMap()),
        )
        assertTrue(receipt.redacted)
        assertEquals("on device", receipt.line)
    }
}
