package dev.wckdboy.autobot.providers.remote

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pasted keys never crash header building (OkHttp rejects non-ASCII and control characters). */
class ApiKeysTest {
    @Test
    fun cleansWhatCopyPasteAdds() {
        assertEquals("sk-abc123", ApiKeys.clean("  sk-abc123\n"))
        assertEquals("sk-abc123", ApiKeys.clean("“sk-abc123”"))
        assertEquals("sk-abc123", ApiKeys.clean("sk-​abc﻿123"))
        assertEquals("sk-abc123", ApiKeys.clean("'sk-abc\r\n123'"))
    }

    @Test
    fun explainsWhatCannotBeSent() {
        assertNull(ApiKeys.problem("sk-abc123"))
        assertNotNull(ApiKeys.problem("sk abc"))
        assertNotNull(ApiKeys.problem("sk-ключ"))
        assertNull(ApiKeys.usable("sk-ключ"))
        assertNull(ApiKeys.usable("   "))
    }

    @Test
    fun everyUsableKeyFitsInAHeader() {
        val inputs = listOf("sk-1\n", " sk-2 ", "‘sk-3’", "sk-4\u0000", "sk-5 ", "sk-😀")
        for (raw in inputs) {
            val key = ApiKeys.usable(raw) ?: continue
            Request.Builder().url("https://example.com").header("Authorization", "Bearer $key").build()
        }
    }
}
