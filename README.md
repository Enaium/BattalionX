# BattalionX

A Kotlin Multiplatform reimplementation (port) of the Flash game **Battalion Vengeance**.

All game logic (rules engine, AI, turn system, data model) lives in `commonMain`; each desktop platform (JVM / macOS / Linux) only provides an entry point and asset loading. Graphics, audio and fonts are SDL3-based (via KMP bindings).

![](https://img.cdn1.vip/i/6aa6b3928e8df_1789309842.webp)

## Assets

All game resources live under `assets/` in the project root (versioned, no runtime unpacking):

```
assets/
├── data/                        # game data as typed JSON documents
│   ├── units/<id>.json          # ONE file per unit (stats + sprite sheet + sounds)
│   ├── terrain/<id>.json        # ONE file per terrain/structure type
│   ├── modifiers/
│   │   ├── unit/<id>.json       # ONE file per unit rule modifier (Flak, Transport, ...)
│   │   └── terrain/<id>.json    # ONE file per terrain rule modifier (Water, Cover, ...)
│   ├── ballistics.json          # weapon-type x armor-type damage multipliers
│   ├── sprites.json             # sheet slicing: regions/grids for avatars, explosions, cursor
│   ├── campaigns/
│   │   ├── nemesis.json         # Battalion Nemesis: 10 missions (maps/ep1/)
│   │   ├── skirmish.json        # Battalion Skirmish: 1 mission (maps/sk1/)
│   │   ├── ghosts.json          # Battalion Ghosts: 10 missions (maps/ep2/)
│   │   ├── vengeance.json       # Battalion Vengeance: 10 missions (maps/ep3/)
│   │   └── bootcamp.json        # tutorial campaign
│   └── maps/                    # mission documents, one directory per generation
├── images/                      # sprite files, named by key (scorpion.png, sea_0_nw.png)
└── sounds/                      # sound effects + BGM, named by event name (ExplosionSound.mp3)
```

Every document is parsed with kotlinx.serialization against the `@Serializable` config models
in `data/AssetConfigs.kt` — there is no XML or XML-shaped JSON anywhere in the pipeline.
Unknown fields are ignored, so mods can annotate configs freely.

### Game generations

The mission select lists every game generation as one button per campaign document, in release
order: **Nemesis** (Battalion 1), **Skirmish** (Battalion Skirmish at Daul), **Ghosts**
(Battalion Ep2), **Vengeance** (Battalion Ep3) and the **Boot Camp** tutorial. Any level is
playable directly — there is no unlock gate; the medal row only shows the medals already earned.

Levels are the generations' own maps (converted 1:1 from the original `.yimf` documents: terrain
grid, structures, starting units, scripted events and dialogue). Their vehicles, troops and
structures are drawn with Vengeance's art, while generation-exclusive art — the portraits used
by dialogue, the side panel and the results screen, plus the mission-select header — comes from
that generation's own tilesheet:

| Campaign (`campaigns/<id>.json`) | Header art | Portrait sheet (`sprites.json`) |
| --- | --- | --- |
| `nemesis` | `campaign_header_jungle` | `avatars_nemesis` (150×150 cells) |
| `skirmish` | `skirmish_campaign_header` | `avatars_skirmish` (130×150 cells) |
| `ghosts` | `ep2_campaign_header` | `avatars` (shared with Vengeance) |
| `vengeance` | `ep3_campaign_header` | `avatars` |
| `bootcamp` | `default_header` | `avatars` |

The main menu carries the original's button row (Continue / Single Player / Multiplayer /
Options / **Level Editor**, the editor not being ported), the Bonus Map button top-left and the
Credits button top-right — the latter opens the scrolling `CreditsMenu` overlay.

Each level can be played from either side: the mission select's faction buttons (the team
colours flanking the mission arrows) pick which of the mission's teams the player commands —
left of the left arrow lists the teams (the player's side is boxed, defaulting to the side the
mission assigns to the player), right of the right arrow lists the opponents. The match always
opens with the player's turn, whichever side was picked (`GameLoader.withPlayerTeam`).

### Per-unit configuration (`units/warmachine.json`)

