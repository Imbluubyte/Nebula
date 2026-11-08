# CLAUDE.md - Nebula

Read this first; it exists so you do not have to explore the repo. Nebula is a 2D game engine and editor
for Android tablets (Kotlin + Compose). Games are scenes of nodes with scripts written in **SuperNova + the
Nebula layer** (`.nbs`). Repo `Imbluubyte/Nebula`, work directly on `main`. SuperNova (`Imbluubyte/SuperNova`,
Rust compiler `snc`, emits C) is a separate repo: **read-only from here, never edit it; Nebula adapts to it**.
There is no README on purpose. The usage guide is `docs/GUIDE.md`, the syntax reference `docs/SYNTAX.md` (update both when syntax or API changes).

## Working rules (save tokens)

* **Never read a whole file when a section will do.** Use the map below, then `Grep -n` a symbol and `Read`
  with `offset`/`limit` (~60 lines). Biggest files: `Studio.kt` 390, `Transpiler.kt` 376, `Engine.kt` 300, `api/nebula.sn` 450.
* **No Explore agents** for this repo; the map is enough. Do not open `docs/GUIDE.md` / `docs/SYNTAX.md` unless changing user-facing behaviour.
* Batch independent tool calls in one message; edit several spots with one Python script (`assert old in s`).
* There is **no Android SDK in the sandbox**. Kotlin in `core/` is tested locally; `app/` is compiled only by
  GitHub Actions (job `apk` runs `gradle assembleEditorDebug`, which first builds the player APK). After pushing: `sleep 110; gh api repos/Imbluubyte/Nebula/actions/runs --jq '.workflow_runs[0]|"\(.status) \(.conclusion)"'`,
  then errors: `gh api repos/Imbluubyte/Nebula/actions/jobs/$JID/logs | grep "e: file"` (only `e: file:///` lines matter).
  `gh api` REST works; GraphQL and artifact downloads do not. Read Kotlin you write as a compiler would.
* Commit straight to `main` (`git push origin HEAD:main`; the "repository moved" notice is harmless). Keep commits small,
  imperative. Use the attribution trailers from the session reminder.
* `.gitignore` has `/build/` and `/app/build/` only. A bare `build/` rule once hid `app/.../studio/build/`.
* Keep final replies short: what changed, any limitation, one next step. The user is on a tablet.

## Commands

```
KOTLINC=<path>/kotlinc SNC=<path>/snc sh tests/run-core.sh    # core tests (compiles core/ with kotlinc, ~1 min)
sh tests/run-native.sh                                         # after run-core: demo fixture .so driven by a fake C engine
```
Kotlinc: `curl -L -o k.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip`
(GitHub downloads work, Maven does not). snc: `cd SuperNova/compiler && cargo build --release`.
CI (`.github/workflows/build.yml`): job `apk` (gradle assembleEditorDebug, player-is-slim check, exporter self-check with aapt2/zipalign), job `core-tests`.

## Architecture in one paragraph

`.nbs` scripts -> `Transpiler` (Kotlin) -> one `game.sn` + `scripts.meta` -> `snc build --lib` (in Termux) ->
`libscripts.so`. The engine (`Engine`, Android-free) owns the scene tree and calls a few exported functions of the
library (`nbAttach nbReady nbFrame nbTick nbEvery nbFire nbFreed`) through `ScriptHost` (JNA on Android). Scripts call
back through `extern fn nb_*` declared in `api/nebula.sn`, implemented in `JnaScriptHost`/`Engine.api*`.
Node ids cross the boundary; script-side wrapper classes (`nebula.Node`, `Sprite`, ...) hide them.
The app module has two flavors. **editor** (`app/src/editor`): Studio, ui, build, exporter, Termux bridge; it embeds the player APK as `assets/player.apk`.
**player** (`app/src/player`): only `MainActivity` + `app/src/main` (runtime/ + core), R8-shrunk, no editor code; every exported game is this.
`app/src/main` must never import anything from `editor` (the player build would fail). Export: copy `player.apk`, patch the binary manifest
(package + label), add `assets/project/**` and `lib/arm64-v8a/libscripts.so`, align, sign (apksig). Optionally copy the result to Downloads (MediaStore).

## Map (read only what the task needs)

