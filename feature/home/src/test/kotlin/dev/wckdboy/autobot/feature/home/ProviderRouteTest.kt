package dev.wckdboy.autobot.feature.home

import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.designsystem.component.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Run screen's route tag per provider (it once recursed forever for any cloud provider). */
class ProviderRouteTest {
    private fun provider(kind: ProviderKind, url: String) = Provider("p", kind, "P", url, "m")

    @Test
    fun cloudLanAndLoopbackProvidersGetTheirRoute() {
        assertEquals(Route.CLOUD_API, RunViewModel.providerRoute(provider(ProviderKind.DEEPSEEK, "https://api.deepseek.com")))
        assertEquals(Route.PC_LAN, RunViewModel.providerRoute(provider(ProviderKind.OLLAMA, "http://192.168.1.20:11434")))
        assertEquals(Route.LOCAL, RunViewModel.providerRoute(provider(ProviderKind.OPENAI_COMPATIBLE, "http://127.0.0.1:8080/v1")))
        assertEquals(Route.LOCAL, RunViewModel.providerRoute(provider(ProviderKind.LOCAL, "")))
    }
}
