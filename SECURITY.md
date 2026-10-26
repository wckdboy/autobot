# Security

Autobot's privacy rules are its security boundary: what the kill switch blocks, what the audit
log records, what stays encrypted, what an approval card stops. A way around any of them is a
vulnerability, and so is anything that lets someone else's code pass as an Autobot release.

## Reporting a vulnerability

Report it privately through GitHub: this repository's **Security** tab › **Report a
vulnerability** ([new advisory](https://github.com/wckdboy/autobot/security/advisories/new)).
If the Security tab has no **Report a vulnerability** button, open an issue titled
`security contact` with no details; a maintainer will open a private advisory and invite you to it.
Only you and the maintainers see the report; the fix, the advisory text and the credit are
worked out there.

Please do not describe it in a public issue, pull request or discussion.

A useful report names the app version and platform (Settings › About), the steps, what you
expected and what happened. Models → Compute → `report` adds the device and engine details
without personal data. Leave out real prompts, API keys and pairing codes.

## Scope

In scope:

- **The Android app**, both the published `foss` build and the opt-in `playServices` build.
- **The iOS app**, including the Harness tab's supervision of its guest.
- **The shared core** both apps run (`:shared`, `:agent:core`): the network policy and kill
  switch, the audit log, redaction, the agent runtime and its approvals, taint checks, the
  `.autobot` export format, the PIN and wipe rules, and the phone's side of node pairing and
  receipt checks.
- **Release signing**: the Android signing key and certificate, `scripts/release.sh`,
  `.github/workflows/android-release.yml`, `SHA256SUMS.txt`, and the pinned build inputs (the
  engine submodules, the Hexagon library hashes in `engine/llama/build.gradle.kts`).

For example: a request that gets out in Offline mode or leaves no audit entry; a tool call that
runs without the approval its preset requires; data at rest that can be read without its key; a
redacted value that reaches a cloud provider; a node receipt accepted with a bad signature; a way
past the PIN, the failed-unlock wipe or the duress PIN; a release asset that verifies but was not
built from its tag.

Not in scope here:

- **The Invincible node and the `autobot` agent on your machine** (`autobot serve`). Report
  those to [Invincible](https://github.com/wckdboy/invincible) the same way, through its Security
  tab or a `security contact` issue there. When a flaw sits in what the app and the node must
  agree on (pairing, receipts, the agent protocol), report it there and say that the app is
  affected too; the two fixes are coordinated.
- **Upstream engines and libraries** (llama.cpp, whisper.cpp, stable-diffusion.cpp, MNN,
  Qualcomm's QNN runtime, dsh-ios, iSH-ARM64). Report those upstream, and tell us here when the
  version Autobot ships is affected.
- **What a model says.** A jailbreak or a wrong answer is not a vulnerability unless it makes the
  app break one of its own rules above, such as a prompt injection that sends private data out
  without an approval card.
- Attacks that need root on the phone or a modified build of the app.

## Response targets

These are targets, not guarantees:

- A report is acknowledged within **3 days**.
- A **high or critical** issue (the severity set in the advisory, CVSS) has a fix, or a plan
  with a date, within **14 days**.
- Lower severities are fixed in a coming release.

You are kept up to date in the advisory and credited in it unless you ask not to be. Please keep
the details private until the advisory is published; if a fix takes longer than planned, a
disclosure date is agreed with you there. There is no bug bounty.

## How fixes ship

Only the latest release gets fixes; Android and iOS always ship the same version.

1. **A patch release** of both apps: the signed APK on
   [GitHub releases](https://github.com/wckdboy/autobot/releases) with `SHA256SUMS.txt`, and the
   same version on TestFlight.
2. **A GitHub security advisory** on this repository, published once the patch release is out,
   naming the affected and fixed versions. A CVE is requested through GitHub when the issue
   warrants one.
3. **Verify the APK before you install it.** Its signing certificate must have the SHA-256 given
   in the [README](README.md#install):

   ```sh
   apksigner verify --print-certs autobot-X.Y.Z-release.apk
   # Signer #1 certificate SHA-256 digest: 9a1378acf9587885e7920355558c85a1ec0e89304f58c153feebf90fdee7f3f1
   shasum -a 256 -c SHA256SUMS.txt --ignore-missing
   ```

   An APK signed with any other key is not an Autobot release. Settings › About on Android shows
   whether the installed app matches the release key.
