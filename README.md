<picture>
  <source media="(prefers-color-scheme: dark)" srcset="shared/design/brand/autobot-dark.svg">
  <img alt="autobot" src="shared/design/brand/autobot-light.svg" height="44">
</picture>

**Private AI on your phone and your machine.** An agent for Android and iOS that runs models on
the device (NPU, GPU, CPU) and treats every network request as something you opt into, can see,
and can switch off. With [Invincible](https://github.com/wckdboy/invincible), your own node, it
works on a machine you own or a server you rent too.

[Install](#install) · [Privacy](#privacy-stance) · [Your node](#your-own-node-invincible--in-progress) ·
[Building](#building) · [Releases](https://github.com/wckdboy/autobot/releases) · [Security](SECURITY.md) ·
[Brand](BRAND.md)

## Status

Version 1.14. What runs, and what is not yet verified:

| Part | State |
| --- | --- |
| On-device models | Chats, agent and code sessions through llama.cpp: Android on the CPU, the Adreno GPU (OpenCL) or the Hexagon NPU; iOS on Metal; speculative decoding from a small draft model |
| Images | stable-diffusion.cpp, QNN on the Snapdragon NPU or MNN on its GPU (Android), Core ML on the Apple Neural Engine (iOS) |
| Voice | Streaming dictation and spoken answers with barge-in |
| Cloud | Optional. Chats are redacted before they leave, every answer carries a privacy receipt, Settings › Usage counts tokens and cost on the phone; "Phone first" lets the on-device model answer before any cloud model |
| Protection | A PIN with a duress code and wipe after failed unlocks; recipes run scheduled agents on the phone |
| Your node | Pairs with Invincible over Noise: the node's agent, approvals, schedules, receipts and audit log in the app |
| Core | One Kotlin Multiplatform core for both apps: agent runtime, network policy, redaction, settings, model management, the encrypted export format |
| Snapdragon | Built for the 8 Elite Gen 5 (Hexagon V81 NPU, Adreno 840 GPU). Measured on a OnePlus 15: chat on the CPU and the NPU (Qwen3.5 0.8B) and SDXL on the NPU ([1.6.5](https://github.com/wckdboy/autobot/releases/tag/v1.6.5)); Models → Compute → `report` shows the diagnostics on your phone |
| Generative UI | Since 1.13: in the phone chats the agent can call `show_ui` (A2UI v1.0, shared core in `:shared`) to show forms, choices and cards, drawn with Material 3 Expressive on Android (`:core:a2ui`) and SwiftUI system controls with Liquid Glass on iOS, in the app's purple; kept with the chat; a sent form locks and keeps what was entered (unsent typing is not kept across a restart); images load only on tap. Not offered to subagents or recipe runs. Settings › Agent › Generative UI turns it off (on by default) |
| Harness (iOS) | Since 1.14: the Harness tab boots an Alpine + Node guest in the iSH-ARM64 emulator and runs the DeepSeek Harness web UI on the phone, supervised by the app. Autobot's DeepSeek key reaches it, Offline mode stops it, and the audit log records that its model calls leave from the guest. Experimental: the guest boots in about a minute, and the emulator is slow. The emulator and dsh-ios are GPL-3.0, so the iOS build is distributed under GPL-3.0 (see [License](#license)) |
| **Not yet verified** | No independent security review yet ([reporting a vulnerability](SECURITY.md)). No diagnostics report from an 8 Elite Gen 5 is published: chat and images on the GPU, SD 1.5 on the NPU and the NPU upscaler have not been shown to run on one |

Autobot and Invincible are made by [Sovware](https://sovware.io).

## Install

- **Android 14+ (arm64):** signed APKs on [GitHub releases](https://github.com/wckdboy/autobot/releases).
  The app id is `ai.wckd.autobot`; releases are signed with the certificate whose SHA-256 is
  `9a1378acf9587885e7920355558c85a1ec0e89304f58c153feebf90fdee7f3f1` (verify with
  `apksigner verify --print-certs`). Each release lists `SHA256SUMS.txt`.
- **iOS 18+:** TestFlight (`ai.wckd.autobot`). See [ios/README.md](ios/README.md) to build it yourself.

Versions move together: Android and iOS ship the same version and build number.

## Two apps, one core

```
 Android (Compose, Hilt, Room+SQLCipher, OkHttp)      iOS (SwiftUI, SQLite+CryptoKit, URLSession)
        │  thin platform adapters: storage, HTTP bytes, engines, UI  │
        └──────────────────────────┬─────────────────────────────────┘
                     :shared + :agent:core (Kotlin Multiplatform)
   agent runtime · tools · providers wire format · network policy · settings schema ·
   model catalog + fit rules + hub clients + download state machine · .autobot export · diagnostics
                                   │
        native engines (shared C cores): llama.cpp · whisper.cpp · [Android] sd.cpp, QNN, MNN · [iOS] Core ML
```

A behaviour fix in the core lands on both platforms at once; the platforms only move bytes, store
data and draw their UI from the same design tokens (brand purple, type, spacing) (`shared/design/tokens.json`, checked by
`TokensParityTest` on Android and `TokensTests` on iOS). The parity table is in
[ios/README.md](ios/README.md).

Every chat (the phone's, an agent run, the node's agent) has the same composer on both platforms,
and every screen with a field follows one keyboard pattern (`shared/composer/Composer.kt`,
`Keyboard.kt` on Android, `Keyboard.swift` on iOS): the field grows to five lines, then scrolls;
the last message stays right above it; while the keyboard is up only the field, the transcript and
the question being answered stay, and banners, chips, the plan and empty-state art step aside. A
chat shows one banner at a time.

## Privacy stance

Both apps make the same guarantees; the rules below are shared code (`:shared`
`dev.wckdboy.autobot.core.network`), and the platform specifics are listed per item.

- **Offline by default.** The global network mode starts as `Offline`. A kill switch (the shared
  `KillSwitch`, enforced by OkHttp's `KillSwitchInterceptor` and the iOS URLSession client) rejects
  every request before DNS or socket I/O. Loopback servers
  such as a local Ollama at `127.0.0.1` can still be reached; you can turn that off too.
- **The Harness tab is the exception, and it says so.** Its Linux guest makes the harness's model
  calls from the emulator, outside the route resolver and the kill switch's per-request view. So the
  guest runs only while the network mode allows a route out (Offline stops it), the audit log records
  that it is running and where its calls go, and the tab is iOS-only.
- **You choose the route.** The modes are Offline, Direct, Tor (Orbot SOCKS `127.0.0.1:9050`, remote DNS)
  and SOCKS5. Each provider can override the route, but a `Direct` override can never downgrade a
  global Tor or SOCKS5 mode.
- **Visible egress.** The Privacy Center shows a live, in-memory audit log of every request
  attempt: time, method, host, path, sizes, tag and whether it was blocked. Headers, query strings and
  bodies are never recorded.
- **Encrypted at rest.**
  - Conversations live in Room on top of SQLCipher.
  - The 256-bit database key is random and wrapped by an AES-256-GCM Android Keystore key
    (StrongBox when available).
  - API keys are encrypted the same way, never logged, and their `toString()` is redacted.
- **No telemetry.**
  - No Google Play Services, Firebase, analytics or crash reporting.
  - Requests carry no device identifiers.
  - The User-Agent is the constant `Autobot`.
  - There is no HTTP cache, no cookies and no automatic redirect following. Model downloads follow
    redirects hop by hop, and every hop passes the kill switch and the audit log.
- **Locked down.**
  - `FLAG_SECURE` is set and recents screenshots are disabled.
  - Optional app lock using biometrics or the device credential, with an auto-lock timeout.
  - Backups and device transfer are disabled.
  - HTTPS uses system CAs only. Cleartext is allowed only for `localhost`, `127.0.0.1` and `10.0.2.2`.
  - The keyboard is asked not to learn from what you type (`IME_FLAG_NO_PERSONALIZED_LEARNING`).
- **Redaction before cloud calls.** On by default: names, emails, phone numbers, addresses,
  card, IBAN and account numbers and memory facts become placeholders (`[PERSON_1]`) before a
  cloud provider sees a request, and the answer gets the real values back on the phone. Each
  chat can switch it off; the receipt counts what was hidden. Shared code
  (`dev.wckdboy.autobot.shared.redaction`).
- **Phone first.** Off by default. With it on, a plain chat on a cloud model is answered by the
  on-device model first; a small judge (rules plus one short self-check on the same model)
  decides whether that answer is good enough. "Ask" then offers the cloud model, "Auto" sends a
  weak answer's prompt on its own unless it looks personal, "Never" keeps everything on the
  phone, or, with your paired node as the chat's model, on the phone and that node (see
  [Your own node](#your-own-node-invincible--in-progress)). The cloud request goes through
  redaction, both answers stay on screen, and the receipt says why. Shared code
  (`dev.wckdboy.autobot.shared.cascade`).
- **Incognito chats.** These are held in memory only and never touch the database.
- **Panic wipe.** Type `WIPE` to destroy Keystore keys, wrapped keys, databases, DataStore,
  secrets and caches. The process is then killed.
- **Export you control.** Settings → Export & import writes one `.autobot` file sealed with your
  passphrase (PBKDF2-HMAC-SHA256, 600k iterations; AES-256-GCM in 1 MiB chunks with the header,
  chunk index and final flag authenticated, so tampering, reordering and truncation are rejected).
  API keys are never exported, and an import never switches the app online or changes the app lock.
  The other platform's app opens the same file.
- **Diagnostics without identifiers.** Models → Compute → `report` shows the device, accelerators
  and benchmark results for bug reports. No prompts, file paths or identifiers; nothing is sent.
- **iOS equivalents.** Data protection (`NSFileProtectionComplete`), Keychain + Secure Enclave key
  wrapping, a privacy cover in the app switcher, Face ID / passcode lock, backup exclusion, ATS with
  HTTPS only except loopback.
- **Minimal permissions.** `INTERNET` and `USE_BIOMETRIC`, plus `RUN_USER_INITIATED_JOBS`,
  `ACCESS_NETWORK_STATE` and `POST_NOTIFICATIONS` so a model download can keep running (as a
  user-initiated data transfer job) with a progress notification. The
  notification permission is asked for only when the first download starts. `RECORD_AUDIO` is
  asked for only when you first tap the microphone; audio is transcribed on the phone and never
  stored or sent anywhere.

## Module map

| Module | Responsibility |
| --- | --- |
| `:app` | `AutobotApplication` (Hilt), `MainActivity` (FragmentActivity, edge-to-edge, FLAG_SECURE), welcome screen, Navigation 3 `NavDisplay` with the RUN · MODELS · REMOTE · SETTINGS bar, lock screen, manifest and network security config |
| `:core:designsystem` | Material 3 Expressive theme in the brand purple (MaterialKolor scheme) with the brand's two marks: the pixel a (`AutobotMark`, `Wordmark`, Invincible's `NodeMark`) and the purple pixel dot after a top-level title. One kit of standard Material components (`Standard*.kt`): `AppBar`/`TitleWithDot`, list items (`NavListItem`, `SwitchItem`, `InfoItem`, `ActionListItem`), `SectionCard`, `TonalLabel`, `RouteChip`, `NoticeCard`, `EmptyContent`, `Loading`, `JobProgress`, `EmptyPicture`, `Field`, `SearchField`, `Choices`, `SliderField`, `StepperField`, `DropdownField`, the chat composer (`MessageComposer`, `SendIconButton`, `StopIconButton`), chat markdown (code blocks on `surfaceContainerHigh`, tonal inline code) and Material Symbols for the Run modes. Every screen uses it: Chat, Run, Remote and the node screens, Settings, Models, Image, Studio, the lock screens and the welcome. The riso kit is gone |
| `:feature:home` | Run screen (Image / Chat / Agent / Code, recent runs, live CPU state), welcome, Remote screen |
| `:feature:models` | Model manager UI: phone profile, downloads, catalog with fit badges, Hugging Face and Civitai browsers, accounts |
| `:core:a2ui` | The Compose renderer of A2UI v1.0: `A2uiSurfaceView(host, surfaceId, onAction)` draws the basic catalog with Material 3 in the app theme, writes inputs back through the host, asks before opening links; `A2uiChatState` / `A2uiToolSurfaces` for a chat's `show_ui` surfaces |
| `:core:models` | Android side of model management: `DeviceProfile` (RAM, storage, CPU features, SoC), Hugging Face / Civitai clients and NPU package plans over the shared hubs, `FileFetcher` (OkHttp bytes for the shared download state machine), `ModelDownloader` + user-initiated transfer job, `ModelLibrary`, accounts |
| `:engine:llama` | llama.cpp (MIT) via JNI in the `:llm` process: chat templates, tool-call parsing, KV prefix reuse, runtime CPU variant selection |
| `:engine:diffusion` | stable-diffusion.cpp (MIT) via JNI in the `:sd` process: txt2img, img2img, inpainting, LoRA |
| `:engine:npu` | `:npu` process: Qualcomm QNN runtime for precompiled context binaries on the Hexagon NPU, MNN (Apache-2.0) for text encoders and GPU diffusion; SD 1.5 / SDXL pipeline (CLIP BPE, Euler / Euler a / DPM++ 2M / LCM, CFG, img2img, inpaint, v-prediction), tiled 4× upscaler |
| `:engine:speech` | whisper.cpp (MIT) in the `:asr` process: on-device dictation |
| `:engine:systemllm` | Gemini Nano through ML Kit GenAI (AICore) as the system model; only in the opt-in `playServices` flavor (brings GMS and ML Kit telemetry, see [System model fallback](#system-model-fallback)) |
| `:shared` | Kotlin Multiplatform code both apps run: provider adapter, workspace tools (okio), `AGENTS.md`/skills, `web_fetch`, host tools (`HostTool`), the MCP client (`mcp`), on-device model routing with the OS-model fallback (`SystemModels`, `LocalChatRouter`), OpenAI wire format + SSE decoding, on-device engine codec, provider presets, session → transcript projection, network policy (`NetworkMode`, `RouteResolver`, `KillSwitch`, `Loopback`, `AuditLog`), `SettingsSchema`, model management (catalogs incl. Core ML, fit rules, Hugging Face / Civitai requests and parsing, resumable SHA-256 download state machine, remote zip directory), the `.autobot` backup format, `DiagnosticsReport`, the A2UI v1.0 generative UI core (`a2ui`: message parser, catalog-driven validator with LLM-readable errors, surface state, render tree, input write-back and actions, the `show_ui` tool with lenient intake and the replay rules, drawn by `:core:a2ui` and the iOS renderer), and `AgentBridge` (the Swift-facing facade, shipped as `AutobotShared.xcframework`) |
| `:agent:core` | Kotlin Multiplatform (JVM + iOS) port of the DeepSeek Harness runtime: Cordis-style `Context` (services, hooks, reversible effects, injecting plugins), append-only session log + `deriveMessages`, ReAct step/turn loop, tool pipeline, permission presets + fail-closed approvals, retry, tool-result pruning, compaction |
| `:agent:runtime` | Android host: `AgentHost`, provider → `LlmAdapter` bridge (DeepSeek `reasoning_content` echo), encrypted session store, `/workspace` sandbox with `read`/`write`/`edit`/`glob`/`grep`/`delete`, `web_fetch`, `AGENTS.md` instructions, skills |
| `:core:diffusion` | `DiffusionEngine` API, A1111/Forge engine (`/sdapi/v1`), local HTTP+SSE engine, backend registry |
| `:feature:imagine` | Imagine screen (txt2img / img2img / inpaint, LoRA stack, sampler, canvas, seed, batch), mask editor, encrypted gallery, backends manager, `generate_image` agent tool |
| `:core:security` | `KeyManager` (Keystore AES-GCM, StrongBox fallback, wrapped DB passphrase), `SecretStore`, `AppLockManager`, `PanicWipe`, pure-JVM `AesGcmEnvelope` |
| `:core:network` | Android glue for the shared policy: `NetworkPolicy` state, `KillSwitchInterceptor`, `HeaderScrubInterceptor`, `HttpClientFactory`, `java.net.Proxy` routes, `OkHttpTransport` |
| `:core:data` | Room + SQLCipher (`Conversation`, `Message`, `Provider`), repositories, DataStore settings under the shared `SettingsSchema`, `IncognitoSession`, settings→policy sync, `BackupService` (`.autobot` export/import) |
| `:providers:remote` | `ChatProvider` over OkHttp in the dialect of the provider's preset (OpenAI-compatible or Anthropic native), model lists, the on-device `LocalChatProvider`; `ProviderFactory` |
| `:feature:chat` | Session list (live indicators, incognito) and agent session screen (streaming, reasoning, tool cards with inline approvals, plan/todos, questions, context gauge, slash commands, model switcher) |
| `:feature:settings` | Settings, Privacy Center (network mode, audit log, panic wipe), Providers editor (masked key, routing, test connection), MCP servers editor (masked token, routing, trusted, test connection) |
| `ios/App` | The SwiftUI app: system components with Liquid Glass and the brand purple tint, every screen of the Android app, URLSession transport, encrypted store, Keychain/Secure Enclave, Core ML image generation and upscaling, export/import |
| `ios/Packages/AutobotEngines` | Swift package over the shared llama.cpp / whisper.cpp C cores (Metal, Core ML encoder) and Apple's ml-stable-diffusion; built into `AutobotLlama` / `AutobotWhisper` xcframeworks with dSYMs |
| `ios/Harness` | The Harness tab's native side (`HarnessKernel`, the XcodeGen fragment generator) and the vendored [dsh-ios](https://github.com/everettjf/dsh-ios) with the iSH-ARM64 emulator (GPL-3.0); the guest image is built, not committed |
| `scripts/` | `bump-version.sh` (one version for both apps), `release.sh` (test, build, sign, TestFlight, GitHub release) |
| `build-logic/` | Convention plugins: `autobot.android.application`, `autobot.android.library`, `autobot.android.compose`, `autobot.hilt` |

## Cloud and local providers

Settings → Providers → **+** lists every preset from the shared table
(`ProviderPresets`, checked against each provider's docs; the doc link is in the code), grouped:

- **Cloud:** OpenAI, Anthropic (native Messages API with prompt caching), Google Gemini, xAI,
  Mistral, DeepSeek, Moonshot (Kimi), Alibaba Qwen, Z.ai (GLM), MiniMax, Cohere, Perplexity.
- **Open-model hosts:** OpenRouter, OpenCode Go, Groq, Cerebras, Together AI, Fireworks AI,
  DeepInfra, Nebius Token Factory, SambaNova, Novita, Hugging Face Inference Providers, Venice.ai,
  abliteration.ai.
- **Local servers:** Ollama, LM Studio, llama.cpp server, vLLM, any OpenAI-compatible endpoint.
- **This phone:** added automatically once a chat model is installed.

Saving a key (or "refresh", or "test connection") fetches the provider's model list through the
same kill switch, route and audit log as chat. The key is sent only to the provider's own
origin. Non-chat models (embeddings, speech, image, moderation…) are filtered out, lists are
sorted flagship-first then newest, cached in the encrypted store ("fetched 3 min ago"), and
their context windows size the agent's context. Each preset shows its privacy note.

**OpenCode Go** (the $10/month subscription, one key for ~30 open models) gets per-model
handling: each model goes to its documented endpoint (chat completions, or the Anthropic-style
`/messages` for Qwen, MiniMax and Claude Haiku); models only on the Responses or Gemini APIs
(Grok, GPT, Muse, Gemini) are hidden. Every request carries the per-conversation
`x-opencode-session` id the service requires, an opaque hash of the local chat id. Model lists show
each model's data policy (`ModelDataPolicies`): zero retention, kept 30 days, or "trains on your
prompts" for the *Contributor* models. Switching a chat to one of those asks first, and the app
never picks one on its own. DeepSeek's zero-retention agreement is dated and stops counting after
it ends.

**Helper model.** Every provider can name a cheaper helper model, used for subagents (the
`task` tool runs on it unless asked for `model: "main"`) and context summaries, so a quota lasts
longer. OpenCode Go starts with `glm-5.3-flash`. A used-up plan limit (HTTP 429 "usage limit",
402) reads as such, with the window and reset time when the provider gives them ("OpenCode Go's
5-hour limit is used up; it resets in ~2 h"). It is not retried like a rate limit. With **On
limit, fall back** (Settings → Agent, off by default), the turn runs once more on the helper
model, else the on-device model, and the answer says so.

## Your own node (Invincible) — in progress

[Invincible](https://github.com/wckdboy/invincible) is a self-hosted node: a desktop, a GPU box or
a server you rent that serves open models and, when its rules allow, frontier models behind
redaction. It is the "my own cloud" of ROADMAP Phase 2.5 #6, the tier between this phone and the
cloud.

The protocol layer is in `:shared` (`dev.wckdboy.autobot.shared.invincible`):

- **Pairing and sessions.** `PairingCode` parses the node's QR code; `InvincibleNode.pair` /
  `session` run Noise `IKpsk1` / `IK` (`25519_ChaChaPoly_SHA256`) over a `NodeLink`. The
  `NodeChannel` that comes out carries HTTP/1.1 to the node's OpenAI-compatible API. No accounts,
  no relay: the node's key comes from the QR code, and the phone's key is pinned on the node.
- **Platform crypto only.** `NodeCrypto` is javax.crypto on Android (`JvmNodeCrypto`) and CryptoKit
  on iOS (`SwiftNodeCrypto`): X25519, ChaCha20-Poly1305, SHA-256, HMAC, Ed25519. The Noise state
  machine is common code; no primitive is.
- **Signed receipts.** `NodeReceipts.verify` checks the node's per-request receipts (what ran where,
  what left the node) against the receipt key pinned at pairing.
- **Same rules as the node.** `scripts/sync-invincible-spec.sh` copies Invincible's conformance
  vectors into `shared/src/jvmTest/resources/invincible`. `InvincibleSpecTest` checks Noise
  byte for byte against the node's snow transcripts, receipts against OpenSSL-made signatures,
  pairing codes, runs `RedactionVault` on the node's redaction vectors (the phone and the node
  hide the same things), and the kill switch and network modes on the egress vectors (the same
  request is blocked, sent direct or proxied on both). `InvincibleLiveTest` pairs with a real `invincible serve` when
  `INVINCIBLE_BIN` is set. On iOS, `InvincibleNodeTests` runs the same Noise and receipt vectors
  on CryptoKit (passing on the iOS 26.1 simulator).

**On Android, "My node" works end to end** (checked on an Android 16 emulator against a real node):
Remote › *Pair a node* (or the welcome screen's *Pair a node*, or Remote › Providers › + › *My node
(Invincible)*) opens a pairing sheet. Scanning the node's QR code with the system camera opens it
filled in (an `invincible://pair?…` link: no camera permission, no new dependency; on a fresh
install the welcome steps aside for it), or the code can be pasted. The sheet shows the node's key
fingerprint to compare with what `invincible pair` printed; Cancel stops the handshake. The pairing
is stored encrypted in the provider's API-key slot and never sent as one. Pairing the same node
again (after an unpair there, or a new address) replaces that provider's pairing and keeps the
phone's key, so the node lists it once. A node whose pairing is gone (a restored backup has no
secrets) shows "not paired" in Providers. Failures are said in plain words, the same on both apps:
a used or expired code, a node that is down or silent (handshakes give up after 15 s), an answer
cut off, a phone the node no longer knows; a node that cannot be reached is tried once, not
retried. Node connections go through the kill switch (offline mode refuses them, Tor
and SOCKS5 carry them with remote DNS), every attempt is in the Privacy Center's audit log, and
the turn's privacy receipt counts the bytes that went to the node. Ed25519 receipt checks use
BouncyCastle's RFC 8032 verifier, because Android's software provider has no Ed25519.
`NodeCryptoDeviceTest` (instrumented) runs the Noise and receipt vectors on the device.

**The agent on your machine.** If the machine also runs the Autobot Agent (`autobot serve`,
installed with the node by Invincible's `install.sh`), REMOTE › *Agents · my node* opens it.
Tasks run on that machine: shell commands and file work, on its models. Every command, every file
write and every read outside the agent's working directory comes to the phone as a card first:
Allow, Always (this conversation), or Deny. Answers show the model and tier that ran each turn. It
all goes through the same Noise session as the node's models: `NodeAgent` (`:shared`) speaks
Invincible's [spec/agent.md](https://github.com/wckdboy/invincible/blob/main/spec/agent.md), and
the conversation lives on the machine, so it is there again after the app restarts. The calendar
button lists the agent's scheduled tasks (next run, how the last one went), adds them (cron in the
machine's local time, `@daily`, `every 30m`) and removes them; they run with nobody to ask, so
only allowlisted commands and writes in the working directory.
`InvincibleLiveTest` runs a real turn against `invincible` + `autobot` with a model, approving
from the phone side (set `AUTOBOT_BIN` and `INVINCIBLE_MODEL` too).

**Recipes on your node.** A recipe's editor has *Runs on*: this phone, or a paired node. On a
node it runs there on its schedule, always on and with no 8-minute limit, on the node's models,
offering only the recipe's tool groups (files, memory; the node has no web or vault tools, and the
editor says so). It is sent in UTC with the phone's current offset, so "07:00" stays the phone's
07:00 wherever the node is, and sent again when the offset changes. The phone collects every run
(every 15 minutes on Android, when the app opens on iOS, and right after *Run now*) into a chat in
the Recipes folder, with a notification as for a phone run, and the chat's receipt holds the node's
own signed receipts, checked against the key pinned at pairing. `NodeRecipeSync` (`:shared`) does
it for both apps; `InvincibleLiveTest` hands a recipe to a real node, runs it there and collects it.

**A team on your machine.** When the node's agent has team members (`[agents.*]` in its config),
the agent screen shows each member's answer under its name, and a member's approval card says
who is asking ("coder: Run a command?"). Web fetches and MCP tools on the node get their own
cards too ("Fetch a web page?", "Use github/get_issue?"), and a recipe on the node can use the
web group: the node fetches through its gateway, under its network rules.

**The team board.** When the node's agent plans background work for its team, the Activity sheet's
*board* tab shows each plan's tasks (who, status, result). A member that needs you shows its
question there, with a field to answer, and the tab counts what is waiting. The answer sends the
task on, as `/board answer` does in Telegram.

**While you were away.** With a node paired, the Run screen shows a short card when it did
something since you last looked: how many requests it answered (on the node or in the cloud,
tokens), the agent's actions (by the rules or approved by you), the recipes that ran, what
failed, and whether every receipt verified (one that did not is the first line, in red). Questions
waiting for you are its main button, which opens the agent's board; Details opens the activity.
Dismissing or opening it marks it seen, per node.

**Bots.** A node in bots mode runs several named bots, each with its own persona, model, approvals
mode and channels (Telegram, Discord, Slack, email, Signal, Matrix, the API). Remote › *Agents · my
node* then lists them under the agent row: name, persona line and model, its state with the
channels it listens on, a channel that is down in red with why (`slack down: token revoked ✗`),
and what is queued and when it was last active. A bot's page shows its channels, model and
approvals, the approvals waiting for it, its recent sessions, a switch to turn it off (it asks
first) and *New chat with ops*, a conversation on the node with that bot. With more than one
bot, the agent screen and its approval cards say which bot, and "While you were away" gives one
short line per bot. An older node without bots answers 404 to `GET /v1/agent/bots` and the
screens stay as they were. With the demo data (below) the paired node has three sample bots:
`default`, `ops` on Telegram and Slack with Slack down, and `support` on email.

**A bot's personality, what it knows, what it did.** With Invincible 0.0.14 or later, a bot's
page also shows its personality, picked from the node's list (`none`, `concise`, …) and set for
every turn from then on; *What it knows about you*, the entries of its `USER.md` profile, read-only
here, with the command to edit it in the node's terminal; and its usage: today's turns, model
calls, tokens and cost, then the last 7 days with the share of calls that stayed on the machine,
what the cloud cost (or which cloud models have no price), the tools and models it used as flat
bars, and what went wrong. Turning a bot on or off says what the node did (`ops is running.`, or
why it could not start), and Remote's bot list has *Reload* to bring the bots in line with their
configuration at once. In a chat with the node's agent, a photo from the camera or the library
goes with the message, downscaled on the phone to 1568 px and re-encoded as JPEG without its
location; a bot whose model cannot see images says so and gives the text back. When the agent
keeps something on its own after an answer, a quiet `kept · profile: …` line says what. Retry on
the last answer runs that turn again on the node (`POST …/retry`). An older node shows none of
this and the page stays as it was.

**What the node did.** The list button on the agent screen opens the node's activity, from its
own logs (Invincible's [spec/audit.md](https://github.com/wckdboy/invincible/blob/main/spec/audit.md)):
*Requests* is every request the node answered (which device or the agent asked, the model and
tier, tokens, and whether anything left the node), each receipt checked against the key pinned at
pairing, so an entry that does not verify says so in red. *Actions* is everything the agent asked
to do and who decided: the rules, an "always" earlier, or you on the app, the CLI or Telegram.
*Memory* shows what the agent remembered between conversations (forget any of it) and the skills
on the machine. The requests show even when the agent is not running.

**The node's own receipts.** Every answer from a node comes with its request id; when the turn's
privacy receipt is built, the phone fetches the node's receipt for it (Invincible's
[spec/receipts.md](https://github.com/wckdboy/invincible/blob/main/spec/receipts.md)) and checks
its Ed25519 signature against the receipt key pinned at pairing. The receipt line then says
`kept on studio ✓` (the signed receipt lists no outside host), `studio → 1 host` (the node used a
cloud model, which the sheet lists with routes, bytes and what the node redacted), or
`studio unverified` (the signature does not check out: shown, never trusted). `NodeReceiptLogTest`
runs this on Invincible's own signed receipt vectors, tampered and mis-keyed copies included.

**Phone first, then my node.** "Phone first" works on a node chat too: this phone answers first,
and when the judge finds its answer weak, the prompt goes to your node instead of a cloud model.
The node is yours, on hardware you own or on a server you rent, so "Never" (nothing to the cloud)
does not keep a prompt from it: under "Never", as under "Auto", a weak answer's prompt goes to the
node on its own, a sensitive one included, and an answer the judge is only unsure about is
offered ("Ask" offers both). With no on-device chat model, or a chat too long for it, the node
answers directly. These turns are sent with `invincible-private: 1`, so the node keeps them on its
own models and never forwards them to a cloud backend: here "never" means never to a cloud model.
The notice reads `phone → my node`, and the receipt says the turn was sent to your node and kept
there. Nothing is redacted for the node; it redacts what it sends on itself.

**On iOS too.** Remote › *Pair a node* (or Remote › Providers › + › *My node (Invincible)*) opens
the same pairing sheet (paste the code, or open an `invincible://pair?…` link: the Camera app's QR
scan does that), with the same words and the same re-pairing. The
connector is Network.framework under the same kill switch, routes and audit log as every request
(`SwiftNodeConnector`; Tor and SOCKS5 get the node's name unresolved), and the crypto is CryptoKit.
Chats, signed receipts, "phone first, then my node" and the agent screen (Remote › Agents · my
node: tasks, approval cards, scheduled tasks, activity) work as on Android. `NodeConnectorTests` checks the connector over a real `NWConnection` and,
with `TEST_RUNNER_INVINCIBLE_PAIR_CODE` set, pairs and chats with a real node from the simulator
(and with `TEST_RUNNER_INVINCIBLE_AGENT=1`, runs an agent task approved from the phone and finds
it, and the phone's own chat, in the node's logs).

## On-device models

**Engines.** Two native engines are built from pinned git submodules in `third_party/`:

- **llama.cpp** runs chat, agent and code sessions. It is built for every ARM feature level
  (ARMv8.0 up to ARMv9.2 with i8mm/SVE2), and the best one for the phone is picked at runtime.
- **stable-diffusion.cpp** runs images. It is built for ARMv8.2 with dot-product and fp16.

Each engine runs in its own process (`:llm`, `:sd`) behind a Binder interface. A native crash or
an out-of-memory kill ends that process, not the app. The engines never touch the network.
Downloaded local models show up as the **This phone** provider and the **On-device** image
backend.

**Model manager.** The MODELS tab has three sources:

- **For this phone** — a curated catalog with exact files and licences: Qwen3.5 0.8B–4B, Qwen3 4B
  Instruct 2507, Gemma 4 E2B, Phi-4 mini, SmolLM3, Llama 3.2 3B, Qwen2.5 Coder, SD 1.5,
  SDXL Turbo, Z-Image Turbo and FLUX.2 klein 4B. Each entry is checked against the phone's RAM,
  free storage and CPU features and marked `FITS`, `TIGHT` or `TOO BIG`.
- **Hugging Face** — search GGUF repositories and pick a file. Signing in with a read token
  unlocks gated and private repositories.
- **Civitai** — checkpoints and LoRAs, from `civitai.com` or `civitai.red` (same API). An API key
  is optional. Mature content is hidden unless you opt in (18+). Models flagged as depicting
  minors or real people are always filtered out.

**Downloads.** Downloads run in a user-initiated data transfer job on Android (they keep going when
you leave the app or swipe it away, wait for a network — or Wi-Fi only — and resume after the
process dies) and in a background `URLSession` on iOS (swiping the app away there stops them; they
resume on the next launch). They go through the kill switch like every request, resume with HTTP
`Range`, and are verified against the SHA-256 published by the hub. A file that fails the check is deleted. Hub tokens are
sent only to their own host (`huggingface.co`, `civitai.com` / `civitai.red`) and never to CDN
redirects.

## System model fallback

"This phone" can also run the operating system's own model. It appears as a model of that provider
and stands in automatically when the requested downloaded model is not installed, is too big for the
phone's memory, or fails to load. An answer from a stand-in is labelled in the chat
(`… · system model · fallback — <why>`), so you always know what ran. The decision is one pure
function in `:shared` (`LocalModelResolver`); the platforms only report availability and load
failures. Compute and the diagnostics report list the system model and its availability.

The system models have about 4k tokens of context and are offered without tools: earlier tool calls
and results are passed as text, and long system prompts and old turns are shortened to fit.

- **iOS 26+: Apple Intelligence** (`system:apple`, Foundation Models). On-device only; Private
  Cloud Compute is never used. Unavailable on ineligible devices, with Apple Intelligence turned
  off, or while its model is still downloading; iOS 18–25 builds skip it.
- **Android: Gemini Nano** (`system:gemini-nano`, ML Kit GenAI Prompt API → AICore), **only in the
  opt-in `playServices` flavor** (`./gradlew :app:assemblePlayServicesRelease`, app id
  `ai.wckd.autobot.gms`). The published `foss` app does not contain it, because ML Kit cannot be
  used without Google code and telemetry:
  - `com.google.mlkit:genai-prompt` pulls in `play-services-basement`/`-base`/`-tasks`,
    `firebase-components`/`-encoders` and `com.google.android.datatransport` (Firelog/Clearcut),
    plus `ACCESS_NETWORK_STATE`, a JobScheduler service and an init provider.
  - ML Kit sends usage metrics (device and app info and a per-install ML Kit id, not prompts) to
    Google, with no opt-out, as stated in the ML Kit terms. That traffic does not pass Autobot's
    kill switch or audit log.
  - Inference itself runs in the AICore system app on the phone. AICore downloads Gemini Nano
    itself; Autobot asks it to only when you tap `download` under Compute or pick the model.
  - Supported phones are listed by Google (Pixel 9/10, Galaxy S25/S26 and others; Galaxy S26 with
    the Snapdragon 8 Elite Gen 5 is on the list). It answers only while the app is in the
    foreground, has per-app quotas, and does not run with an unlocked bootloader.
  - Use is subject to the ML Kit Terms and the GenAI additional terms (Generative AI Prohibited
    Use Policy, 18+).

## NPU and GPU

Phones are fast at inference only off the CPU, so Autobot uses every accelerator a Snapdragon has:

- **Images on the Hexagon NPU.** Precompiled Stable Diffusion 1.5 and SDXL packages in the Local
  Dream layout (for example [xororz](https://huggingface.co/xororz): `sd-qnn`, `sdxl-qnn`) run
  through Qualcomm's QNN runtime. The UNet and VAE are QNN context binaries built per Hexagon
  generation (`8gen1` = V69 … `8gen4` = V79). Autobot picks a build your phone can load, as Local
  Dream does: every phone from V73 up, the 8 Elite Gen 5 (V81) included, gets the `8gen2` (V73)
  builds; SDXL is published as `8gen3` (V75) builds only, which run on V75 and newer. `8gen4`
  builds are never picked on a V81. The text encoders run with MNN. Zip
  packages are never downloaded whole: the needed entries are fetched by byte range, inflated
  and checked against the zip's CRC-32. SDXL needs a Snapdragon 8 Gen 3 or newer. DMD2 packages
  default to 8 LCM steps. Anima packages are not supported yet.
- **Images on the GPU.** `sd-mnn` packages run on the Adreno GPU through MNN's OpenCL backend, at
  any resolution.
- **Upscaling on the NPU.** 4× UltraSharp and Real-ESRGAN models from the same publisher upscale
  gallery images in 192 px tiles.
- **Chat on the GPU.** llama.cpp's OpenCL backend (tuned by Qualcomm for Adreno) loads like the
  CPU variants. If a model or context cannot be created on the GPU, it falls back to the CPU.
- **Chat on the NPU.** llama.cpp's Hexagon backend (`libggml-hexagon.so` plus HTP op libraries for
  V73, V75, V79 and V81) offloads matmuls in Q4_0, Q8_0, MXFP4 and the K-quants to the NPU. The
  Hexagon SDK only runs on Linux, so `.github/workflows/hexagon.yml` builds the libraries from the
  pinned llama.cpp commit and publishes them as a release; `:engine:llama` downloads that release
  and checks every file against SHA-256 hashes pinned in `engine/llama/build.gradle.kts`. The DSP
  loads its half through FastRPC from the app's library directory (`ADSP_LIBRARY_PATH`).
  `auto` tries NPU → GPU → CPU.

**Compute screen** (MODELS → COMPUTE) shows the CPU, GPU and NPU of the phone. For each model you
can force CPU, GPU or NPU, or run a benchmark. `auto` then uses whichever backend was fastest.

**Speculative decoding.** A small model of the same family drafts a few tokens and the chat model
checks them in one batch, keeping the ones it agrees with plus its own next token. Every emitted
token is still sampled by the chat model from its own logits, in order, so replies are what plain
decoding gives: with sampling the same distribution, greedy the same text (on Metal identical in
every bench check; on the CPU, llama.cpp rounds the batched check slightly differently from a
single step, so at a near tie a reply can take the other, equally likely word), and the reasoning
budget, the loop guard, tool-call grammars and streaming see every token as before. The catalog pairs Qwen3 4B Instruct 2507 with Qwen3 0.6B,
Llama 3.2 3B with Llama 3.2 1B, and Qwen2.5 Coder 7B / 1.5B with Qwen2.5 Coder 0.5B. Settings
live on the Compute screen (`auto` / `on` / `off`, default `auto`): `auto` loads the draft when it
is installed, the chat model fits well alone and the pair still fits, and the engine stops
speculating while it does not pay (it measures acceptance and the cost of draft and check steps on
the device and picks how many tokens to draft); `on` drafts whenever the pair fits; `off` never
loads a draft. The draft runs on the CPU on Android (accelerators pay a fixed cost per
single-token step, the draft's whole job) and on Metal next to the model on iOS. It sees a window
of the last 0.5-1k tokens (8-bit KV cache), so a new chat's long agent prompt costs it at most a
512-token prefill, and only once a reply turns out longer than a tool call. A draft with another tokenizer is refused with a clear
reason. Hybrid models with recurrent layers (the Qwen3.5 family) do not speculate: rejected drafts
cannot be cut from their memory cheaply (a 50 MB state copy per round for Qwen3.5 4B plus 19 MB for
its 0.8B draft, which has the same problem, and no draft-side prompt cache), so the catalog does not
pair them and the engine reports them as `unsupported`. The Compute benchmark measures the same
reply with and without the draft and shows the speedup and the share of drafts kept; the
diagnostics report includes both.

Measured with `engine/llama/bench` (`--draft`) on an M3 Pro, Metal, greedy, `auto`, median of
three interleaved runs: Qwen3 4B 2507 + 0.6B 1.64x on code, 1.44x on a list, 1.74x on a rewrite,
0.98x on free prose (47% of drafts kept), 2.01x on a 1,300-token reply; Llama 3.2 3B + 1B 1.38x /
1.29x / 1.52x / 1.02x; Qwen2.5 Coder 1.5B + 0.5B 1.22x on code. Short agent turns (20-50 tokens)
stay at 1.00x: the draft is not caught up for them. On the Mac's CPU alone (draft on the CPU too)
it does not pay (0.9-1.4x, median about 1.0x; `auto` keeps the loss small). Phones are not
measured yet.

**Vision.** Chat models with a vision projector (`mmproj`), such as Qwen3-VL and SmolVLM2 from the
catalog, read attached photos. Hub downloads pick up a repo's `mmproj` automatically. Images are
picked with the system photo picker (no storage permission), re-encoded (dropping EXIF and GPS)
and stored encrypted. Cloud vision models receive them inline.

**Speech.** Dictation uses whisper.cpp on the phone (Models → speech) and shows the words while
you speak: a bounded sliding window is transcribed again every ~0.7 s, words two passes agree on
are settled, and windows close at pauses. Audio stays in memory and is never written or sent.
Answers are read aloud with the Kokoro neural voice when installed, else an offline system voice;
in voice mode they are read sentence by sentence while they stream, and with hands-free on,
talking over an answer interrupts it (echo-cancelled microphone behind an energy gate).

Qualcomm's QNN runtime (`com.qualcomm.qti:qnn-runtime` from Maven Central) is proprietary. It is
redistributed in object code inside the Android app under Qualcomm's AI Stack license; see
[License](#license) and NOTICE.

## Agent runtime (DeepSeek Harness port)

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (`dsh`, MIT) is a TypeScript agent
harness where *everything is a plugin*. `:agent:core` re-implements its architecture natively in
Kotlin so it runs inside the app process, under the app's network policy:

| DSH | Autobot |
| --- | --- |
| Cordis `Context`, services, `waterfall`/`emit`, effects, `inject` | `Context`, `ServiceKey`, `Hook`/`Event`, `effect`, `Plugin.inject` (plugins re-queue when a service disappears) |
| Session log, `deriveMessages()`, surface `replace` | `SessionLog` (append-only, persisted in SQLCipher), `deriveMessages`, `SurfaceOp.Replace` |
| `ReactLoopAgent` (steps/turns, inbox `followup`/`steer`/`inject`, cancel repair) | `Agent` — same semantics; interrupted streams are committed, undispatched calls get `ABORTED_BEFORE_DISPATCH` |
| Tool pipeline (`pre-execute` → approval → `execute` → `post-execute`, barriers, `isConcurrencySafe`) | `ToolExecutor` — parallel batches, exclusive barriers, results committed in model order |
| `dsh-permission-presets`, `ctx.approval` (fail closed) | `PermissionPreset` (read-only / workspace-write / full-access, network = escalation), `ApprovalService`, plus *allow for session* |
| `dsh-llm-retry`, tool-result pruner, `dsh-compaction-basic` | `llmRetryPlugin`, `toolResultPrunerPlugin`, `Compactor` (same summary sections) |
| `task` (subagents) | `TaskTool`: a child `Agent` on the parent's model, preset, approval policy and grants, with a fresh in-memory history; parallel `task` calls run together; no nesting; approvals and questions surface on the parent's `task` card; the report is the tool result and the child's steps go in `meta.subagent` |
| `read`, `write`, `edit`, `glob`, `grep`, `web_fetch`, `todo_write`, `ask_user_question`, `skill`, `AGENTS.md` | Same names, confined to a private `/workspace` (real paths are never shown to the model) |

Not ported (no equivalent on a phone): `bash`/`pwsh` sandboxes and hooks bridges. The runtime is Kotlin
Multiplatform, so the iOS app runs exactly this code; it is covered by JVM tests.

**MCP servers.** Settings → MCP servers adds remote [Model Context Protocol](https://modelcontextprotocol.io)
servers over Streamable HTTP (`dev.wckdboy.autobot.shared.mcp`). Each server's protocol is
detected once per configuration: the stateless 2026-07-28 revision first (`server/discover`,
per-request `_meta`, `Mcp-Method` / `Mcp-Name` / `x-mcp-header` headers, `subscriptions/listen`
for tool-list changes), falling back to the session-based 2025-11-25, 2025-06-18 and 2025-03-26
revisions as the spec's backward-compatibility rules describe. Their tools join agent sessions as `mcp__<server>__<tool>` with the
server's own schema. Nothing is dialled until a turn with tools starts; every request goes
through the kill switch, the server's route (Inherit / Direct / Tor / SOCKS5, same rules as
providers) and the audit log, and redirects are not followed. Each tool call asks for approval
(the card names the server and its host). MCP calls are network calls (`FETCH`), so a server
marked trusted follows the permission preset like `web_fetch` instead of always asking:
full-access runs its calls, read-only and workspace-write still ask. An optional bearer token is stored like an API key and sent
only to that server's host. Offline, unreachable or disabled servers contribute no tools and the
model is told why. Not yet: OAuth sign-in, resources/prompts, images in tool results (shown as a
placeholder), servers in the `.autobot` export.

## Image generation

The Image screen talks to diffusion **backends** through one `DiffusionEngine` interface:

- **On-device** — stable-diffusion.cpp in the `:sd` process, with any downloaded image model
  and LoRAs. Works in Offline mode.
- **Local SSE engine** — `GET /health`, `POST /generate` streaming `progress` / `complete` /
  `error` events. It works with Local Dream's backend host mode.
- **A1111 / Forge / SD.Next API** (`/sdapi/v1`) — runtime LoRA via `<lora:name:w>`, checkpoint
  switching, samplers/schedulers, inpainting, live previews, interrupt on cancel.

A prompt with `{red|blue}` wildcards runs once per expanded prompt (same seed, at most 16
images), and one tap applies a built-in style or a negative preset. While you write the prompt
for an on-device model (stable-diffusion.cpp on Android, Core ML on iOS), the app loads it (freeing the chat model first, never while another
engine works), so Generate starts sampling instead of loading; the status line says "Loading the
model" until the first step.

Every request goes through the kill switch and audit log. Cleartext is only allowed to loopback,
so LAN backends need HTTPS (e.g. Tailscale). Results are stored in an **encrypted gallery**
(AES-256-GCM files under a Keystore-wrapped media key); exporting to `Pictures/` is an explicit,
confirmed action. The agent can generate images too (`generate_image`, a `GENERATE` tool that
reuses your Imagine settings).

The feature set (modes, mask editing, LoRA stacks, schedulers, parameter reuse) was informed by
studying the behaviour of [xororz/local-dream](https://github.com/xororz/local-dream). That project
is CC BY-NC 4.0, so **no code, assets, model files or conversion tooling were taken from it**; the
implementation is clean-room, from public API descriptions.

## Building

Requirements:

- JDK 17 or newer to run Gradle. Android Studio's bundled JBR works.
- Android SDK with platform `android-37.0`, NDK `30.0.16248370` and CMake `4.1.2`. Install them
  with the SDK Manager or with
  `sdkmanager "ndk;30.0.16248370" "cmake;4.1.2"`.
- The engine sources, which are git submodules:

  ```sh
  git submodule update --init --recursive
  ```

The first build compiles llama.cpp, stable-diffusion.cpp, MNN and whisper.cpp, which takes a few
minutes. It also needs network access once. `:engine:npu:fetchQairtHeaders` reads the QNN headers
out of Qualcomm's public QAIRT SDK zip with HTTP range requests into the git-ignored
`third_party/qairt/`. Those headers are marked confidential, so they are never committed. No Python
is needed: the OpenCL kernel embedding runs as a CMake script.

Windows (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleFossDebug      # debug APK (arm64-v8a)
.\gradlew.bat testDebugUnitTest      # JVM unit tests
.\gradlew.bat :app:assembleFossRelease # R8 full mode + resource shrinking (unsigned)
```

macOS/Linux:

```sh
./gradlew assembleFossDebug testDebugUnitTest :app:assembleFossRelease
```

If AGP fails with *"Several environment variables and/or system properties contain different paths
to the Android Preferences folder"*, unset `ANDROID_PREFS_ROOT` (it conflicts with
`ANDROID_USER_HOME`).

Release builds are configured for reproducibility:

- no dependency-metadata blob (`dependenciesInfo` off)
- no VCS info
- no build timestamps
- `android.util.Log` calls stripped by R8
- the Baseline Profile is committed text (`app/src/fossRelease/generated/baselineProfiles/`), so
  builds need no device; two clean builds give the same APK

**Baseline Profile and startup.** The classes and methods that cold start, the four tabs, all
chats, a new chat and a Settings page run
ship precompiled (ART applies the profile through profileinstaller, also for sideloaded installs),
and the startup part orders the primary dex. Regenerate it after large UI changes, with an API 33+
emulator or phone attached:

```sh
./gradlew :app:generateFossReleaseBaselineProfile                        # rewrites the committed profile
./gradlew :baselineprofile:connectedFossBenchmarkReleaseAndroidTest      # cold start, with and without it
```

The activity reports itself fully drawn when a real screen shows (lock, welcome or Run), so logcat's
`Fully drawn` line and Macrobenchmark's time to full display are the startup time a person sees.
`-Pautobot.composeReports` writes Compose compiler reports (what skips, which parameters are
unstable) to each module's `build/compose-reports`.

Signing is left to the distributor (F-Droid / GitHub releases). For local signed builds, put a
git-ignored `keystore.properties` at the repository root:

```properties
storeFile=C:/path/to/autobot-release.jks
storePassword=…
keyAlias=autobot
keyPassword=…
```

When it exists, `:app:assembleFossRelease` produces a signed `app-foss-release.apk`; otherwise the
output stays `app-foss-release-unsigned.apk`.

**Flavors.** `foss` is the app Autobot publishes. `playServices` is an opt-in build with Gemini Nano
(see [System model fallback](#system-model-fallback)); `assembleDebug` / `assembleRelease` build both,
so use the `Foss` task names for the default app.

### Tests

```sh
./gradlew testDebugUnitTest :shared:jvmTest :agent:core:jvmTest          # Android + shared core
cd ios && xcodegen generate && xcodebuild -project Autobot.xcodeproj -scheme Autobot \
    -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test           # iOS app
```

The shared core's tests run on the JVM; the iOS suite re-runs the network, settings and backup
rules through the Kotlin framework, and decrypts `shared/backup/testvector.autobot` (written on the
JVM) with CryptoKit to prove both platforms read the same format. CI (`.github/workflows/ci.yml`)
runs the Android/shared suites on Linux and the iOS suites on macOS for every push and pull request.

### A2UI eval

How often a model produces valid A2UI through `show_ui`, the risk on small on-device models. 33
phone-chat requests (`shared/src/jvmTest/resources/a2ui/eval/tasks.json`: time slots, RSVPs,
validated forms, chips, lists templated over data, dates, tabs, cards with actions, two that
should stay in text) go to any OpenAI-compatible endpoint with the app's system prompt and the
`show_ui` schema; the real `ShowUiTool` and validator answer each call, invalid UI goes back to
the model up to 3 times, and the first valid UI is checked against the task's expectations
(component types, inputs bound to the data model, the button's context carrying the inputs,
checks, templates).

```sh
./gradlew :shared:a2uiEval -Pmodel=qwen3:4b-instruct                          # Ollama on :11434
./gradlew :shared:a2uiEval -Pendpoint=http://localhost:8080/v1 -Pmodel=local    # llama.cpp server
./gradlew :shared:a2uiEval -Pendpoint=https://api.example.com/v1 -Pkey-env=API_KEY -Pmodel=...
```

`-Ponly=id,id` picks tasks, `-Pextra='{"chat_template_kwargs":{"enable_thinking":false}}'` is
merged into each request. It writes `build/a2ui-eval/`: `report.md` (a table per model and per
task, the most common validator errors) and, per model, every request and the tool's answer as
JSONL. Calls a model writes as text instead of a tool call are recovered and counted
(`-Psalvage=false` to not). `show_ui` declares `messages` as a JSON array of message objects;
`-Pargs=string` declares the old string form instead (the tool accepts both), and
`-Pprompt=<file>` swaps `A2uiPrompt.text` for another guidance text. The scoring runs in `:shared:jvmTest` on canned output.
Ollama drops tool calls whose JSON does not parse (they show as `no-call`); llama.cpp's server
reports them, which the eval counts as `malformed-call`.

### Demo data and screenshots

Debug builds take launch switches for UI work; release builds ignore them (Android parses them
only when `BuildConfig.DEBUG` is set, and the sample data is compiled into `src/debug` alone; iOS
reads them under `#if DEBUG`). Both apps seed an empty install with the same sample: providers
(DeepSeek, Ollama, a paired node), a chat with reasoning and code, an agent run with tool steps and
a plan, a "phone first" chat, a recipe run, four gallery images, an installed model, an image
backend, audit entries, three bots on the node (`NodeBots.sample`), each with a personality, a
profile and a week of usage (`NodeBotDetails.sample`), a chat with the node's agent with a photo
and what it kept (`NodeChatSample`, the `node` screen), and the node digest sample, one line per
bot (`NodeDigests.sampleWithBots`).

| | Android (intent extras) | iOS (launch arguments) |
| --- | --- | --- |
| Allow screenshots | `--ez allow_screenshots true` | `-AutobotScreenshots` (also skips the app lock) |
| Sample data | `--ez demo true` | `-AutobotDemo` |
| Digest with a failed receipt | `--es demo_digest failed` | `-AutobotDemoDigest failed` |
| Start screen | `--es open <screen>` | `-AutobotOpen <screen>` |
| Theme for this launch | `--es theme light\|dark\|system` | `-AutobotTheme light\|dark\|system` |
| Past the welcome | `--ez skip_welcome true` | `-AutobotSkipWelcome` |
| Chat opens with the keyboard up | (tap the field) | `-AutobotKeyboard` |

Screens: `run`, `sessions`, `chat`, `agent`, `phone-first`, `ui` (a generative UI form; Android), `imagine`, `gallery`, `viewer`,
`backends`, `models`, `compute`, `accounts`, `remote`, `providers`, `node` (a chat with its agent), `settings`, `privacy`,
`bot` (the node's ops bot), `about`, `licenses`, `memory`, `vault`, `usage`, `recipes`, `mcp`, `studio`,
`studio-<room>`.

```sh
adb shell pm clear ai.wckd.autobot.debug        # the sample only fills an empty install
adb shell am start -n ai.wckd.autobot.debug/dev.wckdboy.autobot.MainActivity \
  --ez allow_screenshots true --ez demo true --es open models --es theme light
xcrun simctl launch booted ai.wckd.autobot -AutobotScreenshots -AutobotDemo -AutobotOpen models -AutobotTheme light
```

Every main screen in dark and light, from the sample: `app/screenshots/` (Android) and
`ios/screenshots/` (iOS), with the same file names.

### Releasing

`scripts/release.sh X.Y.Z [notes.md]` bumps both apps, runs every suite, builds the signed APK,
uploads the iOS build to TestFlight, tags `vX.Y.Z` and publishes the GitHub release with
`SHA256SUMS.txt`. It stops before tagging if anything fails.

### iOS

See [ios/README.md](ios/README.md). In short: `./gradlew :shared:assembleAutobotSharedReleaseXCFramework`
builds the shared Kotlin runtime, `ios/Packages/AutobotEngines/scripts/build-xcframeworks.sh` builds
llama.cpp and whisper.cpp for iOS, and `cd ios && xcodegen generate` creates the Xcode project.
Design tokens (`shared/design/tokens.json`) are shared by both apps; the model catalog, fit rules,
hub clients and download state machine live in `:shared` (`dev.wckdboy.autobot.core.models`).

### Qualcomm QNN

- The QNN **runtime** comes from Maven Central (`com.qualcomm.qti:qnn-runtime`). Only the HTP
  backend, `libQnnSystem` and the V68–V81 DSP libraries are packaged. The on-device graph
  compiler, DSP and GPU backends are excluded.
- The QNN **headers** are fetched at build time into `third_party/qairt/<version>/` and git-ignored.
- Model files (`/models/`, `*.gguf`, `*.bin`, `*.safetensors`, `*.onnx`, `*.mnn`, `*.dlc`) are
  git-ignored as well.

## Roadmap

See [ROADMAP.md](ROADMAP.md): goals, release rules and the phased plan for both platforms.

## Reporting a vulnerability

Privately, through GitHub's **Report a vulnerability** on this repository's Security tab.
If the Security tab has no **Report a vulnerability** button, open an issue titled
`security contact` with no details; a maintainer will open a private advisory and invite you to it.
Scope, response targets and how fixes ship: [SECURITY.md](SECURITY.md).

## License

Autobot's own code in this repository, both apps and their shared core, is Apache License 2.0.
See [LICENSE](LICENSE). The Autobot Agent on your machine (`autobot serve`) is not in this
repository: it is the Rust `autobot` in the [Invincible](https://github.com/wckdboy/invincible)
repository, under the MIT License.

The Android app is not all open source: it contains Qualcomm's QNN runtime
(`com.qualcomm.qti:qnn-runtime` 2.51.0 from Maven Central), which is proprietary and ships as
object code only, under Qualcomm's AI Stack license rather than Apache-2.0. It runs the UNet and
VAE of the NPU image models (SD 1.5, SDXL) and the 4× upscaler on the Hexagon NPU. Every Android
build contains it, the published `foss` APK and the opt-in `playServices` build alike: both depend
on `:engine:npu`, and the packaging check (`verify<Variant>NativePackaging` in
`app/build.gradle.kts`) fails an APK without the QNN libraries. There is no Android build without
it today. Chat on the NPU does not use it (that is llama.cpp's own Hexagon backend), and
the iOS app does not contain it.

The iOS app includes the Harness tab, which links [dsh-ios](https://github.com/everettjf/dsh-ios)
and the iSH-ARM64 emulator, both GPL-3.0. The iOS app as distributed (TestFlight and any store
build) is therefore a combined work under the GNU General Public License v3.0, with iSH's
additional terms (`ios/Harness/dsh-ios/ish-arm64/LICENSE.IOS`). Apache-2.0 is GPL-3.0-compatible,
so the Apache-licensed files stay Apache-licensed and can be reused under that licence on their
own. The Android app links no GPL code and is distributed under Apache-2.0, apart from the QNN
runtime above. See [NOTICE](NOTICE) and Settings › Licenses in the app.
