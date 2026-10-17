package dev.wckdboy.autobot.shared.redaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiDetectorTest {

    private fun found(text: String, names: Boolean = true) = PiiDetector.detect(text, names).map { it.kind to it.value }

    private fun kinds(text: String) = PiiDetector.detect(text).map { it.kind }

    private fun assertFinds(text: String, kind: PiiKind, value: String) {
        val all = found(text)
        assertTrue("expected $kind '$value' in '$text', got $all", kind to value in all)
    }

    private fun assertNothing(text: String) {
        val all = found(text)
        assertTrue("expected nothing in '$text', got $all", all.isEmpty())
    }

    // --- emails ---------------------------------------------------------------------------

    @Test
    fun emails() {
        assertFinds("write to hannah.schmidt@posteo.de today", PiiKind.EMAIL, "hannah.schmidt@posteo.de")
        assertFinds("Mail: Jürgen+news@müller-bau.example.co.uk.", PiiKind.EMAIL, "Jürgen+news@müller-bau.example.co.uk")
        assertFinds("(lea_w@mail.com)", PiiKind.EMAIL, "lea_w@mail.com")
    }

    @Test
    fun emailLookalikesAreNotEmails() {
        assertNothing("clone git@github.com:wckdboy/autobot.git")
        assertNothing("use icon@2x.png and logo@3x.webp")
        assertNothing("send it to someone@example.com or test@example.org")
        assertNothing("npm i @scope/pkg@1.2.3")
        assertNothing("noreply@github.com sent a notification")
    }

    @Test
    fun emailsCountEvenInCode() {
        assertFinds("```\nauthor = \"lea@posteo.de\"\n```", PiiKind.EMAIL, "lea@posteo.de")
    }

    // --- IBAN / cards / accounts ------------------------------------------------------------

    @Test
    fun ibansAreValidated() {
        assertFinds("IBAN DE89 3704 0044 0532 0130 00, thanks", PiiKind.IBAN, "DE89 3704 0044 0532 0130 00")
        assertFinds("pay GB82WEST12345698765432 now", PiiKind.IBAN, "GB82WEST12345698765432")
        assertFinds("NL91ABNA0417164300", PiiKind.IBAN, "NL91ABNA0417164300")
        // Wrong check digits, unknown country, too short.
        assertFalse(PiiKind.IBAN in kinds("DE88 3704 0044 0532 0130 00"))
        assertFalse(PiiKind.IBAN in kinds("ZZ89370400440532013000"))
        assertFalse(PiiKind.IBAN in kinds("DE89 3704 0044"))
    }

    @Test
    fun ibanValidator() {
        assertTrue(Iban.valid("DE89370400440532013000"))
        assertTrue(Iban.valid("fr14 2004 1010 0505 0001 3m02 606"))
        assertFalse(Iban.valid("DE89370400440532013001"))
        assertFalse(Iban.valid(""))
    }

    @Test
    fun cardsNeedLuhnAndACardPrefix() {
        assertFinds("card 4111 1111 1111 1111 exp 12/27", PiiKind.CARD, "4111 1111 1111 1111")
        assertFinds("5500-0000-0000-0004", PiiKind.CARD, "5500-0000-0000-0004")
        assertFinds("amex 378282246310005.", PiiKind.CARD, "378282246310005")
        assertFalse(PiiKind.CARD in kinds("4111 1111 1111 1112"))
        assertFalse(PiiKind.CARD in kinds("order 1234567890123456"))
        assertFalse(PiiKind.CARD in kinds("0000 0000 0000 0000"))
    }

    @Test
    fun luhn() {
        assertTrue(Luhn.valid("79927398713"))
        assertFalse(Luhn.valid("79927398710"))
        assertFalse(Luhn.valid("12a4"))
    }

    @Test
    fun cardsInCodeAreTestData() {
        assertNothing("```kotlin\nval card = \"4242424242424242\"\n```")
        assertNothing("use `4242 4242 4242 4242` in the sandbox")
    }

    @Test
    fun accountNumbersAfterACue() {
        assertFinds("my account number is 12345678", PiiKind.ACCOUNT, "12345678")
        assertFinds("Kundennummer: 4711-0815-22", PiiKind.ACCOUNT, "4711-0815-22")
        assertFinds("passport no. C01X00T47", PiiKind.ACCOUNT, "C01X00T47")
        assertFinds("SSN 078-05-1120", PiiKind.ACCOUNT, "078-05-1120")
        assertFinds("my ssn is 078-05-1120.", PiiKind.ACCOUNT, "078-05-1120")
    }

    @Test
    fun accountCuesNeedANumber() {
        assertNothing("my account was locked yesterday")
        assertNothing("the policy for 12345 users")
        assertNothing("member since 2019")
        assertNothing("customer support 24/7")
    }

    // --- phones -----------------------------------------------------------------------------

    @Test
    fun phones() {
        assertFinds("call +49 171 2345678 later", PiiKind.PHONE, "+49 171 2345678")
        assertFinds("+1 (415) 555-0132", PiiKind.PHONE, "+1 (415) 555-0132")
        assertFinds("office (415) 555-0132", PiiKind.PHONE, "(415) 555-0132")
        assertFinds("cell 415-555-0132.", PiiKind.PHONE, "415-555-0132")
        assertFinds("ruf an: 0171 2345678", PiiKind.PHONE, "0171 2345678")
        assertFinds("Festnetz 030/12345678", PiiKind.PHONE, "030/12345678")
        assertFinds("01712345678", PiiKind.PHONE, "01712345678")
        assertFinds("phone: 555 1234", PiiKind.PHONE, "555 1234")
        assertFinds("+44 20 7946 0958", PiiKind.PHONE, "+44 20 7946 0958")
    }

    @Test
    fun numbersThatAreNotPhones() {
        assertNothing("released on 2026-10-17 at 14:30")
        assertNothing("version 1.2.3 and 10.4.12.1")
        assertNothing("ping 192.168.178.20")
        assertNothing("it costs 1,299.00 EUR, 3 x 5 = 15")
        assertNothing("Python 3.12.1, Kotlin 2.1.20, API 35")
        assertNothing("between 1990 and 2024, about 25000 people")
        assertNothing("17.10.2026 or 10/17/2026")
        assertNothing("the ratio was 0.75 and 0.123456789")
        assertNothing("issue #12345 and PR 6789")
    }

    @Test
    fun phonesInLinksAndCodeAreLeftAlone() {
        assertNothing("see https://example.org/item/0171-2345678 for details")
        assertNothing("```\nconst PORT = \"0171 2345678\"\n```")
    }

    // --- addresses --------------------------------------------------------------------------

    @Test
    fun addresses() {
        assertFinds("I live at 221B Baker Street, London.", PiiKind.ADDRESS, "221B Baker Street, London")
        assertFinds("1600 Pennsylvania Ave NW, Washington, DC 20500", PiiKind.ADDRESS, "1600 Pennsylvania Ave NW, Washington, DC 20500")
        assertFinds("Lieferung an Hauptstraße 12a, 10115 Berlin bitte", PiiKind.ADDRESS, "Hauptstraße 12a, 10115 Berlin")
        assertFinds("wohnt in der Berliner Straße 5", PiiKind.ADDRESS, "Berliner Straße 5")
        assertFinds("12 rue de la Paix, 75002 Paris", PiiKind.ADDRESS, "12 rue de la Paix, 75002 Paris")
        assertFinds("en Calle Mayor 5", PiiKind.ADDRESS, "Calle Mayor 5")
        assertFinds("Via Roma 10", PiiKind.ADDRESS, "Via Roma 10")
    }

    @Test
    fun notAddresses() {
        assertNothing("we shipped 3 new features this week")
        assertNothing("Spring 2026 release")
        assertNothing("turn left on the main road after 2 km")
    }

    // --- names ------------------------------------------------------------------------------

    @Test
    fun namesFromCues() {
        assertFinds("my sister Hannah wants to know", PiiKind.PERSON, "Hannah")
        assertFinds("My boss, Grace Okafor, said no", PiiKind.PERSON, "Grace Okafor")
        assertFinds("a guy named Quentin", PiiKind.PERSON, "Quentin")
        assertFinds("Ask Dr. Weber about it", PiiKind.PERSON, "Weber")
        assertFinds("Frau Müller-Lüdenscheidt kommt", PiiKind.PERSON, "Müller-Lüdenscheidt")
        assertFinds("Hi Lukas,\nthanks for the files", PiiKind.PERSON, "Lukas")
        assertFinds("Dear Ms Abernathy,", PiiKind.PERSON, "Abernathy")
        assertFinds("See you soon.\n\nBest,\nTobias Kraemer", PiiKind.PERSON, "Tobias Kraemer")
        assertFinds("I'm Hannah, nice to meet you", PiiKind.PERSON, "Hannah")
        assertFinds("meine Freundin Ayşe kommt", PiiKind.PERSON, "Ayşe")
    }

    @Test
    fun namesFromTheList() {
        assertFinds("I met Hannah Schmidt yesterday", PiiKind.PERSON, "Hannah Schmidt")
        assertFinds("Hannah called again.", PiiKind.PERSON, "Hannah")
        assertFinds("ask Ludwig van Beethoven", PiiKind.PERSON, "Ludwig van Beethoven")
        assertFinds("Hannah's car", PiiKind.PERSON, "Hannah")
        assertFinds("lunch with Mark Jensen", PiiKind.PERSON, "Mark Jensen")
    }

    @Test
    fun wordsThatAreNotNames() {
        assertNothing("I will mark it as done in May.")
        assertNothing("Max tokens is 4096")
        assertNothing("I'm Sorry, this is Important")
        assertNothing("Dear Team,")
        assertNothing("The grace period ends in June")
        assertNothing("Hope this helps! Will do.")
        assertNothing("Use Ruby or Julia for that")
        assertNothing("Jan 5 meeting at 10:00")
        assertNothing("Austin, Texas and Paris, France")
    }

    @Test
    fun namesAreNotLookedForInCodeOrLinks() {
        assertNothing("```python\nclass Hannah:\n    pass\n```")
        assertNothing("call `Daniel.run()` first")
        assertNothing("https://github.com/Hannah/project")
    }

    @Test
    fun namesCanBeSwitchedOff() {
        assertTrue(found("my sister Hannah, hannah@posteo.de", names = false).none { it.first == PiiKind.PERSON })
        assertTrue(found("my sister Hannah, hannah@posteo.de", names = false).any { it.first == PiiKind.EMAIL })
    }

    // --- mixed ------------------------------------------------------------------------------

    @Test
    fun spansDoNotOverlapAndComeInOrder() {
        val text = "Hannah Schmidt (hannah@posteo.de, +49 171 2345678) lives at Hauptstraße 12, 10115 Berlin; IBAN DE89370400440532013000."
        val spans = PiiDetector.detect(text)
        assertEquals(listOf(PiiKind.PERSON, PiiKind.EMAIL, PiiKind.PHONE, PiiKind.ADDRESS, PiiKind.IBAN), spans.map { it.kind })
        spans.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
        spans.forEach { assertEquals(it.value, text.substring(it.start, it.end)) }
    }

    @Test
    fun technicalProseIsLeftAlone() {
        assertNothing(
            """
            The build failed on Gradle 8.10.2 with Kotlin 2.1.20. Run ./gradlew assembleDebug, then open
            http://localhost:8080/api/v1/users?id=1234567890 and check the logs at 2026-10-17T14:30:00Z.
            Commit a1b2c3d4 fixed issue #4821; the SHA-256 is 9a1378acf9587885e7920355558c85a1ec0e8930.
            """.trimIndent(),
        )
    }

    @Test
    fun emptyAndPlainTexts() {
        assertNothing("")
        assertNothing("how do I make a sourdough starter?")
        assertNothing("[PERSON_1] asked about [EMAIL_2]")
    }
}
