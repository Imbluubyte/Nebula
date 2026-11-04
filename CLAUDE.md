# CLAUDE.md - Nebula

Read this first; it exists so you do not have to explore the repo. Nebula is a 2D game engine and editor
for Android tablets (Kotlin + Compose). Games are scenes of nodes with scripts written in **SuperNova + the
Nebula layer** (`.nbs`). Repo `Imbluubyte/Nebula`, work directly on `main`. SuperNova (`Imbluubyte/SuperNova`,
Rust compiler `snc`, emits C) is a separate repo: **read-only from here, never edit it; Nebula adapts to it**.
There is no README on purpose. The usage guide is `docs/GUIDE.md`.

## Working rules (save tokens)

* **Never read a whole file when a section will do.** Use the map below, then `Grep -n` a symbol and `Read`
  with `offset`/`limit` (~60 lines). Biggest files: `Studio.kt` 390, `Transpiler.kt` 376, `Engine.kt` 300, `api/nebula.sn` 450.
* **No Explore agents** for this repo; the map is enough. Do not open `docs/GUIDE.md` unless changing user-facing behaviour.
* Batch independent tool calls in one message; edit several spots with one Python script (`assert old in s`).
* There is **no Android SDK in the sandbox**. Kotlin in `core/` is tested locally; `app/` is compiled only by
  GitHub Actions. After pushing: `sleep 110; gh api repos/Imbluubyte/Nebula/actions/runs --jq '.workflow_runs[0]|"\(.status) \(.conclusion)"'`,
  then errors: `gh api repos/Imbluubyte/Nebula/actions/jobs/$JID/logs | grep "e: file"` (only `e: file:///` lines matter).
  `gh api` REST works; GraphQL and artifact downloads do not. Read Kotlin you write as a compiler would.
* Commit straight to `main` (`git push origin HEAD:main`; the "repository moved" notice is harmless). Keep commits small,
  imperative. Use the attribution trailers from the session reminder.
* `.gitignore` has `/build/` and `/app/build/` only. A bare `build/` rule once hid `app/.../studio/build/`.
* Keep final replies short: what changed, any limitation, one next step. The user is on a tablet.

## Commands

```
KOTLINC=<path>/kotlinc SNC=<path>/snc sh tests/run-core.sh    # core tests (compiles core/ with kotlinc, ~1 min)
sh tests/run-native.sh                                         # after run-core: starter .so driven by a fake C engine
```
Kotlinc: `curl -L -o k.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip`
(GitHub downloads work, Maven does not). snc: `cd SuperNova/compiler && cargo build --release`.
CI (`.github/workflows/build.yml`): job `apk` (gradle assembleDebug + exporter self-check with aapt2/zipalign), job `core-tests`.

## Architecture in one paragraph

`.nbs` scripts -> `Transpiler` (Kotlin) -> one `game.sn` + `scripts.meta` -> `snc build --lib` (in Termux) ->
`libscripts.so`. The engine (`Engine`, Android-free) owns the scene tree and calls a few exported functions of the
library (`nbAttach nbReady nbFrame nbTick nbEvery nbFire nbFreed`) through `ScriptHost` (JNA on Android). Scripts call
back through `extern fn nb_*` declared in `api/nebula.sn`, implemented in `JnaScriptHost`/`Engine.api*`.
Node ids cross the boundary; script-side wrapper classes (`nebula.Node`, `Sprite`, ...) hide them.
Export: the running APK copies itself, patches the binary manifest (package + label), adds `assets/project/**` and
`lib/arm64-v8a/libscripts.so`, aligns, signs (apksig). `MainActivity` boots `Player` when `assets/project/project.nebula` exists.

## Map (read only what the task needs)

