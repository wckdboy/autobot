package dev.wckdboy.autobot.shared.cascade

import dev.wckdboy.autobot.shared.redaction.PiiDetector

/**
 * Whether a prompt is safe to send to the cloud without asking: "Auto" escalates only prompts
 * with no [reasons]. Codes, never content.
 */
data class CascadeRisk(val reasons: List<String>) {
    val sensitive: Boolean get() = reasons.isNotEmpty()

    companion object {
        val LOW = CascadeRisk(emptyList())

        /** Names, emails, numbers, addresses (the redaction gateway's detector found something). */
        const val PERSONAL = "personal"
        const val HEALTH = "health"
        const val FINANCE = "finance"

        /** An image is attached to the prompt or earlier in the chat. */
        const val ATTACHMENT = "attachment"

        /** Memory notes or vault passages are on the request, or the prompt names a memory fact. */
        const val MEMORY = "memory"
    }
}

/**
 * The cascade's risk classifier: rules only, on the device, cheap enough for every turn. It
 * errs towards "sensitive": a false alarm costs one tap, a miss sends private data unasked.
 */
object CascadeRiskClassifier {

    /**
     * @param prompt the user's message.
     * @param images whether the request carries an attached image (this prompt or earlier ones).
     * @param privateContext whether a private context (memory recall, vault passages) is on the request.
     * @param privateTerms the user's memory facts (from the taint index); a prompt naming one is sensitive.
     */
    fun classify(
        prompt: String,
        images: Boolean = false,
        privateContext: Boolean = false,
        privateTerms: Collection<String> = emptyList(),
    ): CascadeRisk {
        val reasons = ArrayList<String>()
        if (runCatching { PiiDetector.detect(prompt) }.getOrDefault(emptyList()).isNotEmpty()) reasons += CascadeRisk.PERSONAL
        val words = words(prompt)
        if (matches(words, prompt, HEALTH_WORDS, HEALTH_PHRASES)) reasons += CascadeRisk.HEALTH
        if (matches(words, prompt, FINANCE_WORDS, FINANCE_PHRASES)) reasons += CascadeRisk.FINANCE
        if (images) reasons += CascadeRisk.ATTACHMENT
        val lower = prompt.lowercase()
        if (privateContext || privateTerms.any { it.length >= 3 && lower.contains(it.lowercase()) }) reasons += CascadeRisk.MEMORY
        return CascadeRisk(reasons)
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }.map { it.trim('\'') }.toSet()

    private fun matches(words: Set<String>, text: String, single: Set<String>, phrases: List<String>): Boolean {
        if (words.any { w -> w in single || STEMS.any { (stem, set) -> set === single && w.startsWith(stem) } }) return true
        val lower = text.lowercase()
        return phrases.any { lower.contains(it) }
    }

    private val HEALTH_WORDS = setOf(
        "symptom", "symptoms", "diagnosis", "diagnosed", "doctor", "doctors", "physician", "therapist", "therapy", "medication",
        "medications", "medicine", "prescription", "prescribed", "dose", "dosage", "pill", "pills", "pregnant", "pregnancy",
        "miscarriage", "abortion", "std", "sti", "hiv", "cancer", "tumor", "tumour", "diabetes", "insulin", "depression",
        "depressed", "anxiety", "suicidal", "suicide", "self-harm", "adhd", "autism", "bipolar", "schizophrenia", "psychiatrist",
        "rash", "fever", "infection", "biopsy", "surgery", "allergy", "allergic", "disease", "illness", "pain", "bleeding",
        "lump", "migraine", "antidepressant", "antibiotics", "vaccine", "blood", "cholesterol", "rehab", "addiction", "overdose",
        // German
        "arzt", "ärztin", "krankheit", "schmerzen", "medikament", "medikamente", "schwanger", "diagnose", "therapie",
    )
    private val HEALTH_PHRASES = listOf("blood pressure", "mental health", "side effect", "heart rate", "my health", "test results")

    private val FINANCE_WORDS = setOf(
        "salary", "income", "debt", "debts", "loan", "loans", "mortgage", "bankrupt", "bankruptcy", "tax", "taxes", "irs",
        "invoice", "pension", "inheritance", "credit", "overdraft", "payslip", "paycheck", "savings", "portfolio", "alimony",
        "rent", "bank", "iban", "password", "pin", "ssn",
        // German
        "gehalt", "schulden", "kredit", "steuer", "steuern", "konto", "miete", "rente",
    )
    private val FINANCE_PHRASES = listOf("net worth", "credit score", "bank account", "account number", "social security", "how much i earn", "my money")

    /** Word stems that count wherever they start a word ("diagnos" in "diagnosing"). */
    private val STEMS: List<Pair<String, Set<String>>> = listOf(
        "diagnos" to HEALTH_WORDS, "medicat" to HEALTH_WORDS, "prescri" to HEALTH_WORDS, "psychiatr" to HEALTH_WORDS,
        "mortgag" to FINANCE_WORDS, "bankrupt" to FINANCE_WORDS,
    )
}
