Your words stay yours in the cloud, replies come faster on the device, voice answers while it thinks, and you can see what every model costs.

## Redact before cloud (both platforms)
- **Placeholders, not your data.** Before a chat goes to a cloud model, names, emails, phone numbers, addresses, card, IBAN and account numbers, and your memory facts become placeholders like `[PERSON_1]`. The answer comes back with your real words, put back on your phone.
- **Tools still work.** The agent gets the real values back on the phone, so it can still write the email or fill in the file.
- **On by default**, in Settings → Privacy & security. A `REDACT` chip turns it off for one chat. "This phone" and local servers are never redacted.
- **On the receipt.** It shows what was hidden ("2 names · 1 email"), never the values. Code, links, dates and version numbers are left alone, so coding chats keep working.

## Faster replies with a draft model (both platforms)
- **A small model guesses, your model checks.** Install Qwen3 0.6B next to Qwen3 4B Instruct 2507, Llama 3.2 1B next to 3B, or Qwen2.5 Coder 0.5B next to the Coders. On a Mac GPU, code, lists and rewrites came 1.3–1.7× faster and long answers about 2×.
- **Every word is still your model's.** Thinking budgets, tool calls and the stop button work as before. Short agent steps skip the draft, so they never get slower.
- **Auto / On / Off in Compute.** Auto drafts only when the pair fits your memory comfortably, and pauses when drafting doesn't pay. The benchmark and the diagnostics report show the speedup and how many guesses were kept.
- Qwen3.5 models don't use drafts: their memory can't take back wrong guesses cheaply. A draft from another model family is refused with a reason.

## Voice that keeps up (both platforms)
- **Your words as you speak.** Dictation shows settled words in ink, with the rest firming up as you go. Audio is never saved.
- **Answers start out loud after the first sentence**, not after the whole reply. Code is skipped; numbers and abbreviations are read whole.
- **Just talk to interrupt.** In hands-free mode, speaking over an answer stops it, keeps your first words and listens.
- Stopping dictation is faster: the last pass starts right away.

## Usage, counted on your phone (both platforms)
- **Settings › Usage:** tokens, requests and cost for today, this week and this month, a bar per day, and a breakdown by provider and model. Nothing is sent anywhere.
- **Your prices, never guesses.** Enter a model's price per million tokens; until then it says "cost unknown". Anthropic's published prices are built in.
- **Subscription windows.** Track OpenCode Go's 5-hour, weekly and monthly limits, or any plan's, with progress and when it resets.
- **Tokens on every receipt**, per model, with what they cost. On-device answers cost $0.
- When a provider throttles a request, its rate-limit headers say when to try again.

## Images (both platforms)
- **`{red|blue}` in a prompt** makes one image per option, with the same seed, side by side, up to 16 at a time. The agent's `generate_image` does the same.
- **One tap for a style** (Photo, Cinematic, Risograph, Ink, Watercolour) or a negative preset on the Image screen.
- **The first image comes sooner:** your on-device model loads while you're still writing the prompt.
- Android shows "Loading the model" until sampling starts, the progress bar no longer jumps back on every preview, and batches show progress on each image in turn.

## Fixed
- iOS Studio: reusing an inpaint recipe came back as img2img and repainted the whole picture. It now comes back as inpaint and asks for the mask.
- Steadier iOS test runs: waits no longer run out while another test holds the main thread.

## Files
- `autobot-1.7.0-release.apk`: signed release (`ai.wckd.autobot`, arm64-v8a, Android 14+). Signer certificate SHA-256 `9a1378acf9587885e7920355558c85a1ec0e89304f58c153feebf90fdee7f3f1`.
- `autobot-1.7.0-debug.apk`
- `autobot-1.7.0-mapping.txt`: R8 mapping, for reading crash reports
- `SHA256SUMS.txt`

iOS: TestFlight build 1.7.0.
