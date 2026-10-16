package dev.wckdboy.autobot.agent.core.loop

import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.agent.core.session.SessionEvent

/** The chat's own Think choice (its latest [SessionEvent.ThinkingChanged]), or null when it never chose. */
fun Agent.chosenThinking(): ThinkingSetting? = log.latest<SessionEvent.ThinkingChanged>()?.setting

/** The Think switch requests use now (auto when nothing was chosen). */
val Agent.thinking: ThinkingSetting get() = config.value.llm.thinking ?: ThinkingSetting.AUTO

/**
 * Applies the chat's Think switch: its own choice from the log, else [modelDefault] (the user's
 * default for the chat's model), else auto. Called when the agent opens and when its model changes.
 */
fun Agent.restoreThinking(modelDefault: ThinkingSetting?) {
    val setting = chosenThinking() ?: modelDefault
    updateConfig { it.copy(llm = it.llm.copy(thinking = setting)) }
}

/** Sets the chat's Think switch and remembers it in the log (restored when the chat reopens). */
suspend fun Agent.chooseThinking(setting: ThinkingSetting) {
    val changed = chosenThinking() != setting
    updateConfig { it.copy(llm = it.llm.copy(thinking = setting)) }
    if (changed) log.append(SessionEvent.ThinkingChanged(setting.mode, setting.budget))
}
