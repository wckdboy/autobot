package dev.wckdboy.autobot.core.data.model

/**
 * Chat provider families, shared so both apps derive the same wire quirks and model limits.
 * [LOCAL] runs on the device (llama.cpp engine); its base URL is unused and never dialled.
 * [ANTHROPIC] speaks Anthropic's native Messages API; every other kind speaks OpenAI chat
 * completions. Which provider a row is beyond that is its preset id (`ProviderPresets`).
 * Stored by name: entries are only ever added.
 */
enum class ProviderKind { DEEPSEEK, OPENAI_COMPATIBLE, OPENROUTER, OLLAMA, LOCAL, ANTHROPIC }
