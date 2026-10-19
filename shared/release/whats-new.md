My node on iPhone too, what your machine did at a glance, recipes that run on your node, and the team of agents your machine can now run.

## My node on iOS
- **Pair your computer** in Settings › Providers › + › My node (Invincible). Paste the code, or open the `invincible://pair` link from the camera.
- **Chats, signed receipts and "phone first, then my node"** all work as on Android.
- **The agent screen.** Remote › Agents · my node: tasks on that machine, approval cards, and scheduled tasks.

## What your machine did (Android and iOS)
The list button on the agent screen opens the node's Activity, read from its own logs:
- **Requests:** every request the node answered: who asked, which model, tokens, and whether anything left. Each receipt is checked against the key pinned at pairing.
- **Actions:** what the agent ran, and who allowed it.
- **Memory and skills:** what the agent remembered (you can make it forget), and your skills.

## Recipes on your node
- **Runs on** in the recipe editor: this phone, or your node.
- **On the node,** a recipe runs on its schedule, always on and with no 8-minute limit, on the node's models, with only the recipe's tool groups (web included).
- **Your time, wherever the node is.** "07:00" means 07:00 where the phone is.
- **Results come back** as chats in the Recipes folder, with the node's signed receipts and a notification.

## A team on your machine
- **Team members.** When the node's agent has a team, each member's answer shows under its name.
- **You know who's asking.** An approval says which member wants it ("coder: Run a command?").
- **New approval cards** for web fetches and MCP tools.

## Fixes
- Several CI tests that depended on timing are steadier.
