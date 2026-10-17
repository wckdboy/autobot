package dev.wckdboy.autobot.agent.core.loop

import dev.wckdboy.autobot.agent.core.session.SessionEvent

/** The chat's own cascade policy (its latest [SessionEvent.CascadeChanged]); null follows Settings. */
fun Agent.chosenCascade(): String? = log.latest<SessionEvent.CascadeChanged>()?.policy

/** Applies the chat's cascade policy from the log. Called when the agent opens. */
fun Agent.restoreCascade() {
    val chosen = chosenCascade()
    updateConfig { it.copy(cascade = chosen) }
}

/**
 * Sets the chat's cascade policy ([policy] null: follow Settings again) and remembers it in the
 * log, so it survives a restart.
 */
suspend fun Agent.chooseCascade(policy: String?) {
    val changed = chosenCascade() != policy
    updateConfig { it.copy(cascade = policy) }
    if (changed) log.append(SessionEvent.CascadeChanged(policy))
}
