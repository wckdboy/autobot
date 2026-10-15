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

## Phase 2 · 1.4: a stronger agent *(1.4.0: MCP, memory, search, providers, OS fallback; 1.4.1: subagents; 1.4.2: voice)*

- ✅ **MCP client** (streamable HTTP) in `:shared`. Each server gets its own approval scope and
  network route, and every call appears in the audit log. *(implemented on both platforms against
  MCP 2026-07-28 servers with fallback to 2025-11-25 / 2025-06-18 / 2025-03-26; still to do:
  verification against real servers, OAuth sign-in, MCP servers in the `.autobot` export)*
- ✅ **Subagents**: the DSH `task` tool, so parallel workers report back to one session. *(1.4.1:
  in `:agent:core`, so both apps; a subagent inherits the model, preset and grants, its approvals
  appear on the parent's card, and its steps are listed under the report)*
- ✅ **Local memory**: an encrypted, user-visible, editable notes store the agent can recall from.
  Off by default.
- ✅ **Web search tool**: opt-in, through a provider the user configures (SearXNG, Brave and others),
  routed and audited like `web_fetch`.
- ✅ **Voice mode**: push-to-talk dictation → agent → read-aloud loop, all on the device.
  *(both apps; hands-free end-of-speech detection; offline voices only. In progress: Kokoro-82M
  as a neural voice, Core ML on iOS and ONNX on Android, with an Apache-licensed English frontend
  and no espeak-ng)*
- **Subscriptions used well**: OpenCode Go as a verified preset, per-model data-policy labels
  (no retention / retention / trains on your data), a cheaper *helper model* per provider for
  subagents, summaries and titles, and clear quota-window messages with an opt-in fallback.
- ✅ **Every provider, live model lists**: all major clouds plus open-model hosts and niche
  providers (e.g. abliteration.ai), each verified against its docs; model ids fetched from the
  provider when a key is added (through the kill switch and audit log), never stale hard-coded slugs.
- ✅ **OS models as fallback**: "This phone" falls back to the system's on-device model (Apple
  Foundation Models on iOS 26+; Gemini Nano via AICore on Android only where it does not pull Google
  Play Services into the default build) when no downloaded model fits or loads, always labelled.

**Exit:** an agent can use an MCP server, delegate to a subagent and recall a memory, offline
where possible, on both platforms.

## Phase 2.5: privacy you can see *(planned; runs alongside Phase 3)*

What no other client does, built on what Autobot already has (kill switch, audit log, on-device
models, shared core). Ordered by value.

1. ✅ **Privacy receipts.** Every answer carries a receipt: which hosts were contacted, how many bytes
   left, the provider's retention policy for that model, and which tools ran. One tap from the
   message; built from the audit log, never sent anywhere. *(done on both platforms: requests
   carry their agent turn into the audit log, receipts are `turn/receipt` events in the session
   log)*
2. **Redaction gateway.** Before a cloud call, an on-device model finds names, emails, phone
   numbers, addresses, account numbers and the user's own memory facts, swaps them for stable
   placeholders, and puts the real values back into the answer locally. The provider sees
   `[PERSON_1]`, the user sees their own words. Visible in the receipt.
3. **Local-first cascade.** "This phone" answers first; a small on-device judge decides whether the
   answer is good enough, and only then offers to escalate to the cloud (with the redacted prompt).
   Per-chat policy: never / ask / auto for low-risk prompts.
4. **Taint tracking for tools.** Data that came from memory, attachments or private files is
   tagged; when the agent tries to put tainted data into a network tool (web_fetch, web_search,
   MCP), the approval card highlights exactly which private fragment would leave. Defends against
   prompt-injection exfiltration without blocking normal browsing. *(done, both apps: memory notes,
   recalled memory and workspace file contents are indexed per session as emails, numbers, id-like
   tokens, names and verbatim phrases; network tool arguments are checked case-folded, URL- and
   base64-decoded; a hit always asks, even under full access or for a trusted MCP server, and is
   denied when approvals are off. What the user typed never counts; subagents share the parent's
   taint; it rebuilds from the log after a restart. Images: an email, number or id the model was
   never shown as text in a session with an attached image is treated as read off the image.
   Not yet: private MCP servers as a source.)*
5. ✅ **Private vault (on-device RAG).** Encrypted embeddings of the user's notes, files, PDFs and OCR'd
   photos, built on the NPU/Neural Engine, searchable by the agent (`vault_search`) and by the user.
   Nothing indexed leaves the phone. *(done, both apps, off by default: Qwen3-Embedding 0.6B (Q8_0,
   Apache-2.0) in llama.cpp's embedding mode, cut to 512 dimensions; ~512-token passages with
   overlap, int8 vectors in SQLCipher / the encrypted store, exact cosine search with MMR up to
   50k passages; text, Markdown and PDF on both, photos with Vision OCR on iOS; results are taint
   sources. Not yet: Android OCR, the NPU/Neural Engine path for embeddings (CPU/Metal today).)*
6. **Pair your own devices.** End-to-end encrypted (Noise) pairing between the user's phones and
   computers over LAN or Tailscale: an iPhone can borrow the OnePlus NPU or a desktop GPU as "my
   own cloud"; sessions and the vault sync without a server.
7. **On-device agents on a schedule.** Recipes ("every morning: summarise these feeds over Tor") run
   with BGTaskScheduler / WorkManager on local models, with results as notifications and receipts.
8. ✅ **System entry points.** App Intents / Shortcuts, the Action button, widgets, Android
   Quick Settings tile and share targets: "Ask Autobot" about a screenshot, a selection or a photo,
   answered on device. *(done, both apps: iOS App Intents "Ask Autobot" / "Start Voice Chat" /
   "Generate Image" with Siri phrases, a share extension, a launcher widget, a lock screen voice
   widget and a Control Center voice control (also on the Action button); Android share target,
   "Ask Autobot" in the text selection menu, a Quick Settings voice tile, launcher shortcuts and a
   launcher widget. Requests wait behind the app lock; widgets, tiles and controls are static and
   never show chat content. Only an App Intent the user runs sends a prompt; shared, selected or
   linked text only fills the composer. New chats follow "incognito by default" and start with
   tools off. Shared content goes through the App Group with complete file protection, metadata
   stripped, deleted on pickup and swept after an hour. No background answers: that would hand a
   transcript to Shortcuts past the app lock.)*
9. **Wake word on device.** A tiny always-on keyword model on the NPU for hands-free voice, off by
   default, with a visible indicator.
10. **Your own arena.** Blind side-by-side answers from two models; your votes build a local
    preference profile that tunes the cascade and model suggestions. Never uploaded.
11. ✅ **Undo for agents.** Workspace snapshots per turn, a timeline of what the agent changed, and
    one-tap revert. *(done, both apps: `write`, `edit` and `delete` keep the file's previous
    content in a copy-on-write journal next to the workspace (session · turn · call; 50 MB per
    chat, oldest turns dropped with a notice, files over 10 MB not copied); step rows show a line
    diff with "Undo", a turn's last answer "Undo turn (N files)", and a "Files changed" sheet
    groups the session's changes by turn. Undoing a step also undoes later edits to the same file;
    a revert is journaled so it can be redone, and a `workspace/revert` event tells the model
    which files moved back. Subagent changes count for the parent's turn.)*
12. **Spend and quota board.** Tokens, money and subscription windows (OpenCode Go 5 h / week /
    month) per provider, computed locally from responses.

**Exit:** a cloud answer comes with a receipt and a redacted prompt by default; the vault and the
cascade work fully offline on the 8 Elite Gen 5 and a current iPhone.

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
