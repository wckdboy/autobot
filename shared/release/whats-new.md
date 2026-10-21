The DeepSeek Harness itself, running on your iPhone.

## Harness tab (iOS)
- **A Linux agent workspace on the phone.** The Harness tab boots Alpine Linux with Node inside the iSH-ARM64 emulator and runs the DeepSeek Harness's own web UI, served on the device. No server, no Mac, no jailbreak.
- **Your key, once.** A DeepSeek provider with an API key in Autobot signs the harness's model calls; otherwise the harness asks for one and keeps it in its guest.
- **The same privacy rules.** The guest runs only while a network mode allows a route out: Offline stops it. The audit log records that it is running and where its calls go.
- **Guest shell and log.** A shell into the running guest (`apk add`, `npm i -g`, a look at `~/.dsh`) and a shareable harness log, from the tab's menu.
- **It recovers.** A page that fails, a web process that dies or a server that stopped in the background is reloaded; after three failed loads it says so and waits for Reload.
- Experimental: the first boot imports the 85 MB guest image, a warm boot takes about a minute, and the emulator is slow. iPhone and iPad only.

## Licence
- The iOS app now includes GPL-3.0 components (dsh-ios, iSH-ARM64), so the iOS build is distributed under GPL-3.0 as a combined work. Autobot's own code stays Apache-2.0, and so does the Android app. Settings › Licenses lists the notices.
