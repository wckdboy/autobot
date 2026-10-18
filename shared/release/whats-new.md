Images come out the shape you pick, a full-screen viewer, recipes that run on their own, phone-first answers and a panic PIN.

## Images (both platforms)
- **16:9 means 16:9.** Picking a wide or tall shape could still give a square: on-device models compiled for one size (Core ML on iOS, QNN on Android) quietly swapped your size for theirs. They now render their square and return your shape cut from it, and Tune says "compiled for 1024² · other shapes are cut from the square".
- **The resolution is a pixel budget.** 16:9 at 1024 is about a million pixels in that shape (1368×768), so wide images keep the detail of the square. Every shape chip names the exact size it makes, and the picker remembers your choice instead of snapping back to 512.
- Picking a model keeps your shape instead of resetting to its square. MNN sizes round to the nearest 64 instead of always down.
- A server that sends back a square anyway is cut to your shape (Android) or named in a notice (iOS), and the gallery records the size it really got.
- **Full-screen viewer.** Tap a result, a gallery image or an image in chat: pinch or double-tap to zoom, pan, swipe through the batch, swipe down to close. The info sheet shows the real pixel size and every setting used.
- **Do more with an image:** use it for img2img (prompt and settings carried over, strength showing), inpaint, vary, reuse settings, same seed, upscale 4× (when an upscaler is installed), share, save, copy settings, favorite, delete.
- The agent's `generate_image` takes an aspect ratio and keeps the shape when given one side.
- img2img without an input image now says so instead of quietly running as text-to-image; Tune says when a model can't apply your LoRAs.

## Recipes (both platforms)
- **On-device agents on a schedule.** Settings → Recipes: a prompt, a schedule, a model and the tools it may use. Runs in the background on the phone (WorkManager on Android, background processing on iOS), once for a missed time, safe across daylight-saving changes.
- A private notification opens the result chat after the app lock. While the phone is locked, iOS says "Unlock to run <name>". Recipe data is encrypted and goes with a wipe.

## Phone first (both platforms)
- **The phone answers first.** Off by default. Settings → Privacy → Phone first (ask / auto / never) and a chip per chat: in a cloud chat the installed on-device model answers, and a quick judge decides whether the answer is good enough.
- **Ask** offers "Ask <model>" under a weak answer; **Auto** sends it on only when nothing personal, health, money, attachment or memory related is involved; **Never** keeps it on the phone. A follow-up passes the redaction gateway, and the receipt says what happened and why.

## Panic protection (both platforms)
- **App PIN** with back-off after wrong tries, an optional **wipe after N failed unlocks**, and a **duress PIN** that wipes and opens as a fresh app. The wipe survives being interrupted and can keep downloaded models.
- **Incognito images** stay out of the gallery: they're marked "Not saved" in chat and Imagine, and only Save keeps them.

## Accessibility (both platforms)
- Red, amber, cyan and UV text now reads at WCAG AA contrast on both ink and paper.
- Android holds at 200% font size: crowded rows stack, labels wrap instead of cutting off, segmented tabs are 48 dp tall. An automated audit renders the main screens at 200%.
- iOS follows Dynamic Type up to the largest accessibility sizes, icons and tap targets grow with the text, and thumbnails tell VoiceOver they're buttons.

## Files
- `autobot-1.8.0-release.apk`: signed release (`ai.wckd.autobot`, arm64-v8a, Android 14+). Signer certificate SHA-256 `9a1378acf9587885e7920355558c85a1ec0e89304f58c153feebf90fdee7f3f1`.
- `autobot-1.8.0-debug.apk`
- `autobot-1.8.0-mapping.txt`: R8 mapping, for reading crash reports
- `SHA256SUMS.txt`

iOS: TestFlight build 1.8.0.