| To change / add... | Files |
| --- | --- |
| a Nebula script syntax form (`hear`, `shout`, `@path`, `wait`, `tweak`, ...) | `core/src/nebula/lang/Transpiler.kt` (`scan` = metadata, `emit` = lowering per line, `expr` = `@`/spawn/casts/type names, `hear`), then add a test in `core/test/CoreTests.kt`, then `docs/GUIDE.md` |
| script metadata format | `lang/MetaFile.kt` (also written by `Builder.build`) |
| a built-in node type or property | `core/.../NodeTypes.kt` (registry + broadcasts), class + `wrap` case + `asX` cast + registry dict in `api/nebula.sn`, drawing in `app/.../runtime/Renderer.kt`, inspector special cases in `ui/InspectorPanel.kt`, physics/input in `Engine.kt` |
| a script API function (`log`, `isDown`, `moveBy`, ...) | `api/nebula.sn` (class methods on `Node`/`Node2D`/...). New engine call: `extern fn nb_x` there + `Engine.apiX` + `CbXxx` interface and `reg(...)` line in `runtime/JnaHost.kt` + the stub in `tests/host/starter_host.c` |
| the game loop: onFrame/onTick/onEvery, physics, tap/pointers, scene loading/spawn/free | `core/.../Engine.kt` |
| scene tree, paths (`/World/Jump`, `../X`), unique names | `core/.../Scene.kt` |
| `.nscn` text format | `core/.../SceneFile.kt` |
| editor operations + undo/redo (add/delete/move/duplicate/props) | `core/.../SceneEditor.kt` |
| project folder, `project.nebula` settings, `Builder` (transpile + snc) | `core/.../Project.kt` |
| the starter project template | `core/.../Templates.kt` |
| editor state, project open/save, run/stop/build/export orchestration | `app/.../Studio.kt` (single state holder; panels read it) |
| commands + keyboard shortcuts + palette list | `app/.../Commands.kt` |
| layout (wide 3-pane / compact bottom-nav), top bar, status bar | `app/.../ui/StudioScreen.kt` |
| scene tree panel (scripts appear as child rows) | `ui/SceneTreePanel.kt` |
| viewport gestures (drag node, pan, pinch, snap, grid) | `ui/ViewportPanel.kt` |
| inspector (props, tweaks, script attach) | `ui/InspectorPanel.kt` |
| script editor (highlighting, auto-indent, key row, diagnostics) | `ui/ScriptEditor.kt` |
| console, files list | `ui/ConsoleAndFiles.kt` |
| dialogs (new project/node, export, settings, help, palette, pick) | `ui/Overlays.kt` (in-window overlays, not system dialogs) |
| colours / shared widgets (`Field`, `TextBtn`, `IconBtn`) | `ui/Theme.kt` |
| first screen, setup checklist | `ui/WelcomeScreen.kt` |
| game surface (frame loop, camera, touch/keyboard input) | `runtime/GameView.kt`, drawing in `runtime/Renderer.kt` |
| native script host (JNA callbacks) | `runtime/JnaHost.kt` |
| assets/sounds/save data access | `runtime/ProjectIO.kt` |
| exported game start-up | `runtime/Player.kt`, `MainActivity.startPlayer` |
| building scripts through Termux | `build/TermuxRunner.kt` |
| APK export: manifest patch / zip alignment / signing / install | `build/AxmlPatcher.kt`, `build/ZipAlign.kt`, `build/ApkExporter.kt`, `build/Installer.kt` (CI check: `tools/ExporterCheck.kt`) |
| Gradle / dependencies / manifest / icon | `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/` |

## Decisions and gotchas

* **SuperNova limits shape the design.** No generic classes (each `broadcast` gets a generated `_Bc_Script_name` class),
  no downcasts (per-class registries `_reg_X` + `as` casts), lambdas are single-expression, only Int/Float/Bool/String cross
  the host ABI (so properties are string-keyed `nb_get_f/s/b`, `nb_set_*`), `export fn` of an imported module gets mangled
  (`sn_m1_nebula__x`), so `game.sn` re-exports the entry points with bare names. Lists/strings are 1-based, `orif`, tabs.
* Methods starting with `_` or private are not exported; generated hooks are named `nbTick_30`, `nbEvery_2500` (ms).
* Kotlin comments nest: never write `/*` inside a KDoc (e.g. `scenes/*.nscn`).
* Dialogs are in-window overlays on purpose (system dialogs swallow key events).
* Selection is stored as a node *path* (ids change after undo rebuilds the tree).
* Engine runs on the UI thread; builds/exports run on a worker thread and post back through `Studio.post`.
* Scripts are native code: a change needs a rebuild (Termux + `snc`); no hot reload. Loading a rebuilt `.so` uses a fresh file name.
* Untested on a real device: Termux build bridge, JNA host, APK signing + install (CI proves compile, manifest patch, alignment).
* World position = sum of ancestors' x/y; rotation and scale are not inherited (document if that changes).

## Status

Done: node/scene system, `.nscn` format, editor UI (tree with scripts, viewport, inspector, script editor, console, files,
palette, shortcuts), Nebula script layer, broadcasts, onFrame/onTick/onEvery, simple physics, run in editor, APK export,
CI. Ideas not done: animation, tilemaps, particles, audio mixer, scene instancing UI, in-editor interpreter (no Termux),
script hot reload, 3D, go-to-definition/completion in the script editor, drag-reorder in the tree.
