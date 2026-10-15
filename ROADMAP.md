# Autobot roadmap

Autobot is a private AI agent that runs on the phone's own accelerators: Snapdragon NPU/GPU on
Android, Neural Engine/Metal on iOS. Both apps are built from one shared core.

## Goals

1. **On-device first.** Every feature works offline on the phone's fastest accelerator. The
   network is an opt-in tool, never a requirement.
2. **Private by construction.** Nothing leaves the device unless the user can see it in the audit
   log and could have switched it off. No telemetry, ever.
3. **One app, two platforms.** Android and iOS ship the same features, version and behaviour.
   Logic lives in `:shared` (Kotlin Multiplatform) or the shared C cores; only UI and OS glue is
   per platform.
4. **The riso feel.** One signal red on ink or paper, Antonio and JetBrains Mono, halftones.
   Design tokens come from `shared/design/tokens.json` on both platforms.

## Release rules

- Versions move together: `scripts/bump-version.sh X.Y.Z` sets Android and iOS (same build number).
- App id `ai.wckd.autobot` on both platforms (Android since 1.2.1).
- Android: signed APKs on GitHub releases (key: `autobot-release-2026`). iOS: TestFlight.
- A phase is done when its exit criteria hold on **both** platforms and on real hardware.

---

## Phase 0 · 1.2.x: ship and verify *(in progress)*

Get 1.2 into hands, prove the accelerator paths on real hardware, and close the gaps the port left.

| | Item | Platform |
|---|---|---|
| ✅ | llama.cpp on the Hexagon NPU (V73–V81), Adreno OpenCL, QNN images | Android |
| ✅ | iOS app on the shared Kotlin runtime, Metal llama.cpp, Core ML SD, whisper.cpp | iOS |
| ✅ | Same app id and version on both; `bump-version.sh` | both |
| ✅ | TestFlight build live (`ai.wckd.autobot`) | iOS |
| ✅ | **Diagnostics report**: Compute → "copy report" with SoC, backends that loaded, per-backend benchmark and engine logs, no personal data. This makes hardware bug reports actionable | both |
| ⬜ | Hardware verification on the 8 Elite Gen 5: chat on NPU/GPU/CPU, SD 1.5 + SDXL on QNN, MNN on GPU, upscaler | Android |
| ✅ | Design-token parity test: Android `Theme.kt` must match `tokens.json` | Android |
| ✅ | iOS build fails loudly when the Kotlin framework build fails | iOS |
| ✅ | iOS background downloads: every redirect hop through the kill switch | iOS |
| ✅ | Compute benchmark: image step time excludes model load | iOS |
| ✅ | Tool card → open the exact gallery image | iOS |
| ✅ | dSYMs for the engine frameworks (symbolicated TestFlight crash logs) | iOS |

**Exit:** TestFlight and GitHub builds of the same version. A diagnostics report from an 8 Elite
Gen 5 shows chat and images on the NPU.

## Phase 1 · 1.3: one core

Move the remaining duplicated logic into `:shared`, so a fix lands on both platforms at once.

- ✅ **Network policy in `:shared`.** `RouteResolver`, loopback rules, kill-switch decisions and the
  audit log, with one test suite. Android OkHttp and iOS URLSession become thin adapters.
  *(done in 1.2.2 on both platforms)*
- ✅ **Settings schema in `:shared`.** The same keys, defaults and validation; DataStore and the iOS
  store just persist it. *(1.3.0)*
- ✅ **Model management in `:shared`.** Fit rules (`FITS`/`TIGHT`/`TOO BIG`), Hugging Face and Civitai
  clients, resumable SHA-256 download state machine, catalog. Per-platform image catalogs: QNN/MNN
  on Android, Core ML on iOS.
- ✅ **Encrypted export/import.** A `.autobot` bundle (sessions, gallery, providers without keys),
  sealed with a passphrase. It moves a user between Android and iOS. *(1.3.0; needs a
  real-device round trip Android ↔ iPhone)*
- ✅ **`generate_image` agent tool on iOS.** *(1.2.3)*
- ✅ **NPU upscaler on iOS** (Core ML Real-ESRGAN on the Neural Engine). *(1.2.3)*

**Exit:** `:shared` holds network, settings and model logic; both apps pass the same test suite. A
session exported on Android opens on iOS.

## Phase 2 · 1.4: a stronger agent

- **MCP client** (streamable HTTP) in `:shared`. Each server gets its own approval scope and
  network route, and every call appears in the audit log. *(implemented on both platforms against
  MCP 2026-07-28 servers with fallback to 2025-11-25 / 2025-06-18 / 2025-03-26; still to do:
  verification against real servers, OAuth sign-in, MCP servers in the `.autobot` export)*
- **Subagents**: the DSH task tool, so parallel workers report back to one session.
- **Local memory**: an encrypted, user-visible, editable notes store the agent can recall from.
  Off by default.
- **Web search tool**: opt-in, through a provider the user configures (SearXNG, Brave and others),
  routed and audited like `web_fetch`.
- **Voice mode**: push-to-talk dictation → agent → read-aloud loop, all on the device.
- **Every provider, live model lists**: all major clouds plus open-model hosts and niche
  providers (e.g. abliteration.ai), each verified against its docs; model ids fetched from the
  provider when a key is added (through the kill switch and audit log), never stale hard-coded slugs.
- **OS models as fallback**: "This phone" falls back to the system's on-device model (Apple
  Foundation Models on iOS 26+; Gemini Nano via AICore on Android only where it does not pull Google
  Play Services into the default build) when no downloaded model fits or loads, always labelled.

**Exit:** an agent can use an MCP server, delegate to a subagent and recall a memory, offline
where possible, on both platforms.

## Phase 3 · 1.5: faster on the metal

- **Hexagon tuning**: Q4_0 catalog variants that repack well for HTP, flash attention on HTP,
  benchmark-driven defaults per SoC.
- **Speculative decoding** with a small draft model, on the GPU or NPU.
- **Images**: LoRA on the NPU, SDXL resolutions beyond 1024, Anima packages, Core ML LoRA merge
  tooling.
- **Speech**: whisper encoder on QNN (Android) and Core ML (iOS); streaming dictation.

**Exit:** on the 8 Elite Gen 5 and a current iPhone, a 4B chat model generates ≥ 2× faster than
CPU-only 1.1.

## Phase 4 · 2.0: hardened and public

- **Built-in Tor** on both platforms (Arti), with no external Orbot needed.
- **Panic triggers**: duress PIN, wipe after N failed unlocks.
- **Incognito upgrades**: incognito image runs, no gallery writes.
- **Reproducible Android builds** and an F-Droid listing; signed CI releases.
- **App Store release** after TestFlight hardening.
- **Baseline profiles** and startup time budgets.

## Later / experimental

Video generation · home-screen widgets and shortcuts (App Intents / Android shortcuts) · a `dsh`
sidecar for desktop pairing · on-device fine-tuning of LoRAs.
