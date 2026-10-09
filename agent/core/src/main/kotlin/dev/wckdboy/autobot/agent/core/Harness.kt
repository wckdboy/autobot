package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.approval.ApprovalPolicy
import dev.wckdboy.autobot.agent.core.approval.ApprovalService
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.approval.PermissionPresetsPlugin
import dev.wckdboy.autobot.agent.core.llm.LlmService
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.policy.compactionPlugin
import dev.wckdboy.autobot.agent.core.policy.corePromptPlugin
import dev.wckdboy.autobot.agent.core.policy.llmRetryPlugin
import dev.wckdboy.autobot.agent.core.policy.toolResultPrunerPlugin
import dev.wckdboy.autobot.agent.core.prompt.SystemPromptService
import dev.wckdboy.autobot.agent.core.session.SessionLog
import dev.wckdboy.autobot.agent.core.session.SessionStore
import dev.wckdboy.autobot.agent.core.tools.AskUserTool
import dev.wckdboy.autobot.agent.core.tools.TodoWriteTool
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import dev.wckdboy.autobot.agent.core.tools.toolsPlugin
import kotlinx.coroutines.CoroutineScope

/**
 * The base profile (DSH `dsh-base`, trimmed to what makes sense on a phone): core services plus
 * retry, permission presets, tool-result pruning, compaction, the core prompt and the IO-free
 * built-in tools. Hosts add their own plugins (file tools, web, image generation, skills, UI
 * answerers) with [Context.plugin] on [root].
 */
class Harness(persona: String? = null) {
    val root = Context("harness")

    init {
        root.provide(LlmService.Key, LlmService())
        root.provide(ToolRegistry.Key, ToolRegistry())
        root.provide(SystemPromptService.Key, SystemPromptService())
        root.provide(ApprovalService.Key, ApprovalService())
        BASE_PLUGINS.forEach { root.plugin(it) }
        root.plugin(corePromptPlugin(persona))
    }

    val llm: LlmService get() = root.require(LlmService.Key)
    val tools: ToolRegistry get() = root.require(ToolRegistry.Key)
    val prompts: SystemPromptService get() = root.require(SystemPromptService.Key)

    /**
     * Opens [sessionId] from [store] and starts an agent on it, in its own child scope.
     * [scope] owns the agent's driver coroutine.
     */
    suspend fun openAgent(
        sessionId: String,
        store: SessionStore,
        config: AgentConfig,
        scope: CoroutineScope,
        permission: PermissionPreset = PermissionPreset.WORKSPACE,
        approvalPolicy: ApprovalPolicy = ApprovalPolicy.ASK,
        clock: () -> Long = System::currentTimeMillis,
    ): Agent {
        val log = SessionLog.open(sessionId, store, clock)
        val agent = Agent(root.fork("agent:$sessionId"), log, config, scope, permission, approvalPolicy)
        root.emit(Hooks.AgentCreated, agent)
        return agent
    }

    companion object {
        val BASE_PLUGINS: List<Plugin> = listOf(
            llmRetryPlugin(),
            PermissionPresetsPlugin,
            toolResultPrunerPlugin(),
            compactionPlugin(),
            toolsPlugin("core-tools", TodoWriteTool, AskUserTool),
        )
    }
}
