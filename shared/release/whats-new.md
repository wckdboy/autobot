A calmer Android app: much less text, fewer taps, and the same Settings layout as iPhone.

## Less text, fewer taps (Android)
- **Settings** is grouped into Connections / Agent / Appearance / Security / Data / About. Rows show their state on the right (providers "1", Privacy "Direct", Vault "Off") instead of a subtitle. Explanations are behind ⓘ.
- **Chat with a downloaded model: 2 taps instead of 3.** A new chat opens with the keyboard up. Tapping Chat again reuses an empty chat instead of piling up "New chat" rows.
- **Generate an image: 2 taps instead of 3.** The prompt is focused, and the Generate bar now sits above the keyboard instead of under it.
- **Shorter everywhere.** Chat rows show the model's name ("Qwen3.5 0.8B") and "just now". Model rows drop licence and footnote text, and download progress shows once, in the panel. Remote, Privacy Center, Vault, Imagine and Studio lose their paragraphs: one short line and one button where something needs doing.

## Look
- Living cards match iPhone exactly: a fixed 320 dp wave at 0.45 rad, period 12 s. Every dot stays visible at the wave's low point.
- Run screen cards fade their dots under the title and model name, so the text stays legible.

## Files
- `autobot-1.6.4-release.apk`: signed release (`ai.wckd.autobot`, arm64-v8a, Android 14+). Signer certificate SHA-256 `9a1378acf9587885e7920355558c85a1ec0e89304f58c153feebf90fdee7f3f1`.
- `autobot-1.6.4-debug.apk`
- `autobot-1.6.4-mapping.txt`: R8 mapping, for reading crash reports
- `SHA256SUMS.txt`

iOS: TestFlight build 1.6.4 (no iOS changes since 1.6.3).
