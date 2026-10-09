# Autobot

A privacy-first AI agent for Android. Autobot is built to run models **on the device** (NPU/GPU) and
treats every network request as something the user has to opt into, can see, and can switch off.

> Status: **1.1 — models run on the phone.** Chats, agent and code sessions run on-device through
> llama.cpp, and images through stable-diffusion.cpp, each in its own sandboxed process. A model
> manager downloads verified models from a curated catalog, Hugging Face or Civitai. Remote
> providers and PC/LAN diffusion backends still work alongside, under the same network policy.

## Privacy stance

- **Offline by default.** The global network mode starts as `Offline`. A kill switch
  (`KillSwitchInterceptor`) rejects every request before DNS or socket I/O. Loopback servers
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
- **Incognito chats.** These are held in memory only and never touch the database.
- **Panic wipe.** Type `WIPE` to destroy Keystore keys, wrapped keys, databases, DataStore,
  secrets and caches. The process is then killed.
- **Minimal permissions.** `INTERNET` and `USE_BIOMETRIC`, plus `FOREGROUND_SERVICE_DATA_SYNC` and
  `POST_NOTIFICATIONS` so a model download can keep running with a progress notification. The
  notification permission is asked for only when the first download starts.

## Module map

| Module | Responsibility |
| --- | --- |
| `:app` | `AutobotApplication` (Hilt), `MainActivity` (FragmentActivity, edge-to-edge, FLAG_SECURE), welcome screen, Navigation 3 `NavDisplay` with the RUN · MODELS · REMOTE · SETTINGS bar, lock screen, manifest and network security config |
| `:core:designsystem` | Riso theme: one signal red on ink (dark) or paper (light), Antonio display + JetBrains Mono labels, halftone rendering (`HalftoneImage`, `HalftoneField`), `ModeCard`, `RouteTag`, `RunRow`, `BigReadout`, `Panel`, `Tag`, sliders, segmented controls, `AutobotNavBar`, stroke icons |
| `:feature:home` | Run screen (Image / Chat / Agent / Code, recent runs, live CPU state), welcome, Remote screen |
| `:feature:models` | Model manager UI: phone profile, downloads, catalog with fit badges, Hugging Face and Civitai browsers, accounts |
| `:core:models` | Catalog, `DeviceProfile` (RAM, storage, CPU features, SoC), Hugging Face and Civitai clients, resumable SHA-256-verified `FileFetcher`, `ModelDownloader` + foreground service, `ModelLibrary` |
| `:engine:llama` | llama.cpp (MIT) via JNI in the `:llm` process: chat templates, tool-call parsing, KV prefix reuse, runtime CPU variant selection |
| `:engine:diffusion` | stable-diffusion.cpp (MIT) via JNI in the `:sd` process: txt2img, img2img, inpainting, LoRA |
| `:agent:core` | Pure-Kotlin port of the DeepSeek Harness runtime: Cordis-style `Context` (services, hooks, reversible effects, injecting plugins), append-only session log + `deriveMessages`, ReAct step/turn loop, tool pipeline, permission presets + fail-closed approvals, retry, tool-result pruning, compaction |
| `:agent:runtime` | Android host: `AgentHost`, provider → `LlmAdapter` bridge (DeepSeek `reasoning_content` echo), encrypted session store, `/workspace` sandbox with `read`/`write`/`edit`/`glob`/`grep`/`delete`, `web_fetch`, `AGENTS.md` instructions, skills |
| `:core:diffusion` | `DiffusionEngine` API, A1111/Forge engine (`/sdapi/v1`), local HTTP+SSE engine, backend registry |
| `:feature:imagine` | Imagine screen (txt2img / img2img / inpaint, LoRA stack, sampler, canvas, seed, batch), mask editor, encrypted gallery, backends manager, `generate_image` agent tool |
| `:core:security` | `KeyManager` (Keystore AES-GCM, StrongBox fallback, wrapped DB passphrase), `SecretStore`, `AppLockManager`, `PanicWipe`, pure-JVM `AesGcmEnvelope` |
| `:core:network` | `NetworkPolicy`, `KillSwitchInterceptor`, `HeaderScrubInterceptor`, `AuditLog`, `RouteResolver`, `HttpClientFactory` |
| `:core:data` | Room + SQLCipher (`Conversation`, `Message`, `Provider`), repositories, DataStore settings, `IncognitoSession`, settings→policy sync |
| `:providers:remote` | `ChatProvider` SSE streaming: OpenAI-compatible, DeepSeek (`reasoning_content`), OpenRouter, Ollama, and the on-device `LocalChatProvider`; `ProviderFactory` |
| `:feature:chat` | Session list (live indicators, incognito) and agent session screen (streaming, reasoning, tool cards with inline approvals, plan/todos, questions, context gauge, slash commands, model switcher) |
| `:feature:settings` | Settings, Privacy Center (network mode, audit log, panic wipe), Providers editor (masked key, routing, test connection) |
| `build-logic/` | Convention plugins: `autobot.android.application`, `autobot.android.library`, `autobot.android.compose`, `autobot.hilt` |

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

