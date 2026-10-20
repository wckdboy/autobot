# Working on Autobot

## Every change that ships updates four places

When the app changes (a release, a feature, a status, a limit, the brand), update in the same pass:

1. **The website.** autobot.sh (and sovware.io's product list if the one-line role changes).
   Source: [wckdboy/autobot-www](https://github.com/wckdboy/autobot-www) (`src/views/`,
   `src/data/`); refresh release data with `GITHUB_TOKEN=$(gh auth token) pnpm data`, then
   `pnpm build:all` and `npx wrangler deploy --config deploy/autobot.wrangler.jsonc`.
2. **The GitHub README** of this repo (status table, features, limits) and `ROADMAP.md`.
3. **Plane.** The matching work items and their states.
4. **The brand kit** if colors, symbols or icons change: `brand/kit.json` in autobot-www is the
   source; copy the generated files into `shared/design/brand/`, keep `shared/design/tokens.json`
   in step and update `BRAND.md`.

A release (`scripts/release.sh X.Y.Z notes.md`) is not done until the site shows it, the README
says it and Plane has it.
