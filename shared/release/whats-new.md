Cleaner prints, a faster start, and dictation that keeps up: a polish release.

## Cleaner ink (both platforms)
- **No more doubled dots.** On Android, the paper behind the Image screen, chat, voice, Models, Gallery and the lock drew every dot twice, with a faint copy a few pixels off. Paper is one plate again.
- **Prints land on their own dots.** When a result printed, its colour plate started half a dot away and read as a second grid. It now starts as a thin rim on the same dots and settles.
- **Pictures print in where they show.** A wide image in a square chat thumbnail or a Studio chip no longer prints a stretched copy next to the cropped one.
- One dot field per screen: the voice panel, the empty Models search and empty chat no longer stack a second field under the first, and image proofs sit on whole pixels.
- iOS: a halftone preview always matches its own image. A new image could pick up an old image's dots.

## Faster (both platforms)
- **Android starts sooner.** The app now ships a Baseline Profile, so startup code runs precompiled even on a sideloaded install. On the emulator: 13–17% less time to the first real screen and about 20% less main-thread work.
- **iOS starts sooner.** Fonts and device checks load in the background, about 90 ms earlier to the first frame in the simulator.
- **Live dictation shows words sooner and more often.** Partial passes only encode the audio heard so far, 2–4× less work. Stopping usually has nothing left to transcribe.
- **Faster draft-model checks.** Each speculative step costs less, a draft that failed to load is retried later, and a failed check carries on without the draft instead of ending the answer.
- **Image model warm-up.** It loads again ahead of Generate after it was freed, switching models no longer loads both, and on Android it stays loaded while the Image screen is open.
- Smoother lists: the iOS chat list scrolls with fewer hitches, and Android's Models list only redraws rows that changed. Catalog previews and parsed chat text have a memory limit on iOS.

## Fixed
- Read-aloud no longer cuts off the last words before the assistant uses a tool.
- Talking over a spoken answer no longer triggers by itself when the voice resumes after a pause.
- iOS: the last words you say before tapping stop are no longer lost from dictation.
- A quick double tap on the mic starts one dictation, not two.
- The Image screen no longer jumps when the style row appears, and its readouts stay readable at large text sizes.
- Android: tabs and switches grow with large text instead of clipping, the chat title fits on 360 dp phones, and unlocking no longer flashes the app for a frame.
- Usage: limits read "1 of 10 requests"; on iOS a limit typed as 1,000,000 saves correctly, a mistyped number can't be saved, and quick edits in a row all stick.
- iOS Studio: a video ending no longer stops another, Play/Pause shows the right state after coming back, and Play replays a finished clip.
- Android: the Compute benchmark explains a failed draft check in words, not an engine code.
- The image model is no longer reported as loaded after it was freed for another engine, and a malformed draft output can't pick a wrong token.

## Files
- `autobot-1.7.1-release.apk`: signed release (`ai.wckd.autobot`, arm64-v8a, Android 14+). Signer certificate SHA-256 `9a1378acf9587885e7920355558c85a1ec0e89304f58c153feebf90fdee7f3f1`.
- `autobot-1.7.1-debug.apk`
- `autobot-1.7.1-mapping.txt`: R8 mapping, for reading crash reports
- `SHA256SUMS.txt`

iOS: TestFlight build 1.7.1.
