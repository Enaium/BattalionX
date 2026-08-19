# BattalionX

A Kotlin Multiplatform reimplementation (port) of the Flash game **Battalion Vengeance**.

All game logic (rules engine, AI, turn system, data model) lives in `commonMain`; each desktop platform (JVM / macOS / Linux) only provides an entry point and asset loading. Graphics, audio and fonts are SDL2-based (via KMP bindings).

![](https://img.cdn1.vip/i/6a85bfd754855_1787150295.webp)

## Assets

The project ships no assets. Unpack them from the original SWF:

```
<assetsDir>/
├── images/        # extracted texture PNGs
├── binaryData/    # mission / unit XML data (.bin)
├── sounds/        # sound effects and BGM (.mp3)
└── scripts/       # dialogue scripts
```

## Running

```bash
# Run directly (JVM desktop target)
./gradlew jvmRun --args="/path/to/assets --mission 0"

# Arguments
#   <assetsDir>     path to the unpacked assets
#   --mission N     campaign mission N (0-based, default 0)
#   --skirmish x    free-for-all skirmish on map file x.bin
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
