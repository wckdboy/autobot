package dev.wckdboy.autobot.shared.vault

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

class VaultWordsTest {
    @Test
    fun anEmptyVaultNeverPointsAtAPlusThatIsNotThere() {
        // The "+" shows only with an embedding model; the empty line must not ask for it otherwise.
        assertFalse("+" in VaultWords.empty(hasModel = false))
        assertFalse("+" in VaultWords.empty(hasModel = true))
        assertEquals("No files yet. Files need an embedding model.", VaultWords.empty(hasModel = false))
    }

    @Test
    fun countsReadAsWords() {
        assertEquals("1 document uses another embedding model.", VaultWords.stale(1))
        assertEquals("3 documents use another embedding model.", VaultWords.stale(3))
        assertEquals("Documents · 2 · 40 / ${VaultRules.MAX_CHUNKS} passages", VaultWords.count(2, 40))
    }
}
