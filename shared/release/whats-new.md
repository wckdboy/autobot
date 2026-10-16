Faster and smarter on-device chat: a real thinking switch with a budget, the NPU reads your prompt while the CPU writes the answer, proper formatting in answers, and a lighter Run screen.

## Thinking you control (both platforms)
- **A real switch.** The `∴ think` chip above the composer cycles Auto / On / Off; long-press for a budget (256 to 4k tokens). It's remembered per chat, with optional per-model defaults. Thinking is turned off through the model's own chat template, not a prompt the model can ignore.
- **A budget the engine enforces.** When thinking reaches its budget, the engine wraps up and the model answers. Part of the output is always reserved for the answer, and a loop guard ends thinking that goes in circles.
- **Auto is sensible.** Small models (under 4B) skip thinking in quick chat, which is faster and, on 0.8B models, also more accurate. Agent and code modes think within a budget. Subagents and summaries never think.
- **Before and after:** Qwen3.5 0.8B used to think about 2,000 tokens and never answer. Now it answers in 25 ms with thinking off, 1 s with a 128-token budget, and 3.3 s on Auto in agent mode.
- **Cloud providers** get their real reasoning control (OpenAI, Claude, OpenRouter, Qwen, DeepSeek, Z.ai, Groq, Gemini, vLLM, llama.cpp server). Auto leaves each provider's default alone.

## NPU reads, CPU writes (Android, Snapdragon)
- A new **npu+cpu** setup: the Hexagon NPU reads your prompt, then the CPU writes the reply. On a OnePlus 15 with Qwen3.5 0.8B: prompts read at about 2,500 tok/s (CPU about 100), and replies are written at about 50 tok/s (NPU alone about 40). A long agent prompt that took 10–20 s to read now takes under a second.
- Auto now ranks engines by a typical turn (about 1,000 prompt tokens + 200 generated), so it picks npu+cpu where that's fastest. If memory is short, it falls back to the NPU alone.
- The Compute screen shows all five engines. An on-device bench (`engine/llama/bench/android.sh`) is included for anyone measuring their own phone.

## Formatted answers (both platforms)
- Answers render headings, **bold**, *italic*, lists (nested and checkboxes), quotes, tables, code blocks with copy, and links.
- **Still private:** nothing in a message can make a network request. Images show as text ("[image: alt · host]"), HTML stays literal, and links ask before opening in your browser, with a warning for plain http.
- Streaming stays smooth: half-written formatting shows as text until it closes. Parsing a 20 KB answer takes under 1 ms per update.
- A copy button under each answer copies the original markdown. Read-aloud speaks the plain text without symbols.

## Smoother
- **Run screen (Android):** each card's dots draw in their own layer, and the image art is drawn once. Main-thread work per frame dropped from 5.5 ms to 2.5 ms.
- **Background dots on both platforms** update 5 times a second instead of 10. The slow wave looks the same, and the GPU wakes up half as often, saving battery.

## Fixed
- Claude: newer models reject `temperature`, which conversation summaries were sending. It's no longer sent.
- Android: panic wipe also clears the formatted-text cache.

## Files
- `autobot-1.6.6-release.apk`: signed release (`ai.wckd.autobot`, arm64-v8a, Android 14+). Signer certificate SHA-256 `9a1378acf9587885e7920355558c85a1ec0e89304f58c153feebf90fdee7f3f1`.
- `autobot-1.6.6-debug.apk`
- `autobot-1.6.6-mapping.txt`: R8 mapping, for reading crash reports
- `SHA256SUMS.txt`

iOS: TestFlight build 1.6.6.