```json
{
  "name": "Warmachine",
  "unitType": "ground", "movementType": "tracked",
  "weaponType": "medium", "armorType": "medium",
  "cost": 2500, "range": 3, "health": 75, "damage": 60,
  "weaponRangeMin": 2, "weaponRangeMax": 3,
  "description": "The Warmachine is the cornerstone of your army.",
  "modifiers": ["Constructor", "Skysweeper", "Extractor", "Linchpin", "Unbuildable"],
  "sprite": {
    "sheet": "warmachine",            // images/warmachine.png — 6 columns × 4 rows
    "facingColumns": [0, 1, 2, 3],    // facing N/E/S/W -> column
    "cellWidth": 56, "rows": 4,
    "firingSheet": "warmachine_firing", "firingCellWidth": 144
  },
  "audio": { "move": "TankMove1", "fire": "" }
}
```

The `sprite` block IS the sprite contract: which sheet to use, how it is sliced into cells,
which column each facing maps to, and which dedicated firing sheet (with its cell size) plays
during an attack. No pixel offsets or name-mangling heuristics live in the code.

Terrain configs carry a `sprites` block the same way (`variants` for interchangeable textures,
`composite` for 2×2 sea/shore/canyon mosaics, `single` for one fixed image), and map palette
entries may override it per entry.

### Modding

- **New unit**: drop `units/my-unit.json` (+ its sheets under `images/`) and reference it from
  a map palette entry.
- **New terrain / modifier / sound**: same — one file per item.
- **New map**: drop `maps/mymap.json`; add it to a campaign's `levels` to make it playable.
- **New generation / level list**: drop `campaigns/mygame.json` (+ its maps) and add its id to
  `GameLoader.CAMPAIGN_IDS` to give it a button in the mission select.
- `./gradlew jvmRun --args="assets --assetreport"` drives every campaign, missions select page
  and menu, then lists every sprite the game requests and every image that was never used
  (a mod checklist).

## Running

```bash
# Run directly (JVM desktop target) — assets/ is the default asset directory
./gradlew jvmRun --args="assets --mission 0"

# Arguments
#   <assetsDir>     the asset directory (default: assets)
#   --campaign id   campaign generation for --mission (nemesis | skirmish | ghosts |
#                   vengeance | bootcamp; default vengeance)
#   --mission N     mission N of --campaign (0-based, default 0), e.g. "--campaign nemesis --mission 3"
#   --skirmish x    free-for-all skirmish on assets/data/maps/x.json (e.g. ep3/Mission_1)
#   --selftest      load the mission and tick the game loop without a window (startup self-check)
#   --menushot      capture the main menu and every generation's mission select to PNGs
```

Other targets:

```bash
./gradlew jvmTest                       # run tests
./gradlew fatJar                        # build a runnable fat jar
./gradlew compileKotlinMacosArm64       # compile the macOS native target
./gradlew compileKotlinLinuxX64         # compile the Linux native target
```

On macOS, `jvmRun` already passes `--enable-native-access=ALL-UNNAMED -XstartOnFirstThread`.

## Layout

```
src/
├── commonMain/kotlin/cn/enaium/battalion/
│   ├── data/        # data model: units, maps, terrain, ballistics, mission loading
│   └── game/        # game engine: action system, AI (HAL), triggers, rendering, UI
└── jvmMain/         # JVM entry point (CLI argument parsing)
```

## Notes

- The Mission select loads one campaign document per game generation (`campaigns/nemesis.json`,
  `skirmish.json`, `ghosts.json`, `vengeance.json`, `bootcamp.json`); each level list is switched
  with the buttons above the action row. Every level is selectable from the start — winning one
  no longer unlocks the next, it only records its medal.
- The unpacked assets include **decompiled ActionScript sources** under `scripts/com/gamedozer/battalion/`
  (the original game logic: `GameManager`, controllers, HAL/AI, actions, triggers, UI), plus third-party
  libraries (flintparticles, Tweener, ElectroServer). The top-level `scripts/*.as` files are mostly
  symbol stub classes for embedded assets (e.g. a sound link class). Rendering follows the original
  source constants (e.g. `GAME_WIDTH = 560`).
- Rendering uses the original tilesheet frames at 1:1 scale where possible; no horizontal stretching.
