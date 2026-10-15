package dev.wckdboy.autobot.agent.runtime

/** Which open agents [AgentHost] closes to bound memory. */
internal object AgentEviction {
    /**
     * Ids from [openLru] (least recently used first) to close so at most [max] stay open, never
     * one in [busy] (running, waiting on the user, or on screen). Busy agents may exceed [max].
     */
    fun evictable(openLru: List<String>, busy: Set<String>, max: Int): List<String> {
        var excess = openLru.size - max
        if (excess <= 0) return emptyList()
        val out = mutableListOf<String>()
        for (id in openLru) {
            if (excess == 0) break
            if (id in busy) continue
            out += id
            excess--
        }
        return out
    }
}
