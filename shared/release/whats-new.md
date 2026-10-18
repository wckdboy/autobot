Your own computer as Autobot's next tier: pair a machine running Invincible, chat with its models, hand its agent tasks you approve from the phone, and check what it signed.

## My node (Android)
- **Pair a computer.** Install Invincible on a desktop or VPS (`curl -fsSL https://raw.githubusercontent.com/wckdboy/invincible/main/install.sh | sh`), run `invincible pair`, and scan the code with the camera app (or paste it). Settings › Providers › My node (Invincible). No account, no relay: the phone and the node pin each other's keys (Noise).
- **Chat with its models** like any provider. Connections follow the kill switch (offline refuses them; Tor and SOCKS5 carry them with remote DNS) and show in the Privacy Center's audit log.
- **Signed receipts.** Every answer from the node is checked against the receipt key pinned when you paired: the receipt line says "kept on studio ✓" when nothing left the node, "studio → 1 host" when it used a cloud model (with the hosts, routes and what it redacted), or "studio unverified".
- **Phone first, then my node.** With "phone first" on a node chat, this phone answers first and a weak answer goes to your node instead of a cloud model. The node is hardware you own, so a sensitive prompt may go there and "Never" allows it; these turns are marked private, so the node never sends them on to a cloud model.

## The agent on your machine (Android)
- **REMOTE › Agents · my node.** Give the Autobot Agent running on that machine a task: shell commands and file work, on its models. Every command, file write and read outside its working folder comes to the phone as a card first: Allow, Always (this conversation) or Deny.
- **Scheduled tasks.** The calendar button lists what the agent runs on a schedule (next run, how the last one went), adds tasks (cron, @daily, every 30m) and removes them. Scheduled runs only do what you allowlisted.

## Fixes
- Node chats no longer show a REDACT chip: nothing is redacted for hardware you own.
- The streaming check with a real node leaves room for thinking models.

iOS: the pairing and receipt code is shared and tested on CryptoKit; the iOS node connector comes next, so "My node" says "not on iOS yet".
