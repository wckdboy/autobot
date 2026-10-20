package dev.wckdboy.autobot.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelCaseTest {
    @Test
    fun sentenceCaseNeverUpperCase() {
        assertEquals("Recent runs", labelCase("recent runs"))
        assertEquals("Models · API & LAN", labelCase("models · api & lan"))
        assertEquals("Image backends · PC & LAN", labelCase("image backends · pc & lan"))
        assertEquals("CFG 1 skips the unconditional pass", labelCase("cfg 1 skips the unconditional pass"))
        assertEquals("LoRA library · 3", labelCase("lora library · 3"))
        assertEquals("Already Set", labelCase("Already Set"))
    }

    @Test
    fun leavesNumbersSymbolsAndNamesAlone() {
        assertEquals("~0 / 75 tokens", labelCase("~0 / 75 tokens"))
        assertEquals("∴ Thinking", labelCase("∴ thinking"))
        assertEquals("txt2img · dpm", labelCase("txt2img · dpm"))
        assertEquals("qwen3-4b · Q4", labelCase("qwen3-4b · Q4"))
        assertEquals("", labelCase(""))
        assertEquals("civitai.com · sfw", labelCase("civitai.com · sfw"))
        assertEquals("Inpaint", labelCase("inpaint"))
        assertEquals("Model qwen-api.gguf", labelCase("model qwen-api.gguf"))
    }
}
