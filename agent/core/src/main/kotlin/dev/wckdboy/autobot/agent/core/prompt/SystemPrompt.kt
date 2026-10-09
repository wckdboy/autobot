package dev.wckdboy.autobot.agent.core.prompt

import dev.wckdboy.autobot.agent.core.Disposable
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.PromptAssembly
import dev.wckdboy.autobot.agent.core.ServiceKey
import dev.wckdboy.autobot.agent.core.loop.Agent
import java.util.concurrent.CopyOnWriteArrayList

/** Section orders, mirroring DSH `SECTION_ORDERS`. */
object SectionOrder {
    const val IDENTITY = -1000
    const val PERSONA_PREFIX = 0
    const val PLAN_POLICY = 500
    const val TOOL_GUIDANCE = 1000
    const val MCP_SERVERS = 3100
    const val PERSONA_SUFFIX = 10200
}

/** Context orders for runtime-context user messages (DSH `PromptContext`). */
object ContextOrder {
    const val INSTRUCTIONS = 50
    const val WORKING_DIRECTORY = 100
    const val PERMISSION_POLICY = 110
    const val SKILLS = 130
    const val TIME = 140
}

/** A static or agent-dependent block of the system prompt. `null` text omits the section. */
class PromptSection(val id: String, val order: Int, val render: (Agent) -> String?)

/**
 * A runtime fact delivered as a `<system-reminder>` user message. It is (re)logged at the start
 * of a turn only when its text changed, which keeps the history append-only and the provider's
 * prefix cache valid.
 */
class PromptContext(val id: String, val order: Int, val render: suspend (Agent) -> String?)

/** `ctx.systemPrompt`. */
class SystemPromptService {
    private val sections = CopyOnWriteArrayList<PromptSection>()
    private val contexts = CopyOnWriteArrayList<PromptContext>()

    fun section(section: PromptSection): Disposable {
        sections.removeAll { it.id == section.id }
        sections += section
        return Disposable { sections.remove(section) }
    }

    fun section(id: String, order: Int, text: String): Disposable = section(PromptSection(id, order) { text })

    fun context(context: PromptContext): Disposable {
        contexts.removeAll { it.id == context.id }
        contexts += context
        return Disposable { contexts.remove(context) }
    }

    /** Renders all sections (after the `system-prompt/assemble` hook), joined by blank lines. */
    suspend fun assemble(agent: Agent): String {
        val rendered = sections.mapNotNull { s -> s.render(agent)?.takeIf { it.isNotBlank() }?.let { s.order to it } }
        val assembly = agent.ctx.run(Hooks.SystemPromptAssemble, PromptAssembly(agent, rendered)) { it }
        return assembly.sections.sortedBy { it.first }.joinToString("\n\n") { it.second.trim() }
    }

    /** Current context texts by id, in order. */
    suspend fun renderContexts(agent: Agent): List<Pair<String, String>> =
        contexts.sortedBy { it.order }.mapNotNull { c -> c.render(agent)?.takeIf { it.isNotBlank() }?.let { c.id to it.trim() } }

    companion object {
        val Key = ServiceKey<SystemPromptService>("systemPrompt")
    }
}
