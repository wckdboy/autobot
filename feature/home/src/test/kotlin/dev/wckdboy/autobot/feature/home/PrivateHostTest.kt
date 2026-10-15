package dev.wckdboy.autobot.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateHostTest {
    @Test
    fun lanAndTailnetHostsArePrivate() {
        listOf(
            "http://192.168.1.20:8080/v1", "http://10.0.0.5/v1", "https://box.local/v1", "http://pc.lan:1234",
            "http://172.16.0.1/v1", "http://172.31.255.1/v1", "https://gpu.tail1234.ts.net/v1",
        ).forEach { assertTrue(it, RunViewModel.isPrivateHost(it)) }
    }

    @Test
    fun publicHostsAreNot() {
        listOf("https://api.openai.com/v1", "http://172.32.0.1/v1", "http://172.15.0.1/v1", "https://openrouter.ai/api/v1")
            .forEach { assertFalse(it, RunViewModel.isPrivateHost(it)) }
    }
}
