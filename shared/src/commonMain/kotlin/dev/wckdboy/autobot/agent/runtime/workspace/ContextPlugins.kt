package dev.wckdboy.autobot.agent.runtime.workspace

import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.prompt.ContextOrder
import dev.wckdboy.autobot.agent.core.prompt.PromptContext
import dev.wckdboy.autobot.agent.core.prompt.SectionOrder
import dev.wckdboy.autobot.agent.core.prompt.SystemPromptService
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import dev.wckdboy.autobot.shared.IoDispatcher
import dev.wckdboy.autobot.shared.todayIso
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okio.Path

/** A discovered skill (DSH `SKILL.md` with `name` / `description` frontmatter). */
data class Skill(val name: String, val description: String, val file: Path)

/**
 * Runtime contexts and on-disk extension points inside the workspace:
 *
 * - `AGENTS.md` at the workspace root → instructions context (≤ 64 KiB), like DSH
 *   `dsh-agent-instructions`;
 * - `.agents/skills/<name>/SKILL.md` or `.agents/skills/<name>.md` → `<available_skills>` catalog
 *   plus the `skill` tool that loads a body on demand;
 * - working directory, permission policy and today's date as small contexts.
 */
class WorkspaceContexts(private val workspace: Workspace) {
    private val fs = workspace.fs

    private val Path.isDirectory get() = fs.metadataOrNull(this)?.isDirectory == true
    private val Path.isFile get() = fs.metadataOrNull(this)?.isRegularFile == true
    private fun Path.readText() = fs.read(this) { readUtf8() }

    fun skills(): List<Skill> {
        val dir = workspace.root / ".agents" / "skills"
        if (!dir.isDirectory) return emptyList()
        val files = fs.listOrNull(dir).orEmpty().mapNotNull { f ->
            when {
                f.isDirectory -> (f / "SKILL.md").takeIf { it.isFile }
                f.isFile && f.name.endsWith(".md") -> f
                else -> null
            }
        }
        return files.mapNotNull { parseSkill(it) }.sortedBy { it.name }
    }

    private fun parseSkill(file: Path): Skill? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val front = FRONTMATTER.find(text)?.groupValues?.get(1).orEmpty()
        fun field(key: String) = Regex("(?m)^$key:\\s*(.+)$").find(front)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
        val fallback = if (file.name == "SKILL.md") file.parent!!.name else file.name.substringBeforeLast('.')
        val name = field("name") ?: fallback
        if (field("disable-model-invocation") == "true") return null
        return Skill(name, field("description") ?: "", file)
    }

    val plugin: Plugin = plugin("workspace-contexts", SystemPromptService.Key, ToolRegistry.Key) { ctx ->
        val prompts = ctx.require(SystemPromptService.Key)
        val tools = ctx.require(ToolRegistry.Key)

        ctx.effect {
            prompts.section(
                "tools:files",
                SectionOrder.TOOL_GUIDANCE,
                "Files live in ${Workspace.VIRTUAL_ROOT}. Use read/glob/grep — not guesses — to inspect them, and read a " +
                    "file before editing it.",
            )
        }
        ctx.effect {
            prompts.context(
                PromptContext("instructions", ContextOrder.INSTRUCTIONS) {
                    withContext(IoDispatcher) {
                        val file = workspace.root / "AGENTS.md"
                        if (!file.isFile) return@withContext null
                        val bytes = fs.read(file) { readByteArray() }
                        val text = bytes.decodeToString(0, minOf(bytes.size, MAX_INSTRUCTION_BYTES))
                        "Instructions from ${Workspace.VIRTUAL_ROOT}/AGENTS.md (follow them):\n\n$text"
                    }
                },
            )
        }
        ctx.effect {
            prompts.context(
                PromptContext("working-directory", ContextOrder.WORKING_DIRECTORY) {
                    "Working directory: ${Workspace.VIRTUAL_ROOT} (a private sandbox on the user's phone)."
                },
            )
        }
        ctx.effect {
            prompts.context(
                PromptContext("permission-policy", ContextOrder.PERMISSION_POLICY) { agent ->
                    when (agent.permission.value) {
                        PermissionPreset.READ_ONLY ->
                            "Permission preset: read-only. File changes are denied; network fetches need user approval."
                        PermissionPreset.WORKSPACE ->
                            "Permission preset: workspace-write. You may edit workspace files; deletes and network " +
                                "fetches need user approval."
                        PermissionPreset.FULL_ACCESS -> "Permission preset: full-access. Tools run without approval."
                    }
                },
            )
        }
        ctx.effect {
            prompts.context(
                PromptContext("skills", ContextOrder.SKILLS) {
                    val skills = withContext(IoDispatcher) { skills() }
                    if (skills.isEmpty()) {
                        null
                    } else {
                        "<available_skills>\n" +
                            skills.joinToString("\n") { "- ${it.name}: ${it.description}" } +
                            "\n</available_skills>\nLoad a skill with the `skill` tool when the task matches its description."
                    }
                },
            )
        }
        ctx.effect { prompts.context(PromptContext("time", ContextOrder.TIME) { "Today is ${todayIso()}." }) }
        ctx.effect { tools.register(SkillTool()) }
    }

    inner class SkillTool : ToolDefinition {
        override val name = "skill"
        override val kind = ToolKind.READ
        override val description = "Load the full instructions of a skill listed in <available_skills>."
        override val parameters = schema { string("name", "Skill name", required = true) }

        override fun isConcurrencySafe(args: JsonObject) = true
        override fun describeCall(args: JsonObject) = args.string("name").orEmpty()

        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = withContext(IoDispatcher) {
            val wanted = args.string("name")
            val skill = skills().firstOrNull { it.name == wanted }
                ?: return@withContext ToolOutput.error("no skill named '$wanted'", "NOT_FOUND")
            val body = skill.file.readText().replace(FRONTMATTER, "").trim()
            val resources = skill.file.parent
                ?.takeIf { skill.file.name == "SKILL.md" }
                ?.let { fs.listRecursively(it) }?.filter { it.isFile && it != skill.file }?.map { workspace.display(it) }?.toList().orEmpty()
            ToolOutput(
                buildString {
                    append("<skill_content name=\"").append(skill.name).append("\">\n")
                    if (resources.isNotEmpty()) append("<skill_resources>\n").append(resources.joinToString("\n")).append("\n</skill_resources>\n")
                    append("<skill_instructions>\n").append(body).append("\n</skill_instructions>\n</skill_content>")
                },
            )
        }
    }

    companion object {
        const val MAX_INSTRUCTION_BYTES = 65_536
        private val FRONTMATTER = Regex("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?")
    }
}
