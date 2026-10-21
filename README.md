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
Options / **Level Editor**), the Bonus Map button top-left and the
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

### Mods

A mod is a folder under `mods/` — beside the assets directory, in the directory the program is
started from, so mods survive replacing `assets/` — with a `manifest.json`; it never replaces a
game document, it patches one. Both layers are built once per assets directory
(`ModRegistry.load`) and every document and media lookup goes through them.

```
mods/my-mod/
├── manifest.json          # id, version, name, authors, license, dependencies,
│                          # loadAfter/loadBefore, game compatibility, icon (a resource id)
├── data/patches/          # JSON Patch (RFC 6902), mirroring data/'s own layout
│   ├── units/scorpion-tank.json     -> patches data/units/scorpion-tank.json
│   ├── units/crystal-scorpion.json  -> creates that document (a root "add")
│   └── campaigns/my-mod.json        -> a campaign document of its own
├── images/                # media by RESOURCE ID, not by path: images/ui/icon.png -> "ui/icon"
└── sounds/
```

- **Data is patch-only.** `[{"op":"replace","path":"/cost","value":300}]` edits the loaded
  document; the result is always "base data + patches in load order", so a later mod wins on a
  path and nothing a mod ships can silently drop a game field. `test` operations make conflicts
  loud: a patch that expects a value another mod already changed fails, is reported on the mods
  screen and the rest of that document is kept.
- **Media is namespaced and overlayable.** A file at `images/<id>.<ext>` provides the resource
  id `<id>`; a mod file with an id the game already uses replaces it (later mods win), and
  `mod.id:<id>` pins a lookup to one mod. Documents reference ids only.
- **Load order** comes from `dependencies` / `loadAfter` / `loadBefore`; a mod whose dependency
  or game-version window cannot be satisfied is skipped with a reason instead of breaking the
  game. Everything the loader rejected shows up in the main menu's **Mods** screen.
- **Mod maps**: a mod that adds map documents gets a **Mod Maps** button in the mission select
  listing them (clicking plays one); a mod that adds a campaign document appears in the
  generation switch like the built-in generations.
- `examples/mods/` ships three worked examples (a map + campaign, a data patch, a resource
  override with its own icon); copy one into `mods/` to try it. The runtime `mods/` directory is
  git-ignored, and every mod can be switched off from the main menu's Mods screen.
- `./gradlew jvmRun --args="assets --assetreport"` drives every campaign, missions select page
  and menu, then lists every sprite the game requests and every image that was never used
  (a mod checklist).

### Map editor

The map editor is a port of the editor that ships inside **BattalionVengeance** itself
(`scripts/editor`, the same code the standalone `BattalionVengeanceEdit` SWF was built from):
its 700×600 interface (`EditorInterface`), its three-layer map model and its terrain rule
engine. It runs on SDL directly — no third-party UI toolkit. All editor art comes from the
game's own embedded `EditorAssets` (`editor_frame`, `editor_highlight`, `editor_radio`, the
tab/overlay buttons), not from the Edit SWF.

The game build of the editor is unreachable without the online service, which is why it cannot
be entered in the original: its front end (`SlotSelect`) lists the logged-in user's maps from
the server (`DjangoBroker list/user/...`), Save is gated on a non-guest subscription, and the
save panel offers "Publish Online?" with Premium/Elite checks. The standalone Edit build keeps
the same `CustomMap`/`RuleMapper`/rule documents and drops only those online paths (and adds a
local new/import flow). This port keeps the standalone flow (local documents) and adopts the
game build's data and presentation wherever they differ:

| Difference between the two builds | Port |
| --- | --- |
| Game UI labels are English; Edit labels are Chinese | English UI, game tab art ("Terrain / Units / Building") |
| Game Save is account/publish gated (`UserData.isNotGuest`); Edit only exports text | local save / Write file… / Copy (no server) |
| Game front end is the server map list; Edit has Create New + import XML | Level Editor starts a new map; Ctrl+1 lists local maps |
| Game honours `xml/editor/units.xml` order, `mode` and `restricted`; Edit forces `restricted=false` | game data adopted: original order, War Machine is blitz-only. The game's `restricted` flag is a Premium upsell (free accounts cannot paint the Spider/Lancer Tanks); this offline port has no accounts and behaves like a fully unlocked one, so they paint normally |
| Rules/subscriptions/terrain/units/buildings documents and all editor bitmaps | identical apart from localisation; the port's `rules.json` is generated from the GAME's copies |

Entry points:

- **Main menu → Level Editor** starts a new 10×10 map on the game's window (the window grows to
  700×600 while the editor is open).
- `./gradlew jvmRun --args="assets --editor ep3/Mission_1"` opens that map. With `--frames N` it
  renders headless and saves a capture (add `--real` to use a real window instead of SDL's dummy
  driver).
