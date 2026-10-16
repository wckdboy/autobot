Clear Settings and a new About screen, honest engine labels, the right image defaults, and smoother screens. Tuned on a real OnePlus 15.

## Settings and About (both platforms)
- **Settings is laid out in one clear order:** Privacy & security › Agent › Appearance › Data › About. Every setting has exactly one home. Providers, image backends and accounts live in Remote, and Panic wipe lives only in Settings.
- **About** shows your version, build number and build date, and this release's "What's new". The notes are built into the app, so they work offline. "All releases" and "Source code" open in your browser. It also has licenses, the privacy statement ("No analytics. No telemetry. Network only where you allow it.") and Copy diagnostics.
- **Android:** About shows the release signing certificate and checks it against the APK you're running, so you can verify your install is genuine.

## Labels you can trust
- Route tags show the engine actually doing the work.
  - Android: NPU, GPU or CPU, for images and for chat (from what the engine reports).
  - iPhone: Neural Engine or GPU for images, GPU (Metal) for chat.
- "CPU" no longer appears for models running elsewhere.

## Better image defaults
- Picking a model applies its recommended steps, guidance, sampler and size, without overriding anything you changed. Illustrious DMD2 now opens at 8 steps and 1024² instead of 20 steps at 512². SDXL models never default to 512².

## Cleaner screens
- **Run:** the status line fits, the card grid is aligned, the image art is clean, subtitles wrap instead of cutting off, and an empty "Recent runs" is hidden.
- **Models:** the phone header fits; installed rows read like "image · NPU · 4.5 GB".
- No halftone dots behind text or numbers anywhere.
- **iPhone:** nothing is cut off on the iPhone 17e or at extra-large text, and the 17e shows its chip name.

## Smoother
- **Android:** halftone fields are drawn as batched dots. On the OnePlus 15, Imagine's dropped frames halved (22% → 11%), and slow frames went from 42 ms to 16 ms (p90).
- **iPhone:** screens hidden under the Studio stop animating (stutters 3–6 → 0). Studio Library reloads thumbnails 4× less often while scrolling, and Imagine at rest uses half the main-thread time.

## Measured on a OnePlus 15 (Snapdragon 8 Elite Gen 5)
- Qwen3.5 0.8B: CPU 49 tok/s generation, NPU 576 tok/s prompt reading. After the Compute benchmark, Auto picks the fastest engine.
- Illustrious SDXL DMD2 on the NPU: 1024², 8 steps in 8.8 s with the model loaded (17 s cold).

## Files
- `autobot-1.6.5-release.apk`: signed release (`ai.wckd.autobot`, arm64-v8a, Android 14+). Signer certificate SHA-256 `9a1378acf9587885e7920355558c85a1ec0e89304f58c153feebf90fdee7f3f1`.
- `autobot-1.6.5-debug.apk`
- `autobot-1.6.5-mapping.txt`: R8 mapping, for reading crash reports
- `SHA256SUMS.txt`

iOS: TestFlight build 1.6.5.
