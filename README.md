# Autobot

A privacy-first AI agent for Android. Autobot is built to run models **on the device** (NPU/GPU) and
treats every network request as something the user has to opt into, can see, and can switch off.

> Status: **Phase 3 (agent) + Phase 4 (image generation) landed.** On top of the Phase 1 skeleton,
> security layer and encrypted storage, chats now run through an on-device port of the DeepSeek
> Harness agent architecture (tools, permission gate, approvals, compaction), and the Imagine
> tab drives Stable Diffusion backends (txt2img, img2img, inpainting, LoRA). On-device text
> engines and the native diffusion sidecar are next.

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
  - There is no HTTP cache, no cookies and no redirect following.
- **Locked down.**
  - `FLAG_SECURE` is set and recents screenshots are disabled.
  - Optional app lock using biometrics or the device credential, with an auto-lock timeout.
  - Backups and device transfer are disabled.
  - HTTPS uses system CAs only. Cleartext is allowed only for `localhost`, `127.0.0.1` and `10.0.2.2`.
  - The keyboard is asked not to learn from what you type (`IME_FLAG_NO_PERSONALIZED_LEARNING`).
- **Incognito chats.** These are held in memory only and never touch the database.
- **Panic wipe.** Type `WIPE` to destroy Keystore keys, wrapped keys, databases, DataStore,
  secrets and caches. The process is then killed.
- **Minimal permissions.** The app requests only `INTERNET` and `USE_BIOMETRIC`.

## Module map

| Module | Responsibility |
| --- | --- |
| `:app` | `AutobotApplication` (Hilt), `MainActivity` (FragmentActivity, edge-to-edge, FLAG_SECURE), Navigation 3 `NavDisplay` with the SESSIONS · IMAGINE · GALLERY · SYSTEM bar, lock screen, manifest and network security config |
| `:core:designsystem` | "Underground" Material 3 theme (ink surfaces, hairlines, acid/ultraviolet accents, mono chrome labels, hard 2–6 dp corners), `Panel`, `MicroLabel`, `Readout`, `Tag`, `ValueSlider`, `Segmented`, `SelectField`, `Stepper`, `ConsoleTextField`, `TerminalBlock`, `AutobotNavBar`, custom stroke icons, `ChatBubble`, `ThinkingDisclosure` |
| `:agent:core` | Pure-Kotlin port of the DeepSeek Harness runtime: Cordis-style `Context` (services, hooks, reversible effects, injecting plugins), append-only session log + `deriveMessages`, ReAct step/turn loop, tool pipeline, permission presets + fail-closed approvals, retry, tool-result pruning, compaction |
| `:agent:runtime` | Android host: `AgentHost`, provider → `LlmAdapter` bridge (DeepSeek `reasoning_content` echo), encrypted session store, `/workspace` sandbox with `read`/`write`/`edit`/`glob`/`grep`/`delete`, `web_fetch`, `AGENTS.md` instructions, skills |
| `:core:diffusion` | `DiffusionEngine` API, A1111/Forge engine (`/sdapi/v1`), local HTTP+SSE engine, backend registry |
| `:feature:imagine` | Imagine screen (txt2img / img2img / inpaint, LoRA stack, sampler, canvas, seed, batch), mask editor, encrypted gallery, backends manager, `generate_image` agent tool |
| `:core:security` | `KeyManager` (Keystore AES-GCM, StrongBox fallback, wrapped DB passphrase), `SecretStore`, `AppLockManager`, `PanicWipe`, pure-JVM `AesGcmEnvelope` |
| `:core:network` | `NetworkPolicy`, `KillSwitchInterceptor`, `HeaderScrubInterceptor`, `AuditLog`, `RouteResolver`, `HttpClientFactory` |
| `:core:data` | Room + SQLCipher (`Conversation`, `Message`, `Provider`), repositories, DataStore settings, `IncognitoSession`, settings→policy sync |
| `:providers:remote` | `ChatProvider` SSE streaming: OpenAI-compatible, DeepSeek (`reasoning_content`), OpenRouter, Ollama; `ProviderFactory` |
| `:feature:chat` | Session list (live indicators, incognito) and agent session screen (streaming, reasoning, tool cards with inline approvals, plan/todos, questions, context gauge, slash commands, model switcher) |
| `:feature:settings` | Settings, Privacy Center (network mode, audit log, panic wipe), Providers editor (masked key, routing, test connection) |
| `build-logic/` | Convention plugins: `autobot.android.application`, `autobot.android.library`, `autobot.android.compose`, `autobot.hilt` |

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

The Imagine tab talks to diffusion **backends** through one `DiffusionEngine` interface:

- **Local SSE engine** — `GET /health`, `POST /generate` streaming `progress` / `complete` /
  `error` events. This is the protocol of the planned on-device engine sidecar (loopback, so it
  works in Offline mode) and it interoperates with Local Dream's backend host mode.
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
- Android SDK with platform `android-37.0`.

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

Signing is left to the distributor (F-Droid / GitHub releases).

### Qualcomm QAIRT / Genie (Phase 2)

Proprietary Qualcomm AI Runtime libraries are **not** redistributed. Download them yourself and place
them in `third_party/qairt/`. That directory is git-ignored, as are model files (`models/`, `*.gguf`,
`*.bin`, `*.safetensors`, `*.onnx`, `*.mnn`, `*.dlc`).

## Roadmap

1. **Skeleton & security**: modules, encrypted storage, app lock, chat with remote providers and
   the network kill switch. *(done)*
2. **On-device text engines**: a Genie NPU engine (QAIRT) and a llama.cpp GPU engine, plus a model
   manager for downloads, checksums and storage.
3. **Agent runtime**, modeled on the DeepSeek Harness plugin architecture: tools, permission gate,
   approvals, compaction, skills, workspace. *(done)* Next: MCP (streamable HTTP), subagents.
4. **Image generation**: Imagine UI, mask editor, LoRA, encrypted gallery, A1111 + SSE backends.
   *(done)* Next: on-device engine sidecar speaking the SSE protocol — stable-diffusion.cpp (MIT)
   for CPU/Vulkan/OpenCL with runtime LoRA, and ONNX Runtime + QNN EP for Snapdragon NPU. Needs the
   NDK; no code is taken from `xororz/local-dream` (CC BY-NC).
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
