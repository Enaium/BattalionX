# BattalionX

A Kotlin Multiplatform reimplementation (port) of the Flash game **Battalion Vengeance**.

All game logic (rules engine, AI, turn system, data model) lives in `commonMain`; each desktop platform (JVM / macOS / Linux) only provides an entry point and asset loading. Graphics, audio and fonts are SDL3-based (via KMP bindings).

![](https://img.cdn1.vip/i/6a8b1cb818e19_1787501752.webp)

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
│   │   ├── campaign.json        # Ep3 campaign: mission list, briefs, medals, teams
│   │   └── bootcamp.json        # tutorial campaign
│   └── maps/                    # mission documents: Mission_1..10, bootcamp/, Kongregate_Bonus_Map
├── images/                      # sprite files, named by key (scorpion.png, sea_0_nw.png)
└── sounds/                      # sound effects + BGM, named by event name (ExplosionSound.mp3)
```

Every document is parsed with kotlinx.serialization against the `@Serializable` config models
in `data/AssetConfigs.kt` — there is no XML or XML-shaped JSON anywhere in the pipeline.
Unknown fields are ignored, so mods can annotate configs freely.

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
- `./gradlew jvmRun --args="assets --assetreport"` lists every sprite the game requests and
  every image that was never used (a mod checklist).

## Running

```bash
# Run directly (JVM desktop target) — assets/ is the default asset directory
./gradlew jvmRun --args="assets --mission 0"

# Arguments
#   <assetsDir>     the asset directory (default: assets)
#   --mission N     campaign mission N (0-based, default 0)
#   --skirmish x    free-for-all skirmish on assets/data/maps/x.json (name only, e.g. Mission_1)
#   --selftest      load the mission and tick the game loop without a window (startup self-check)
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

- The unpacked assets include **decompiled ActionScript sources** under `scripts/com/gamedozer/battalion/`
  (the original game logic: `GameManager`, controllers, HAL/AI, actions, triggers, UI), plus third-party
  libraries (flintparticles, Tweener, ElectroServer). The top-level `scripts/*.as` files are mostly
  symbol stub classes for embedded assets (e.g. a sound link class). Rendering follows the original
  source constants (e.g. `GAME_WIDTH = 560`).
- Rendering uses the original tilesheet frames at 1:1 scale where possible; no horizontal stretching.
