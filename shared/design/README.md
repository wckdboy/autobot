# Design tokens

`tokens.json` is the single source of truth for the design system on both platforms (the rules
are in [BRAND.md](../../BRAND.md)): warm ink (dark) and paper (light), one signal, lilac, used
sparingly, the system sans for headings and reading, JetBrains Mono for every label. `products`
holds each product's signal: Autobot's lilac, and Invincible's verdigris for anything about
the node. Antonio stays in the font set for the few places that print a single large word.

- **Android**: the values are written out in
  `core/designsystem/src/main/kotlin/dev/wckdboy/autobot/core/designsystem/theme/Theme.kt` and the
  components; a unit test asserts they match this file.
- **iOS**: `ios/scripts/gen-tokens.swift` turns this file into
  `ios/App/Design/Tokens.generated.swift` (committed). `TokensTests` fails if the generated file
  drifts from the JSON. Run `swift ios/scripts/gen-tokens.swift` from the repository root after
  editing; `--check` only verifies.

Font files are not copied: the Xcode project references
`core/designsystem/src/main/res/font/antonio.ttf` and `jetbrains_mono.ttf` directly.

## Schema

Everything is flat: an object of name → value per group. Colours are `#RRGGBB` (all opaque);
lengths are dp on Android and points on iOS (1 dp ≈ 1 pt); type sizes are sp / pt.

| Key | Contents | Kotlin source |
| --- | --- | --- |
| `palette` | Raw colours | `AutobotColors.*` (camelCase of the Kotlin name: `Ink950` → `ink950`) |
| `aliases` | Semantic aliases → palette name | `AutobotColors.Acid`, `Green`, `Signal`, `Uv`, `Violet` |
| `dark` | Every colour role of the dark (ink) scheme | `GraphiteDarkColorScheme` |
| `light` | Roles Theme.kt sets explicitly for the light (paper) scheme | `GraphiteLightColorScheme` |
| `lightMaterialDefaults` | Light roles Theme.kt leaves to Material 3's baseline (`lightColorScheme()` defaults); kept so iOS has a complete scheme. Not part of the Android assertion. | — |
| `amoled` | Dark-scheme overrides for pure-black panels | `ColorScheme.toAmoled()` |
| `fonts` | Families and the weights Android registers | `DisplayFontFamily`, `CodeFontFamily` |
| `type` | One entry per text style: `font` (`display` / `mono` / `sans`), `size`, `lineHeight`, `weight` (100–900), `letterSpacing`, `trim` (`LineHeightStyle.Trim.Both`), optional `tabular` (`tnum`) | `AutobotTypography` (Material names) and `AutobotExtraStyles` (`code`, `codeBlock`, `micro`, `readout`, `number`) |
| `radius` | Corner radii | `AutobotShapes` (`extraSmall` … `extraLarge`) |
| `stroke` | Line widths: hairlines, borders, icon strokes, the reasoning rule, selection outlines | `Hairline`, `BorderStroke(1.dp)`, `AutobotIcons` (`strokeLineWidth = 1.75f`), `ThinkingDisclosure`, proof selection |
| `spacing` | Paddings, gaps and fixed sizes used by the components | `Riso.kt`, `Underground.kt`, `NavBar.kt`, `Controls.kt`, `StatusChips.kt`, `ChatBubble.kt`, `ThinkingDisclosure.kt` |
| `halftone` | Parameters of `HalftoneImage`, `HalftoneField` and the `ModeCard` fade | `Riso.kt` |
| `motion` | `StatusDot` pulse | `Underground.kt` |

Notes:

- `sans` styles (`titleMedium`, `titleSmall`, `bodyLarge`, `bodyMedium`, `bodySmall`) copy Material 3
  defaults and override some fields. Their `lineHeight` / `letterSpacing` / `weight` are the
  resulting values (e.g. `titleMedium` keeps Material's 24 sp line height). The family is the
  platform's system sans (Roboto / SF Pro).
- `code` and `codeBlock` have no explicit weight in Kotlin; 400 is Compose's default.
- Adding a colour role means adding it to both `dark` and `light` (or `lightMaterialDefaults`):
  the generator refuses schemes with different role sets.
