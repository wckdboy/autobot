package dev.wckdboy.autobot.core.data.model

/**
 * Chat provider families, shared so both apps derive the same wire quirks and model limits.
 * [LOCAL] runs on the device (llama.cpp engine); its base URL is unused and never dialled.
 */
enum class ProviderKind { DEEPSEEK, OPENAI_COMPATIBLE, OPENROUTER, OLLAMA, LOCAL }
