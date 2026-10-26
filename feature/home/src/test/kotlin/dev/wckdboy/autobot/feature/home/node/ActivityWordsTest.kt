package dev.wckdboy.autobot.feature.home.node

import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.ReceiptNode
import dev.wckdboy.autobot.shared.invincible.AgentActionEntry
import dev.wckdboy.autobot.shared.invincible.NodeActivity
import dev.wckdboy.autobot.shared.invincible.NodeActivityWords
import dev.wckdboy.autobot.shared.invincible.NodeRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityWordsTest {
    private fun request(caller: String, client: String? = null, action: String = "run", tier: String = "node", egress: List<ReceiptHost> = emptyList(), verified: Boolean = true) =
        NodeRequest(1, caller, client, action, "requested", ReceiptNode("studio", "r", verified, model = "qwen", tier = tier, egress = egress))

    @Test
    fun saysWhoWhereAndWhatLeft() {
        assertEquals("My-Phone", NodeActivityWords.who(request("device:My-Phone")))
        assertEquals("the agent", NodeActivityWords.who(request("local", "autobot-agent")))
        assertEquals("this machine", NodeActivityWords.who(request("local")))
        assertEquals("qwen on the node", NodeActivityWords.where(request("local")))
        assertEquals("refused (requested)", NodeActivityWords.where(request("local", action = "block")))
        val sent = request("local", tier = "cloud", egress = listOf(ReceiptHost("api.example.com", "tor", 1, 10, 5)))
        assertEquals("qwen on the cloud", NodeActivityWords.where(sent))
        assertEquals("sent to api.example.com via tor", NodeActivityWords.left(sent))
        assertEquals("nothing left the node", NodeActivityWords.left(request("local")))
        val activity = NodeActivity(listOf(sent, request("local", verified = false)), emptyList(), emptyList(), emptyList())
        assertEquals("2 requests · 1 sent off the node · 1 not verified", NodeActivityWords.summary(activity))
        // From Invincible 0.0.22 the node lists only this phone's own requests: an empty list does
        // not mean the node answered nothing.
        assertEquals("No requests from this phone yet", NodeActivityWords.summary(NodeActivity(emptyList(), emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun saysWhoDecided() {
        fun action(ran: Boolean, by: String) = NodeActivityWords.decided(AgentActionEntry(1, "s", "command", "run: ls", ran, by))
        assertEquals("allowed by the rules", action(true, "policy"))
        assertEquals("approved on telegram", action(true, "telegram"))
        assertEquals("refused: no one to ask", action(false, "unattended"))
        assertEquals("denied on app", action(false, "app"))
    }
}
