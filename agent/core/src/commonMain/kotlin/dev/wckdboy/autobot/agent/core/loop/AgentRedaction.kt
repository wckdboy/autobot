package dev.wckdboy.autobot.agent.core.loop

import dev.wckdboy.autobot.agent.core.session.SessionEvent

/** The chat's own redaction choice (its latest [SessionEvent.RedactionChanged]); null follows Settings. */
fun Agent.chosenRedaction(): Boolean? = log.latest<SessionEvent.RedactionChanged>()?.enabled

/** Applies the chat's redaction choice from the log. Called when the agent opens. */
fun Agent.restoreRedaction() {
    val chosen = chosenRedaction()
    updateConfig { it.copy(redaction = chosen) }
}

/**
 * Sets the chat's redaction override ([enabled] null: follow Settings again) and remembers it
 * in the log, so it survives a restart.
 */
suspend fun Agent.chooseRedaction(enabled: Boolean?) {
    val changed = chosenRedaction() != enabled
    updateConfig { it.copy(redaction = enabled) }
    if (changed) log.append(SessionEvent.RedactionChanged(enabled))
}
