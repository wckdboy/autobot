# Autobot for iOS

The iOS port of Autobot: the same privacy-first, on-device AI agent, the same riso design (one
signal red on ink or paper, Antonio display type, JetBrains Mono labels, halftone rendering), the
same screens, copy and flows as Android — in SwiftUI, Swift 6, iOS 18+.

## Build and run

Requirements: Xcode 26 or newer (tested with Xcode 27 / iOS 27 SDK), [XcodeGen](https://github.com/yonaskolb/XcodeGen)
2.46+, an Apple-silicon Mac. For the shared agent runtime: JDK 17 (`brew install openjdk@17`).

```sh
cd ios
scripts/bootstrap-engines.sh      # once: llama.cpp / whisper.cpp xcframeworks (~80 s, only if missing)
scripts/build-shared.sh Debug     # once: Kotlin agent runtime → Frameworks/AutobotShared.xcframework
xcodegen generate                 # the .xcodeproj is not committed
xcodebuild -project Autobot.xcodeproj -scheme Autobot \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=27.0' build
xcodebuild -project Autobot.xcodeproj -scheme Autobot \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=27.0' test
# TestFlight / App Store
xcodebuild -project Autobot.xcodeproj -scheme Autobot -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/Autobot.xcarchive archive -allowProvisioningUpdates
```

The `AutobotEngines` package and `AutobotShared.xcframework` are always linked. A scheme
pre-action rebuilds the Kotlin framework with Gradle for the active configuration on every build
(Debug → debug framework, Release/archive → release framework; JDK 17 from Homebrew's
`openjdk@17` unless `JAVA_HOME` is set; log in `Frameworks/build-shared.log`) and builds the
engine frameworks if they are missing. Set `AUTOBOT_SKIP_SHARED_BUILD=1` to reuse the existing
Kotlin framework. Xcode resolves Swift packages before pre-actions run, so on a fresh checkout run
`scripts/bootstrap-engines.sh` once before opening the project. Simulator builds are arm64 only
(the native and Kotlin frameworks ship arm64 simulator slices). In the Simulator llama.cpp and
whisper.cpp run on the CPU, and the Neural Engine is unavailable.

`AutobotShared` is required: besides the agent runtime it holds the network rules both apps run
(`NetworkMode` codec, `RouteResolver`, `KillSwitch`, `Loopback`, `AuditLog`) and the model
management (catalogs incl. Core ML, fit rules, Hugging Face / Civitai requests and parsing, the
download state machine). `Core/Network` only adapts the network rules to URLSession, `Core/Models`
moves bytes (background URLSession), hashes, persists and mirrors the shared model types as
Codable values (`SharedModels.swift`); the Swift tests double as cross-platform conformance tests. (`InterimAgentRuntime`, plain streaming chat without tools, remains as an agent-runtime
fallback in the source.)

The app ships `App/Resources/PrivacyInfo.xcprivacy` (no tracking, nothing collected,
required-reason APIs: user defaults, disk space, boot time, file attributes) and declares
`ITSAppUsesNonExemptEncryption = NO` (standard crypto for data at rest and HTTPS only).

**Signing.** Bundle id `ai.wckd.autobot`, team `XKA8CGC2AB`, automatic signing. Open
`Autobot.xcodeproj`, pick your device, Run. The app uses the Data Protection entitlement
(`NSFileProtectionComplete`); Xcode adds the capability to the App ID automatically.
Two extensions are embedded: `ai.wckd.autobot.share` (share sheet) and `ai.wckd.autobot.widgets`
(widgets and the Control Center control). The app and the share extension share the App Group
`group.ai.wckd.autobot`; automatic signing registers it on the first device build (with
`-allowProvisioningUpdates` from the command line), or add it once under Certificates, IDs &
Profiles → Identifiers → App Groups and enable it for both App IDs.

**Versions** follow Android (`app/build.gradle.kts`): `MARKETING_VERSION` 1.2.0,
`CURRENT_PROJECT_VERSION` 3, set in `project.yml`.

**Screenshots** of every screen in dark and light are in `screenshots/` (taken with the demo data
below).

**Debug launch arguments** (Debug builds only, like Android's debug `allow_screenshots` extra):
`-AutobotScreenshots` skips the app lock and the app-switcher cover; `-AutobotDemo` seeds an
empty install with sample sessions, providers, gallery images and audit entries;
`-AutobotOpen <screen>` (run, sessions, chat, agent, imagine, gallery, models, compute, accounts,
remote, settings, privacy, providers, backends, licenses, memory) starts on that screen.

```sh
xcrun simctl launch booted ai.wckd.autobot -AutobotScreenshots -AutobotDemo
```

## Design tokens

`shared/design/tokens.json` is the single source of truth for colours (ink and paper schemes,
AMOLED), the type scale, radii, strokes, spacing and halftone parameters; see
`shared/design/README.md`. `swift ios/scripts/gen-tokens.swift` writes
`App/Design/Tokens.generated.swift` (committed); `TokensTests` fails when it drifts. The fonts are
referenced from the Android design system (`core/designsystem/src/main/res/font/*.ttf`, not
copied) and registered with `UIAppFonts`; both are variable fonts, weights are set on the `wght`
axis like Android's `FontVariation`. `swift ios/scripts/gen-icons.swift` renders the app icon and
the brand mark from the Android vector drawables and the launch colour from the tokens.

## Architecture

```
App/
  AutobotApp.swift      entry, lock gate, welcome, privacy cover
  AppContainer.swift    object graph (Hilt on Android)
  Navigation/           one back stack, tab roots RUN · MODELS · REMOTE · SETTINGS
  Design/               riso design system (1:1 with :core:designsystem)
  Features/             screens (Home, Chat, Imagine, Models, Settings)
  Core/Network          NetworkPolicy, RouteResolver, Loopback, AuditLog, HttpClient (kill switch)
  Core/Security         AesGcmEnvelope, KeyManager, SecretStore, AppLockManager, PanicWipe
  Core/Data             EncryptedStore (SQLite + AES-GCM), repositories, preferences
  Core/Models           shared-model bridge, downloader, FileFetcher (bytes), hubs (I/O), device profile, compute profile
  Core/Diffusion        image backends (A1111, SSE, Core ML), generator
  Providers/            OpenAI-compatible SSE + on-device chat (fallback path, see below)
  Agent/                AgentRuntime: SharedAgentRuntime (KMP) / InterimAgentRuntime
  Engines/              the only files that import AutobotEngines
Tests/                  Swift Testing ports of the Android JVM tests
```

**Agent runtime.** Every chat, agent and code session runs through `AgentRuntime`.
`SharedAgentRuntime` drives the Kotlin `AgentBridge` from `AutobotShared.xcframework` — the same
DeepSeek-Harness port Android runs (tools, permission gate, approvals, plans, questions,
retries, compaction, skills, `/workspace`). Kotlin owns the loop, the OpenAI codec / SSE decoder
and the transcript projection; Swift provides the HTTP transport (`URLSessionTransport`, through
the kill switch), the on-device engine (`LocalEngineHost` over `LlamaEngine`), encrypted session
logs, decrypted attachments and the provider list, and renders the `SessionState` JSON. The
session log is the same `SessionEvent` JSON lines on both platforms.

**Network.** The rules are the shared Kotlin ones (see above). Every request goes through `HttpClientFactory.create(tag:override:)`: an ephemeral
`URLSession` (no cache, cookies or credential storage), the kill switch before any I/O (Offline
blocks everything but loopback; cleartext only to loopback; a route change after the client was
built drops the request), header scrub (`User-Agent: Autobot`, fixed `Accept-Language`, no
Referer/Cookie/Origin/tracking headers), no automatic redirects, and the in-memory audit log
(time, method, host, path, sizes, tag, route, blocked — never headers, query or body). Tor and
SOCKS5 use `URLSessionConfiguration.proxyConfigurations` (SOCKSv5, failover off, host names
passed to the proxy); a provider's Direct override can never downgrade a global Tor/SOCKS mode.
ATS allows HTTPS only, plus `localhost` and local-network addresses (the kill switch still
rejects cleartext to anything but loopback). Switching to Offline cancels requests in flight.

**Storage.** `EncryptedStore` uses the system SQLite: rows hold only opaque ids; every payload is
a Codable value sealed with AES-256-GCM (`AesGcmEnvelope`, byte-compatible with Android's) under
a random 256-bit database key with `table:id` as associated data. Data keys are kept in the
Keychain (`WhenUnlockedThisDeviceOnly`) and, on devices with a Secure Enclave, wrapped by a P-256
enclave key (ECDH → HKDF-SHA256 → AES-GCM). Gallery images and chat attachments are sealed files
under the media key. API keys and hub tokens are Keychain items; `Secret` redacts itself from
descriptions and mirrors. Everything lives in `Application Support/Autobot`, excluded from
iCloud/iTunes backup, with `NSFileProtectionComplete` (models use
`completeUntilFirstUserAuthentication` so downloads can finish while locked). Incognito
sessions never leave process memory. Panic wipe (type `WIPE`) stops agents, downloads and
generation, deletes every Keychain item, the database, files, caches and defaults, and exits.

## Parity

| Android | iOS | Notes |
| --- | --- | --- |
| Riso theme, Compose components | `App/Design` (same tokens, fonts, icons) | `tokens.json` is shared |
| Welcome, lock (BiometricPrompt) | Welcome, lock (Face ID / Touch ID / passcode, `LocalAuthentication`), auto-lock timeout on a monotonic clock | |
| `FLAG_SECURE`, no recents thumbnail | Privacy cover while not active | iOS cannot block screenshots |
| Run screen (CPU state) | Run screen (chip, idle/busy, thermal state, free storage) | |
| Sessions list, agent session screen | Same | tools, approvals, plans, questions via the shared runtime |
| `AgentHost` (`:agent:runtime`), `generate_image` tool | `SharedAgentRuntime` over `AutobotShared.xcframework`; `generate_image` is a host tool (`AgentBridge.registerTool`) on the Core ML / PC backends | interim plain chat without the framework |
| llama.cpp CPU / Adreno OpenCL / Hexagon (planned) | llama.cpp on Metal or CPU | no ANE backend in llama.cpp; "npu" falls back |
| System model: Gemini Nano (opt-in `playServices` flavor only) | Apple Intelligence (`system:apple`, Foundation Models, iOS 26+, on-device only; `Engines/AppleIntelligence.swift`) | fallback when no downloaded model can run, chosen by the shared `LocalModelResolver`; FoundationModels is weak-linked so iOS 18–25 still launch |
| Images: sd.cpp (CPU), QNN on Hexagon NPU, MNN on GPU | Core ML Stable Diffusion on the Neural Engine (`split_einsum`) or GPU | Apple's `coreml-stable-diffusion-*` repos |
| LoRA on device | — | Core ML models are compiled; LoRA works on A1111/Forge backends |
| NPU 4× upscaler (tiled 192 px) | Real-ESRGAN 4× on the Neural Engine (Core ML, tiled 512 px, cross-faded overlaps) | `mlboydaisuke/Real-ESRGAN-x4-CoreML`, BSD-3-Clause |
| whisper.cpp dictation | whisper.cpp dictation (Metal, Core ML encoder when present) | |
| Text-to-speech | `AVSpeechSynthesizer` | |
| Model manager, catalog, HF browser, accounts | Same; image catalog is Core ML | chat/code/speech entries shared |
| Civitai browser | Browser and account; checkpoints/LoRAs are for PC backends | Core ML cannot load them |
| Foreground download service | Background `URLSession` | resume by Range, SHA-256 verified |
| Compute screen (CPU/GPU/NPU) | CPU / GPU (Metal) / Neural Engine, force backend, benchmark, auto = fastest | |
| A1111/Forge and SSE backends | Same | |
| MCP servers (Room row + Keystore token) | MCP servers (`EncryptedStore` row + Keychain token) | client, tools and approval gate are shared Kotlin |
| Encrypted gallery, export to `Pictures/` | Encrypted gallery, export to Photos (add-only, confirmed) | |
| Room + SQLCipher | SQLite + per-row AES-GCM | no third-party crypto |
| Android Keystore / StrongBox | Keychain + Secure Enclave | |
| OkHttp interceptors | `HttpClient` kill switch | same tests |
| Orbot (Tor) | External SOCKS app (e.g. Orbot for iOS) at `127.0.0.1:9050` | no built-in Tor |
| Dynamic color, AMOLED | AMOLED only | Material You has no iOS equivalent |
| `IME_FLAG_NO_PERSONALIZED_LEARNING` | Secret fields are `SecureField`s (keyboards do not learn); prompt/URL fields disable autocorrection | iOS has no per-field "do not learn" flag |

## Not available on iOS, and why

- **Hexagon NPU / QNN / MNN** — Qualcomm hardware. Images run on the Apple Neural Engine through
  Core ML instead; chat runs on the GPU (Metal) because llama.cpp has no ANE backend.
- **Built-in Tor** — iOS apps cannot run a Tor daemon in the background reliably; use a SOCKS
  app such as Orbot for iOS and pick Tor (127.0.0.1:9050) or SOCKS5.
- **Blocking screenshots** — iOS has no `FLAG_SECURE`; the app hides content in the app switcher.
- **On-device LoRA and arbitrary checkpoints** — Core ML models are precompiled; use a PC backend.
- **Exporting to shared storage without asking** — export writes to Photos only after a
  confirmation, with add-only access.