| To change / add... | Files |
| --- | --- |
| a Nebula script syntax form (`onHear`, `shout`, `@path`, `wait`, `tweak`, ...) | `core/src/nebula/lang/Transpiler.kt` (`scan` = metadata, `emit` = lowering per line, `expr` = `@`/spawn/casts/type names, `onHear` = listener lowering, connected in a generated `nbConnect` that `nbReady` calls before `onReady`), then add a test in `core/test/CoreTests.kt`, then `docs/GUIDE.md` |
| script metadata format | `lang/MetaFile.kt` (also written by `Builder.build`) |
| a built-in node type or property | `core/.../NodeTypes.kt` (registry + broadcasts), class + `wrap` case + `asX` cast + registry dict in `api/nebula.sn`, drawing in `app/.../runtime/Renderer.kt`, inspector special cases in `ui/InspectorPanel.kt`, physics/input in `Engine.kt` |
| sprite sheet animation (`.nanim`), Sound node playback | `core/.../Anim.kt` (format, `Clip.frameAt`), `Engine.animate/serviceSounds`, `NodeTypes.kt` props, `runtime/Renderer.spriteView`, `runtime/ProjectIO.Sounds`, editor: `ui/AnimationManager.kt` + `AnimDoc` in `Studio.kt` |
| a script API function (`log`, `isDown`, `moveBy`, ...) | `api/nebula.sn` (class methods on `Node`/`Node2D`/...). New engine call: `extern fn nb_x` there + `Engine.apiX` + `CbXxx` interface and `reg(...)` line in `runtime/JnaHost.kt` + the stub in `tests/host/starter_host.c` |
| the game loop: onFrame/onTick/onEvery, physics, tap/pointers, scene loading/spawn/free | `core/.../Engine.kt` |
| scene tree, paths (`/World/Jump`, `../X`), unique names | `core/.../Scene.kt` |
| `.nscn` text format | `core/.../SceneFile.kt` |
| editor operations + undo/redo (add/delete/move/duplicate/props) | `core/.../SceneEditor.kt` |
| project folder (incl. `createFolder`, `folders`), `project.nebula` settings, `Builder` (transpile + snc) | `core/.../Project.kt` |
| the new-project presets (platformer, top down, blank; commented player scripts) | `core/.../Templates.kt` (`Preset`, `Templates.files`), picker in `NewProjectDialog` (`ui/Overlays.kt`), `Project.create(..., preset)`. Test fixture of the old demo (Hero/Coin/Score) is `core/test/Fixtures.kt` |
| camera (zoom, follow, smoothing, per-axis follow, offset, limits) and `fixed` HUD nodes | `Engine.updateCamera/toWorld` (state `camX camY camZoom`, runs every frame after physics; `setPointers`/`tap` take SCREEN coordinates), `NodeTypes.kt` (Camera props, `fixed` on Node2D), `SceneNode.isFixed`, drawing in `runtime/GameView.kt` (world pass through the camera, fixed pass on top), script API `class Camera` in `api/nebula.sn`, target picker in `ui/InspectorPanel.kt` |
| TileMap (tiles, collisions, painter, script API) | `core/.../TileGrid.kt` (cell storage, `of`/`sync`/`apply`/`cellAt`), `NodeTypes.kt`, `Engine.physics/tileCollide` + `apiGet/apiSet` ("tile:col,row" props), `api/nebula.sn` (`class TileMap`), drawing `Renderer.drawTiles`, painter: `Studio.paintPath/paintTile`, `ui/TilePalette.kt`, gesture in `ui/ViewportPanel.kt`, button in `ui/InspectorPanel.kt` ("tiles" hint) |
| SpawnableBody (lifetime, health, launch/chase, spawnBody/Burst/Ring) | `NodeTypes.kt` (type + `bodyTypes`), `Engine.serviceSpawnables` (lifetime), `api/nebula.sn` (`class SpawnableBody`, spawn helpers on `Node`); physics treats every type in `NodeTypes.bodyTypes` as a Body |
| DynamicBar (progress-bar node, sprite roles `empty @X` / `full @X` in its script) | `core/.../Bars.kt` (roles registry, fraction, `fillOf`), `NodeTypes.kt`, `Transpiler.scan` + `barRe` (metadata `barEmpty/barFull`, `MetaFile` line `bar`), `Engine` registers roles at start and `Studio.lint` in the editor, crop in `Renderer` (`"Sprite"` case), `api/nebula.sn` (`class DynamicBar`), direction buttons in `ui/InspectorPanel.kt` |
| Button that looks like Sprite children, pressed look | `Renderer` (`"Button"` case draws nothing of its own when it has a Sprite child; the `"Sprite"` case skips what `core/.../ButtonSkin.kt` hides), `Engine.setPointers` sets the runtime prop `_pressed` on every Button, `Button.isPressed()` in `api/nebula.sn`; children are ordinary Sprites, so `fixed` and input need nothing else |
| open a folder / zip as a project, autosave | `core/.../ProjectImport.kt` (unzip safely, find the project root, copy into the projects folder), `Studio.openFolderUri/openZipUri/runImport` + `MainActivity` pickers + `WelcomeScreen` buttons; autosave loop `Studio.autosave` (4 s) |
| Markdown files (open in the script editor), DOCS list in the Files panel | `core/.../Markdown.kt` (`isMarkdown`, `spans` for colouring), `Project.docs()`, `Studio.openAsset/openScript` (a `.md` is a `ScriptDoc` like a script; `lint()` only looks at `scripts()`), `ui/ScriptEditor.kt` (`highlightMarkdown`, md key row, no colon auto-indent), DOCS group in `ui/ConsoleAndFiles.kt` |
| import a public GitHub repo as a project (welcome screen button, no sign-in, public only) | `core/.../GitHubImport.kt` (`parse` address forms, `archiveUrl`, `import` takes the network call as a parameter so tests fake it, plus the user's `title` / `packageId`; downloads github.com/owner/repo/archive/REF.zip, then `ProjectImport.importZip`), `Studio.importGitHub` + `Modal.ImportGitHub` (progress and errors in `importBusy` / `importError`, shown inside the dialog), `ImportGitHubDialog` in `ui/Overlays.kt` (fixed `https://github.com/` prefix, project name, package id), button in `ui/WelcomeScreen.kt`; the editor manifest has INTERNET (player must not) |
| sprite editor (draw, frames, onion skin) | `core/.../FrameDoc.kt` (what the editor needs from a picture, shared onion maths), `SpriteDoc.kt` (pixels, tools, undo, frames, sheets; Android-free, tested), `app/.../SpriteSession.kt` (toolbar state + finger handling for both kinds), `ui/SpriteEditor.kt` (canvas incl. the 2-finger-tap undo / 3-finger-tap redo gesture, panels, colour wheel `ColorWheelOverlay`, new-sprite dialog; the frame strip must read `doc.frames` in the composition, not inside the LazyRow, or it goes stale), `Studio.openSprite/newSprite/saveSprite/syncAnimations` |
| vector sprites (a sprite is pixel OR vector) | `core/.../VectorDoc.kt` (`VShape`, `VectorDoc`: shapes, hit tests, bucket, undo, frames; `VectorFile` = the `.nvec` text next to the PNG), `ui/VectorDraw.kt` (`drawShapes` draws editor canvas, thumbnails and the PNG alike; `VectorRender.rasterize`), the part-of-a-line eraser is `VectorErase.along` (same file; the canvas shows `SpriteSession.erasePreview`, `VectorDoc.replaceShapes` makes it one undo step), tools in `SpriteSession.v*`, PNG + `.nvec` written by `Studio.saveSprite`, opened as vector by `Studio.openSprite` when a `.nvec` sits next to the PNG |
| CollisionBox (hitbox child of a body) | `NodeTypes.kt`, `Engine.hitbox` (used by `physics` and `tileCollide`), `api/nebula.sn` (`class CollisionBox`), editor outline in `Renderer` |
| editor state, project open/save, run/stop/build/export orchestration | `app/.../Studio.kt` (single state holder; panels read it) |
| commands + keyboard shortcuts + palette list | `app/.../Commands.kt` |
| layout (wide 3-pane / compact bottom-nav), top bar, status bar | `app/.../ui/StudioScreen.kt` |
| scene tree panel (scripts appear as child rows) | `ui/SceneTreePanel.kt` |
| viewport gestures (drag node, pan, pinch, snap, grid) | `ui/ViewportPanel.kt` |
| inspector (props, tweaks, script attach) | `ui/InspectorPanel.kt` |
| script editor (highlighting, auto-indent, key row, diagnostics) | `ui/ScriptEditor.kt` |
| console, files panel (folder tree) | `ui/ConsoleAndFiles.kt` (state: `filesFolder`, `newFolder`, `deleteFolder` in `Studio.kt`) |
| dialogs (new project/node, export, settings, help, palette, pick) | `ui/Overlays.kt` (in-window overlays, not system dialogs) |
| colours / shared widgets (`Field`, `TextBtn`, `IconBtn`) | `ui/Theme.kt` |
| first screen, setup checklist | `ui/WelcomeScreen.kt` |
| game surface (frame loop, camera, touch/keyboard input, FPS counter `showFps`) | `runtime/GameView.kt`, drawing in `runtime/Renderer.kt` |
| native script host (JNA callbacks) | `runtime/JnaHost.kt` |
| assets/sounds/save data access | `runtime/ProjectIO.kt` |
| exported game start-up | `runtime/Player.kt`, `app/src/player/.../MainActivity.kt` |
| building scripts through Termux | `build/TermuxRunner.kt` |
| APK export: manifest patch / zip alignment / signing / install | `build/AxmlPatcher.kt`, `build/ZipAlign.kt`, `build/ApkExporter.kt`, `build/Installer.kt`, `build/Downloads.kt` (CI check: `tools/ExporterCheck.kt`) |
| Gradle (flavors, player embedding) / dependencies / manifests / R8 keep rules / icon | `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/{main,editor}/AndroidManifest.xml`, `app/src/main/res/` |

Paths in this file written `app/.../` mean `app/src/editor/java/com/nebula/studio/` (Studio, Commands, ui, build) or `app/src/main/java/com/nebula/studio/` (runtime).

## Decisions and gotchas

* Player release is shrunk with R8: anything reached only by reflection/native code must be kept in `app/proguard-rules.pro` (JNA and `runtime/**` already are).
* Folders: only `scenes/ scripts/ assets/ animations/` and below are managed; `Project.createFolder` refuses anything else.
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
* Untested on a real device: sprite editor gestures, folder/zip pickers, Termux build bridge, JNA host, APK signing + install (CI proves compile, manifest patch, alignment).
* World position = sum of ancestors' x/y; rotation and scale are not inherited (document if that changes).

## Status

Done: node/scene system, `.nscn` format, editor UI (tree with scripts, viewport, inspector, script editor, console, files,
palette, shortcuts), Nebula script layer, broadcasts, onFrame/onTick/onEvery, simple physics, run in editor, APK export,
CI, folders in the Files panel, sprite sheet animation manager + Sound node controls, FPS counter, slim player APK for exports, export to Downloads,
SpawnableBody node, new-project presets (platformer / top down / blank), TileMap node (painter, solid tiles, script API), camera controls (zoom, follow, smoothing, per-axis, offset, limits) and `fixed` nodes.
DynamicBar node, Button skinned by Sprite children, open folder / zip as a project with autosave, built-in sprite editor (brush size + opacity, fill, colour picker, shapes, eyedropper, undo/redo, frames + onion skin, saved as a PNG sheet + `.nanim`).
CollisionBox node, node copy/cut/paste + Delete/Esc/zoom shortcuts (`Studio.nodeClipboard`, `Commands.inScene`, `TextFocus` in Theme.kt), mouse wheel zoom + middle-drag pan, script variables that start from other variables (`Transpiler.deferFieldStarts`: set in the generated constructor because SuperNova field initialisers cannot see other fields).
Markdown files open in the code editor (DOCS group in Files, own highlighting), and the first screen has **Import from GitHub** (public repositories only: private ones would need a sign-in, which is not built; a missing or private repo both read as "not found").
Vector sprites in the sprite editor (new-sprite dialog picks pixel or vector; PNG + `.nvec`), with a partial eraser, a colour wheel (tap the colour square) and tap-undo / tap-redo.
Gotcha: `StudioScreen` pads only the top and sides for system bars; the status bar sits on the bottom edge (`tappableElement` inset only), and `ModalHost` pads the navigation bar itself.
Gotcha: `Field` (ui/Theme.kt) only calls `onCommit` on keyboard Done or when focus is lost, so a button that reads the text must use `onChange` to track it (the GitHub import "Import" button once did nothing for exactly this reason).
Gotcha: new `Button` nodes get `fixed = true` from `SceneEditor.addNode`; `fixed` is false by default everywhere else, so old scenes behave as before.
Known limit: physics checks every body pair each frame (fine for ~1000 bodies, a spatial grid is the next step if a game needs more).
Ideas not done: private GitHub repos (needs sign-in: a token or device-flow login), Markdown preview, animation blending, particles, audio mixer, scene instancing UI, in-editor interpreter (no Termux),
script hot reload, 3D, go-to-definition/completion in the script editor, drag-reorder in the tree.
