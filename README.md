# Autobot

A privacy-first AI agent for Android and iOS. Autobot is built to run models **on the device** (NPU/GPU) and
treats every network request as something the user has to opt into, can see, and can switch off.

> Status: **1.8 — private by default, fast on the metal.** Chats, agent and code sessions run on the
> device through llama.cpp: on Android on the CPU, the Adreno GPU (OpenCL) or the Hexagon NPU, on iOS
> on Metal, with speculative decoding from a small draft model. Images run through
> stable-diffusion.cpp, QNN on the Snapdragon NPU or MNN on its GPU (Android), and Core ML on the
> Apple Neural Engine (iOS). Voice is streaming dictation and spoken answers with barge-in. Cloud
> providers are optional: chats are redacted before they leave, every answer carries a privacy
> receipt, and Settings › Usage counts tokens and cost on the phone. "Phone first" lets the on-device
> model answer before any cloud model, recipes run scheduled agents on the phone, and a PIN with a
> duress code and wipe-after-failed-unlocks protects the data. Both apps run the same Kotlin
> Multiplatform core: the agent runtime, network policy, redaction, settings, model management and
> the encrypted export format.
>
> **Needs verification on real hardware:** the Snapdragon accelerator paths were built for the
> 8 Elite Gen 5 (Hexagon V81, Adreno 840) but have not yet been run on one. Models → Compute →
> `report` produces the diagnostics that would confirm it.

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
data and draw the riso UI from the same design tokens (`shared/design/tokens.json`, checked by
`TokensParityTest` on Android and `TokensTests` on iOS). The parity table is in
[ios/README.md](ios/README.md).

## Privacy stance

Both apps make the same guarantees; the rules below are shared code (`:shared`
`dev.wckdboy.autobot.core.network`), and the platform specifics are listed per item.

- **Offline by default.** The global network mode starts as `Offline`. A kill switch (the shared
  `KillSwitch`, enforced by OkHttp's `KillSwitchInterceptor` and the iOS URLSession client) rejects
  every request before DNS or socket I/O. Loopback servers
  such as a local Ollama at `127.0.0.1` can still be reached; you can turn that off too.
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
  phone. The cloud request goes through redaction, both answers stay on screen, and the receipt
  says why. Shared code (`dev.wckdboy.autobot.shared.cascade`).
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
| `:core:designsystem` | Riso theme: one signal red on ink (dark) or paper (light), Antonio display + JetBrains Mono labels, halftone rendering (`HalftoneImage`, `HalftoneField`), `ModeCard`, `RouteTag`, `RunRow`, `BigReadout`, `Panel`, `Tag`, sliders, segmented controls, `AutobotNavBar`, stroke icons |
| `:feature:home` | Run screen (Image / Chat / Agent / Code, recent runs, live CPU state), welcome, Remote screen |
| `:feature:models` | Model manager UI: phone profile, downloads, catalog with fit badges, Hugging Face and Civitai browsers, accounts |
| `:core:models` | Android side of model management: `DeviceProfile` (RAM, storage, CPU features, SoC), Hugging Face / Civitai clients and NPU package plans over the shared hubs, `FileFetcher` (OkHttp bytes for the shared download state machine), `ModelDownloader` + user-initiated transfer job, `ModelLibrary`, accounts |
| `:engine:llama` | llama.cpp (MIT) via JNI in the `:llm` process: chat templates, tool-call parsing, KV prefix reuse, runtime CPU variant selection |
| `:engine:diffusion` | stable-diffusion.cpp (MIT) via JNI in the `:sd` process: txt2img, img2img, inpainting, LoRA |
| `:engine:npu` | `:npu` process: Qualcomm QNN runtime for precompiled context binaries on the Hexagon NPU, MNN (Apache-2.0) for text encoders and GPU diffusion; SD 1.5 / SDXL pipeline (CLIP BPE, Euler / Euler a / DPM++ 2M / LCM, CFG, img2img, inpaint, v-prediction), tiled 4× upscaler |
| `:engine:speech` | whisper.cpp (MIT) in the `:asr` process: on-device dictation |
| `:engine:systemllm` | Gemini Nano through ML Kit GenAI (AICore) as the system model; only in the opt-in `playServices` flavor (brings GMS and ML Kit telemetry, see [System model fallback](#system-model-fallback)) |
| `:shared` | Kotlin Multiplatform code both apps run: provider adapter, workspace tools (okio), `AGENTS.md`/skills, `web_fetch`, host tools (`HostTool`), the MCP client (`mcp`), on-device model routing with the OS-model fallback (`SystemModels`, `LocalChatRouter`), OpenAI wire format + SSE decoding, on-device engine codec, provider presets, session → transcript projection, network policy (`NetworkMode`, `RouteResolver`, `KillSwitch`, `Loopback`, `AuditLog`), `SettingsSchema`, model management (catalogs incl. Core ML, fit rules, Hugging Face / Civitai requests and parsing, resumable SHA-256 download state machine, remote zip directory), the `.autobot` backup format, `DiagnosticsReport`, and `AgentBridge` (the Swift-facing facade, shipped as `AutobotShared.xcframework`) |
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
| `ios/App` | The SwiftUI app: riso design system from the shared tokens, every screen of the Android app, URLSession transport, encrypted store, Keychain/Secure Enclave, Core ML image generation and upscaling, export/import |
| `ios/Packages/AutobotEngines` | Swift package over the shared llama.cpp / whisper.cpp C cores (Metal, Core ML encoder) and Apple's ml-stable-diffusion; built into `AutobotLlama` / `AutobotWhisper` xcframeworks with dSYMs |
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

[Invincible](https://github.com/wckdboy/invincible) is a self-hosted node: a desktop or GPU box
that serves open models and, when its rules allow, frontier models behind redaction. It is the
"my own cloud" of ROADMAP Phase 2.5 #6, the tier between this phone and the cloud.

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
  pairing codes, and runs `RedactionVault` on the node's redaction vectors: the phone and the node
  hide the same things. `InvincibleLiveTest` pairs with a real `invincible serve` when
  `INVINCIBLE_BIN` is set. On iOS, `InvincibleNodeTests` runs the same Noise and receipt vectors
  on CryptoKit (passing on the iOS 26.1 simulator).

**On Android, "My node" works end to end** (checked on an Android 16 emulator against a real node):
Settings › Providers › + › *My node (Invincible)* opens a pairing sheet. Scanning the node's QR code
with the system camera opens it filled in (an `invincible://pair?…` link: no camera permission, no
new dependency), or the code can be pasted. The sheet shows the node's key fingerprint to compare
with what `invincible pair` printed. The pairing is stored encrypted in the provider's API-key slot
and never sent as one. Node connections go through the kill switch (offline mode refuses them, Tor
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
the conversation lives on the machine, so it is there again after the app restarts.
`InvincibleLiveTest` runs a real turn against `invincible` + `autobot` with a model, approving
from the phone side (set `AUTOBOT_BIN` and `INVINCIBLE_MODEL` too).

Not yet: the iOS connector (the preset shows "not on iOS yet"), and showing the node's own signed
receipts in the turn receipt.

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
  generation (`8gen1` = V69 … `8gen4` = V79). Autobot picks the build for your phone (an
  8 Elite Gen 5, V81, uses the `8gen4`/`8gen3` builds). The text encoders run with MNN. Zip
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
redistributed in object code inside the app under Qualcomm's AI Stack license; see NOTICE.

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

**Baseline Profile and startup.** The classes and methods that cold start and the four tabs run
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

## License

Apache License 2.0. See [LICENSE](LICENSE).