- `./gradlew jvmRun --args="assets --editorcheck"` drives the whole editor headlessly and prints
  one PASS/FAIL line per feature (40 checks: opening, the rule pass, every tab and colour row,
  painting, Shift-pick, delete brushes, fill, resize, save/reload, every popup, and Test handing
  its map to the match loader).

Layout (the original's numbers):

```
+-------------------------------------------------------------+
| Save | Fill | Resize | Script | Test |               Quit    |
+-----------------------------+-------------------------------+
|                             |  Terrain | Units | Buildings    |
|      map viewport 560×560    |-------------------------------|
|      at (0,40)              |  rows: 124 px wide, paged     |
|                             |  colour radios / Delete button|
+-----------------------------+-------------------------------+
```

- **Tabs** are Terrain / Units / Buildings. The active tab uses the normal button art; the others
  use the editor's faded overlay art, exactly like the original `TabManager`.
- **Rows** are the original's `RuleMapper` rows: one per terrain (in the original `terrain.xml`
  order and mode-filtered — the ore deposits only appear in blitz-mode maps), one per unit type
  × four unit colours, and one per building type × five building colours (the fifth is neutral).
  Colour radios choose which colour the panel shows; the delete button arms a delete brush.
- **Left click or drag** on the map paints the selected material at the tile; **Shift-click**
  picks the terrain under the cursor into the Terrain panel; **arrows** scroll the camera
  (5 tiles/second, clamped to the map) and the view also scrolls while the pointer rests on a
  map edge, so maps resized past 10×10 are reachable with the mouse; the **wheel** steps the
  panel selection (the original's gesture); **Tab** cycles the tabs (Terrain → Units →
  Buildings); **Q/E** step the selection; **Ctrl+1** opens the map list.
- **Highlight**: the original `tile_highlight` art with the hovered tile's `col,row` in the top
  corner; the status line along the bottom shows the last operation.

Data model and rules:

- A map is three layers deep: terrain (0), buildings (1), units (2). Every cell references an
  entry of the document's palette (a material name, a team and its art), and is saved as the
  game's `MapConfig` JSON — a map opened from `assets/data/maps/` is written back there; a map a
  mod owns is written back as that mod's JSON Patch (a root `add`).
- **Terrain art is chosen by the neighbours**, using the original editor's rule tables, converted
  to `assets/data/editor/rules.json`: each terrain has an identity character, tilde/dash wildcard
  sets (`subscriptions.xml`), an out-of-bounds assumption (roads assume an imaginary road, the
  rest sea) and an ordered list of `match` rules. Nine identity characters (3×3, the cell itself
  in the middle) are matched against the rules; the first match supplies either whole-tile
  variants (`regular`) or four 28×28 quadrant pieces (`mini`). A terrain whose table matches
  nothing falls back to its dependency (`shore`, `bridge`, `reef`, `rock formation`,
  `archipelago` → `sea`) and the cell becomes that material, exactly like the original.
- **Every edit re-runs the rules over the 5×5 cells around it** (`recalculateMapArea(col-2,
  row-2, 5, 5)`), so painting one land tile joins the coast pieces of the four water tiles
  around it instead of leaving a square hole.
- **Fill** offers Plains / Sea / Forest / Mountain / Road (the original `VALID_FILL_TERRAIN`);
  **Resize** is the original's two-page popup (type-free in this port: numeric columns/rows, then
  drag the new size rectangle over the old one to choose which edges grow); **Script** shows the
  map's script document as JSON with Reset/Copy and wheel scrolling (the port has no text-input
  widget, so the original's XML text field is not reproduced; an empty script shows the typed
  document skeleton instead of `{}`); **Save** validates that the map has at least two
  players, writes the document back to its file when it has one, and shows the JSON with
  **Write file…** (a save dialog, for a document that has no path yet) and **Copy** (the
  original's export-to-clipboard step). The original's map-name field is omitted for the same
  reason as the script field — the port has no text input — so the document keeps its name; **Test** is enabled with at least
  two players and starts a skirmish from the *working* document (no save needed); **Quit** asks
  before dropping unsaved changes.
- `assets/data/editor/rules.json` is generated from the GAME's embedded editor documents:
  the terrain rules (`xml/editor/terrain`, 18 terrains / 164 rules with subscriptions,
  identities, wildcards and dependencies) plus the unit and building lists
  (`xml/editor/units.xml`, `xml/editor/buildings.xml` — panel order, `mode` and `restricted`).
  `assets/images/` gains the editor's chrome from the game's `EditorAssets` (`editor_frame`,
  `editor_highlight`, `editor_tab_*`, `editor_radio`) and the two art files the rule tables
  referenced but the game export was missing (`canyon_0_v1`, `high_bridge_7_v1`).

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
#   --editor [id]   open the map editor (e.g. --editor ep3/Mission_1); add " --frames 60" to
#                   render headless, save a capture and exit (add " --real" for a real window)
#   --editorcheck   drive every editor feature headlessly and print PASS/FAIL lines
#   --guicheck      click the main menu's Level Editor button and open the editor through the
#                   game's own path (headless wiring check)
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
