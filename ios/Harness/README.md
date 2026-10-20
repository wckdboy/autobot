# Harness — on-device DeepSeek Harness

The **Harness tab** boots a Linux guest (Alpine 3.21 aarch64 + Node 22 + `@deepseek-ai/dsh`)
inside the iSH-ARM64 emulator and shows the harness web UI over loopback — no jailbreak,
no server, no Mac in the loop. Compute can be extended to a paired **Invincible** node
from the same screen.

## Layout

- `dsh-ios/` — vendored from [everettjf/dsh-ios](https://github.com/everettjf/dsh-ios)
  (GPL-3.0), including the iSH-ARM64 emulator sources (`ish-arm64/`). Its UIKit screens
  are not compiled; Autobot supplies a native SwiftUI / Liquid Glass surface instead.
- `HarnessKernel.h/m` — the boot path ported from iSH's AppDelegate (guest root mount,
  device nodes, DNS, `/sbin/init`), plus the exit/die hooks.
- `gen-harness-spec.rb` → `HarnessSpec.yml` — generates the XcodeGen fragment that adds
  the emulator sources, the libarchive project reference, the meson build phase and the
  rootfs copy step to the Autobot target. Re-run after vendoring changes:
  `ruby ios/Harness/gen-harness-spec.rb`.
- `mach_excServer.h/.c` — pre-generated MIG exception server (no build rule needed).
- `build/root.tar.gz` — the guest image (built, not committed).

## One-time tooling

```sh
brew install meson ninja lld libarchive
gem install xcodeproj          # the generator reads iSH.xcodeproj
```

## Build the guest image (~6 min)

```sh
export PKG_CONFIG_PATH=/opt/homebrew/opt/libarchive/lib/pkgconfig:$PKG_CONFIG_PATH
make -C ios/Harness/dsh-ios emulator   # host iSH CLI + fakefsify
make -C ios/Harness/dsh-ios rootfs     # ios/Harness/dsh-ios/build/root.tar.gz
```

The Xcode build runs the meson/ninja step per configuration and copies `root.tar.gz`
into the app bundle (warning only when it is missing).

## Licensing

`dsh-ios/` and the emulator are **GPL-3.0** (iSH / iSH-ARM64). Autobot itself is
Apache-2.0; distributing this build requires the whole app to be provided under
GPL-3.0-compatible terms. See `dsh-ios/LICENSE` and `dsh-ios/LICENSE.md`.