**Downloads.** Downloads run in a foreground service, resume with HTTP `Range`, and are verified
against the SHA-256 published by the hub. A file that fails the check is deleted. Hub tokens are
sent only to their own host (`huggingface.co`, `civitai.com` / `civitai.red`) and never to CDN
redirects.

Repositories in Local Dream's QNN/MNN formats (e.g. [xororz](https://huggingface.co/xororz)) can be
downloaded and are labelled `LOCAL_DREAM`. They need Local Dream's own runtime: run Local Dream in
backend host mode and add it as a PC/LAN image backend.

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
| `read`, `write`, `edit`, `glob`, `grep`, `web_fetch`, `todo_write`, `ask_user_question`, `skill`, `AGENTS.md` | Same names, confined to a private `/workspace` (real paths are never shown to the model) |

Not ported (no equivalent on a phone, or later): `bash`/`pwsh` sandboxes, subagents, MCP, hooks
bridges, web search. The runtime is pure Kotlin and covered by JVM tests.

## Image generation

The Image screen talks to diffusion **backends** through one `DiffusionEngine` interface:

- **On-device** — stable-diffusion.cpp in the `:sd` process, with any downloaded image model
  and LoRAs. Works in Offline mode.
- **Local SSE engine** — `GET /health`, `POST /generate` streaming `progress` / `complete` /
  `error` events. It works with Local Dream's backend host mode.
- **A1111 / Forge / SD.Next API** (`/sdapi/v1`) — runtime LoRA via `<lora:name:w>`, checkpoint
  switching, samplers/schedulers, inpainting, live previews, interrupt on cancel.

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

The first build compiles llama.cpp and stable-diffusion.cpp, which takes a few minutes.

Windows (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug          # debug APK (arm64-v8a)
.\gradlew.bat testDebugUnitTest      # JVM unit tests
.\gradlew.bat :app:assembleRelease   # R8 full mode + resource shrinking (unsigned)
```

macOS/Linux:

```sh
./gradlew assembleDebug testDebugUnitTest :app:assembleRelease
```

If AGP fails with *"Several environment variables and/or system properties contain different paths
to the Android Preferences folder"*, unset `ANDROID_PREFS_ROOT` (it conflicts with
`ANDROID_USER_HOME`).

Release builds are configured for reproducibility:

- no dependency-metadata blob (`dependenciesInfo` off)
- no VCS info
- no build timestamps
- `android.util.Log` calls stripped by R8

Signing is left to the distributor (F-Droid / GitHub releases). For local signed builds, put a
git-ignored `keystore.properties` at the repository root:

```properties
storeFile=C:/path/to/autobot-release.jks
storePassword=…
keyAlias=autobot
keyPassword=…
```

When it exists, `:app:assembleRelease` produces a signed `app-release.apk`; otherwise the output stays
`app-release-unsigned.apk`.

### Qualcomm QAIRT / Genie (Phase 2)

Proprietary Qualcomm AI Runtime libraries are **not** redistributed. Download them yourself and place
them in `third_party/qairt/`. That directory is git-ignored, as are model files (`models/`, `*.gguf`,
`*.bin`, `*.safetensors`, `*.onnx`, `*.mnn`, `*.dlc`).

## Roadmap

1. **Skeleton & security**: modules, encrypted storage, app lock, chat with remote providers and
   the network kill switch. *(done)*
2. **On-device engines**: llama.cpp and stable-diffusion.cpp on CPU, plus a model manager with
   catalog, Hugging Face and Civitai sources, resumable verified downloads. *(done)* Next: GPU
   (OpenCL/Vulkan for Adreno) and a Genie NPU engine (QAIRT).
3. **Agent runtime**, modeled on the DeepSeek Harness plugin architecture: tools, permission gate,
   approvals, compaction, skills, workspace. *(done)* Next: MCP (streamable HTTP), subagents.
4. **Image generation**: Imagine UI, mask editor, LoRA, encrypted gallery, A1111 + SSE backends.
   *(done)* On-device stable-diffusion.cpp with LoRA. *(done)* Next: ONNX Runtime + QNN EP for
   the Snapdragon NPU. No code is taken from `xororz/local-dream` (CC BY-NC).
5. **Hardening**:
   - built-in Tor
   - audit log polish
   - panic wipe triggers
   - incognito improvements
6. **Experimental**:
   - video
   - `dsh` sidecar
   - baseline profiles

## License

Apache License 2.0. See [LICENSE](LICENSE).
