# BattalionX

A Kotlin Multiplatform reimplementation (port) of the Flash game **Battalion Vengeance**.

All game logic (rules engine, AI, turn system, data model) lives in `commonMain`; each desktop platform (JVM / macOS / Linux) only provides an entry point and asset loading. Graphics, audio and fonts are SDL3-based (via KMP bindings).

![](https://img.cdn1.vip/i/6a87265fb762b_1787242079.webp)

## Assets

All game resources live under `assets/` in the project root (versioned, no runtime unpacking):

```
assets/
├── data/                # game data as JSON (one document per file)
│   ├── terrainmodifiers.json / unitmodifiers.json   #   modifier rules (imported first:
│   │                                                 #   terrain/units reference them by name)
│   ├── terrain.json     #   terrain + structure definitions
│   ├── units.json       #   unit definitions
│   ├── units_arena.json #   arena unit set (replaces base entries by name)
│   ├── ballistics.json
│   ├── campaign.json    #   Ep3 single-player campaign (mission list, briefs, medals)
│   ├── bootcamp.json    #   tutorial campaign
│   └── maps/            #   mission documents: Mission_1..10, bootcamp/, Kongregate_Bonus_Map
├── images/              # sprite/texture files, named by sprite key (scorpion.png, sea_0_nw.png)
└── sounds/              # sound effects + BGM, named by event name (ExplosionSound.mp3)
```

The data documents use an XML-shaped JSON layout — every element is
`{"name", "attrs", "children", "text"}` (see `JsonXml.kt`) — so mods ship by editing or
adding files here: swap a sprite under `images/`, add a map under `data/maps/`, tweak a unit
under `data/units.json`. `./gradlew jvmRun --args="assets --assetreport"` lists every sprite
name the game requests (a mod checklist: any requested sprite with no backing file is reported).

## Running

```bash
# Run directly (JVM desktop target) — assets/ is the default asset directory
./gradlew jvmRun --args="assets --mission 0"

# Arguments
#   <assetsDir>     the asset directory (default: assets)
#   --mission N     campaign mission N (0-based, default 0)
#   --skirmish x    free-for-all skirmish on assets/data/maps/x.json (name only, e.g. Mission_1)
#   --selftest      load the mission and tick the game loop without a window (startup self-check)
#   --assetreport   drive every mission/menu and report requested sprites missing a file
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
