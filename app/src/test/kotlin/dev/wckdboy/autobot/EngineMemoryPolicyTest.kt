package dev.wckdboy.autobot

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two large models must never be resident at once; memory pressure frees what is idle. */
class EngineMemoryPolicyTest {
    @Test
    fun largeEnginesExcludeEachOther() {
        assertEquals(setOf(Engine.IMAGE, Engine.NPU, Engine.AUDIO, Engine.SPEECH), EngineMemoryPolicy.before(Engine.LLM))
        assertEquals(setOf(Engine.LLM, Engine.NPU, Engine.AUDIO, Engine.SPEECH), EngineMemoryPolicy.before(Engine.IMAGE))
        assertEquals(setOf(Engine.LLM, Engine.IMAGE, Engine.AUDIO, Engine.SPEECH), EngineMemoryPolicy.before(Engine.NPU))
        assertEquals(setOf(Engine.LLM, Engine.IMAGE, Engine.NPU, Engine.SPEECH), EngineMemoryPolicy.before(Engine.AUDIO))
    }

    @Test
    fun dictationKeepsTheChatModelAndTheVoiceEvictsNothing() {
        assertEquals(setOf(Engine.IMAGE, Engine.NPU, Engine.AUDIO), EngineMemoryPolicy.before(Engine.SPEECH))
        assertTrue(EngineMemoryPolicy.before(Engine.VOICE).isEmpty())
    }

    @Test
    fun pressureFreesIdleEnginesOnly() {
        val all = Engine.entries.toSet()
        assertEquals(all - Engine.LLM, EngineMemoryPolicy.onTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, busy = setOf(Engine.LLM)))
        assertEquals(setOf(Engine.VOICE, Engine.SPEECH), EngineMemoryPolicy.onTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, busy = emptySet()))
        assertEquals(emptySet<Engine>(), EngineMemoryPolicy.onTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, busy = setOf(Engine.VOICE, Engine.SPEECH)))
        @Suppress("DEPRECATION")
        assertEquals(all, EngineMemoryPolicy.onTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, busy = emptySet()))
    }

    @Test
    @Suppress("DEPRECATION")
    fun appSwitchKeepsTheChatModelRealPressureFreesIt() {
        val all = Engine.entries.toSet()
        assertEquals(all - Engine.LLM, EngineMemoryPolicy.onTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, busy = emptySet()))
        assertEquals(all, EngineMemoryPolicy.onTrim(ComponentCallbacks2.TRIM_MEMORY_MODERATE, busy = emptySet()))
        assertEquals(all, EngineMemoryPolicy.onTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, busy = emptySet()))
        assertEquals(all, EngineMemoryPolicy.onTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, busy = emptySet()))
        assertEquals(all - Engine.LLM, EngineMemoryPolicy.onTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, busy = setOf(Engine.LLM)))
    }
}
