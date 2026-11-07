# Nebula guide

Nebula is a 2D game engine for Android tablets. You build **scenes** out of **nodes**, attach **scripts**
written in SuperNova (plus a few Nebula additions), press **Run**, and export an **APK**.

## 1. Setup (once)

1. Install the Nebula APK.
2. Open Nebula and grant **All files access** (projects live in `/storage/emulated/0/NebulaProjects`).
3. Install **Termux** (F-Droid) and SuperNova inside it (`scripts/termux-install.sh` in the SuperNova repo).
4. In Termux run `termux-setup-storage`, and add `allow-external-apps=true` to `~/.termux/termux.properties`, then restart Termux.
5. In Android settings, give Nebula the permission "Run commands in the Termux environment".

Projects without scripts need none of the Termux steps. Scripts are compiled to native code by Termux when you press Run/Build.

### Opening a folder or a zip

On the first screen (or from the command palette) **Open folder** and **Open zip** bring a project that lives somewhere else into Nebula. The files are copied into the
projects folder (the project is found even when a zip wraps everything in one folder, and a missing `project.nebula` or main scene is filled in), a stale `build/` and
`.git` data are left out, and the copy opens like any project. From then on you edit that copy and **every change is saved to it automatically** (every few seconds,
when you leave the app, and when you run). Opening the same zip again makes another copy (`Name 2`); the original folder or zip is never touched.

### Importing from GitHub

**Import from GitHub** on the first screen asks for the address of a **public** repository (`https://github.com/owner/repo`, also `owner/repo`, a `.git` address,
or a branch link like `https://github.com/owner/repo/tree/dev`, which imports that branch). Nebula downloads the repository and saves a copy in the projects folder, which then
opens like any project; a repository that is not a Nebula project still opens, with the Nebula folders added. No GitHub sign-in is involved, so private repositories are
not supported (GitHub answers "not found" for them). It needs an internet connection; exported games never use the internet.

### Starting a project

**New project** asks you to pick a preset. Every preset is an ordinary project you can change freely.

| Preset | You get |
| --- | --- |
| Platformer | a ground and a platform, and a Player that runs and jumps (arrow keys / A D / Space, or the on-screen buttons) |
| Top down | a walled room with a floor, and a Player that walks in all directions (arrows / WASD, or the on-screen D-pad) |
| Blank | an empty scene and no scripts |

The Player script in the first two presets (`scripts/player.nbs`) is commented line by line: it says what each line does and what to change.
Its numbers (`tweak speed`, `tweak jumpPower`) are also editable in the Inspector.

## 2. The editor

* **Files tab**: folders for scenes, scripts, animations and assets. Use the folder buttons to create folders (and subfolders); tap a folder to
  make it the target for imports, new scenes, new scripts and new animation sets. Tap a sound to hear it.
* **Markdown**: `.md` files (a repository's `README.md`, notes in `docs/`, or anything in `assets/`) are listed under **DOCS** and open in the same code editor as
  scripts, with their own colours (headings, bold, code, links, lists) and a key row for Markdown characters. They are plain text files: nothing is rendered and
  they are never built or exported into the game.
* **Sprite editor**: tap an image in the Files tab, the brush icon (new sprite), or **Edit in sprite editor** under a Sprite's `image`. See "Drawing sprites" below.
* **Left**: the scene tree (and a Files tab). A script appears as a row *under* its node. Tap to select, long-press to rename,
  the buttons above the tree add scripts, duplicate, move, indent/outdent and delete.
* **Centre**: the scene view (1 finger on a node drags it, on empty space pans, 2 fingers pan/zoom; buttons for zoom, grid, snap),
  script tabs, and the running game.
* **Right**: the inspector (properties, tags, script, tweaks). **Bottom**: console (tap an error line to jump to it).
* On a narrow screen the same panels are in the bottom bar.
* Everything works by touch. Shortcuts (hardware keyboard) are listed in the command palette (Ctrl+Shift+P, or the search button) and F1:
  Ctrl+S save, Ctrl+Z/Ctrl+Shift+Z undo/redo, F5 run, F6 run this scene, Shift+F5 stop, Ctrl+B build, Ctrl+E export,
  Ctrl+N add node, Ctrl+Shift+N new script, Ctrl+D duplicate, F2 rename, Ctrl+Backspace delete, Ctrl+1/2/3 scene/script/game.
  In the scene view also Ctrl+C/Ctrl+X/Ctrl+V copy, cut and paste a node (with its children; paste goes under the selected node), Delete removes it, Esc deselects,
  Ctrl+= / Ctrl+- zoom, Ctrl+0 fit. Mouse: wheel zooms at the pointer, middle-button drag pans. They stand aside while a text field has the keyboard.

## 3. Nodes

Every node has a name and a type. Node names are unique among siblings. Paths: `Child/Grandchild`, `..` (parent), `/Root/Child` (absolute).

| Type | Use | Main properties |
| --- | --- | --- |
| Node | plain container | |
| Node2D | position holder / group | x, y, rotation (degrees), scale, layer, visible, touchable, fixed |
| Sprite | picture or animated sprite sheet | image, flipX, width, height, hframes, vframes, frame, animations, animation, playing, speed |
| Shape | rectangle or circle | shape, width, height, color |
| Label | text | text, fontSize, color |
| Button | on-screen button; add **Sprite** children and it looks like them (the colour and text are then not drawn); a child Sprite named **Pressed** is the look while it is held (see below); width and height are still the tap area | text, width, height, color, **action** |
| DynamicBar | a bar that fills by itself, like Godot's ProgressBar; no look of its own, see "DynamicBar" below | value, minValue, maxValue, direction |
| Body | moving thing with collisions | vx, vy, gravity, width, height, solid, static, bounce, color |
| CollisionBox | a hitbox: as a child of a Body or SpawnableBody it replaces the body's own width x height box (x, y = offset from the body, first one wins; scale ignored). Script: `width() height() setSize(w, h)` | width, height |
| SpawnableBody | a Body made to be spawned many times (bullets, enemies, pickups) | everything a Body has, plus lifetime, health, maxHealth, damage |
| TileMap | a grid of tiles cut from one picture (floors, walls, levels) | image, tileWidth, tileHeight, hframes, vframes, columns, rows, solid, solidTiles, tiles |
| Camera | the view: zoom, follow a node, limits | zoom, follow, target, followX, followY, smoothing, offsetX, offsetY, limits, limitLeft, limitTop, limitRight, limitBottom |
| Sound | sound player | file, volume, pitch, loop, autoplay |

Positions are in game units (project size, default 1280x720, y grows downward) and add up through parents.
A **Button with an `action`** (`left`, `right`, `up`, `down`, `jump`, `action`) counts as that action while held: scripts read it with `isDown("left")` /
`axis("left", "right")`. The keyboard (arrows/WASD, space, Z) feeds the same actions.
**Body**: gravity pulls it down; a body that is `solid` and `static` is ground that other bodies stand on (`onGround()` becomes true).

### Buttons with their own pictures

Give a Button **Sprite** children and it shows them instead of the coloured box and text. To give it a **pressed look**, add one more Sprite child named exactly `Pressed`:
while a finger is on the button only `Pressed` is shown (the other Sprites are hidden), and the rest of the time `Pressed` is hidden. The editor shows all of them so you
can line them up. `Pressed` can be a different picture or the same one cut to another cell. In a script, `isPressed()` tells whether the button is held:
`if (@Jump as Button).isPressed():`. This works for every button, with or without an `action`.

### DynamicBar: health bars, loading bars

A DynamicBar fills itself from its `value`: `(value - minValue) / (maxValue - minValue)`. It has **no default appearance**. You give it two **Sprite** children
(anywhere below it) and say in its script which is which:

* the **empty bar**: the whole bar *with* its border (always shown)
* the **full bar**: the bar when full, *without* a border (cropped to the value, on top of the empty bar)

Make both pictures the same size and line the two sprites up (same x and y). Then attach a script to the bar:

```
script Health of DynamicBar:
	empty @Back
	full @Fill

	fn onReady():
		setValue(75.0)
```

`empty @Back` and `full @Fill` take any node path relative to the bar (`@Frame/Fill`, `@^/Other` for a sibling). The `minValue`, `maxValue` and `value` are in the Inspector
(defaults 0, 100, 100) and can be changed from scripts. `direction` (right, left, up, down) is the side the bar grows from.
Calls: `setValue(v) value() fill(n) drain(n) fillUp() emptyOut() setMin(v) setMax(v) setRange(lo, hi) minValue() maxValue() percent() isFull() isEmpty() setDirection(side)`.
`setValue` keeps the value between the minimum and the maximum, and changing the minimum or maximum keeps the value inside the new range.
To change a bar from another script cast its node: `(@/Hud/Health as DynamicBar).drain(10.0)`.

### Camera: zoom, following, smoothing, limits

Add a **Camera** node anywhere in the scene (a scene without one shows the game area as it is). What the player sees is the world around the camera's `x`, `y`, `zoom` times closer.

| Property | What it does |
| --- | --- |
| `zoom` | 1 = normal, 2 = twice as close (things look twice as big), 0.5 = twice as far |
| `follow` | **off**: the camera is stationary, it shows what is around its own `x`, `y`. **on**: it tracks its `target` |
| `target` | what to follow: tap the button and pick a node, or a **#tag** (`#player` follows the first node tagged `player`; it keeps working if you rename the node) |
| `followX`, `followY` | which directions it moves in. Platformer: `followX` on, `followY` off = only follows sideways, the height stays where you placed the camera. Both on = follows everywhere |
| `smoothing` | how lazily it follows, in seconds: the time it takes to close about two thirds of the gap. `0` = locked to the target, `0.15` = snappy, `0.5` = floaty. Same feel at any frame rate |
| `offsetX`, `offsetY` | shift the view away from the target, e.g. `offsetY = -80` shows more of what is above the player |
| `limits` | keep the view inside `limitLeft` .. `limitRight` x `limitTop` .. `limitBottom` (the size of your level), so the camera never shows the empty space beyond it. A level smaller than the screen is centred |

While following, the engine moves the Camera node itself, so `x()` / `y()` on the camera give what the view is looking at.

**Fixed nodes.** Everything in a scene normally moves with the world. Turn on **`fixed`** on a node (and everything under it) to place it on the screen instead: a score Label, a health bar, on-screen Buttons.
Fixed nodes ignore the camera completely (position, zoom) and are drawn on top; touch buttons and `tapped` use screen positions for them. New Buttons are fixed by default, and the presets have it on.
`touches()` and `tapped(x, y)` of normal nodes are world positions, so aiming a bullet at a finger works with any camera.

From a script, on a node of type Camera (or `@/World/Camera as Camera`):

```
script Rig of Camera:
	fn onReady():
		setZoom(1.5)
		followTag("player")            # or followNode(@/World/Hero)
		setSmoothing(0.3)
		setFollowAxes(true, false)     # sideways only
		setOffset(0.0, -60.0)
		setLimits(0.0, 0.0, 3000.0, 800.0)
```

`setZoom(z) zoom()`, `followNode(node) followTag(tag) stopFollowing() isFollowing()`, `setSmoothing(s) smoothing()`, `setFollowAxes(horizontal, vertical)`, `setOffset(dx, dy)`,
`setLimits(left, top, right, bottom) clearLimits()`, `snapToTarget()` (jump straight to the target this frame, e.g. after teleporting the player). Any node: `setFixed(b) isFixed()`.
The platformer and top down presets each have a Camera that follows the player inside a level wider than the screen; select it to try the settings.

### TileMap: levels made of tiles

A **TileMap** draws a `columns` x `rows` grid of square-ish tiles (`tileWidth` x `tileHeight` game units each). Its `x`, `y` is the **centre** of the whole map.
The tiles come from one picture: import a tileset image, choose it as the map's `image`, and set `hframes` / `vframes` to the number of tiles across and down.
Tile numbers start at 1 at the top left and go left to right, top to bottom (the same numbering as sprite sheet frames); **0 is an empty cell**.
Without an image each tile number shows as a coloured square, so you can lay out a level before any art exists.

**Painting.** Select the TileMap and tap **Paint tiles** in the inspector. The scene view then has a tile bar: pick a tile, then use **paint** (drag a finger to paint),
**erase**, or **fill** (tap to flood an open area). Two fingers still pan and zoom. Each stroke is one undo step. Tap **Done** to go back to moving nodes.

**Collisions.** With `solid` on, bodies (Body, SpawnableBody) stop against painted tiles: they land on them (`onGround()` becomes true), bump into walls and hit their heads on ceilings.
Leave `solidTiles` empty to make every painted tile solid, or list the tile numbers that are (`1, 2, 7`), so decoration tiles (grass, signs) can be walked through.
A body touching a solid tile sends `collided(map)` once, with the TileMap as `other`. A map with `solid` off is scenery only.

**From a script** (columns and rows count from 1):

```
script Level of TileMap:
	fn onReady():
		# dig a hole in the floor and put a coin above it
		clearTile(5, 12)
		var spot = cellCenter(5, 10)
		log(f"coin goes at {spot.x}, {spot.y}")

	fn onEvery(1):
		# turn a random-looking tile into a wall
		setTile(3, 4, 2)
```

`tileAt(col, row)`, `setTile(col, row, tile)`, `clearTile(col, row)`, `fillTiles(tile)`, `columns()`, `rows()`, `tileWidth()`, `tileHeight()`, `setSolid(b)`,
`colAt(worldX)` / `rowAt(worldY)` (which cell a position is in; below 1 or above `columns()` / `rows()` means outside), `tileAtPoint(worldX, worldY)` and `cellCenter(col, row)`.
For example a hero can ask `tileAtPoint(x(), y() + 40.0)` what is under its feet. Cells outside the map read as 0 and writes to them are ignored.
A TileMap is not moved by its parent's rotation or scale (like every node, only x and y add up). Maps of up to 1,000,000 cells work; only the visible part is drawn.

### SpawnableBody: bullets, enemies, pickups

A **SpawnableBody** is a Body with the things shoot-em-up and survivor-style games keep needing built in: it can expire on a timer, it has health,
it can deal damage, and it knows how to fly in a direction or walk at a target. Make a small scene whose **root** is a SpawnableBody
(`scenes/bullet.nscn`: set its size and color, optionally a script), then spawn it from any script:

```
script Gun of Node2D:
	fn onEvery(0.4):
		# a bullet under the Gun's parent (so it does not move with the Gun), flying right at 500 px/s
		var b = spawnBody("scenes/bullet.nscn", parent(), x(), y())
		b.launch(0.0, 500.0)

	fn onEvery(5):
		# eight bullets in a ring (a burst), or enemies placed round a point (a ring)
		spawnBurst("scenes/bullet.nscn", parent(), x(), y(), 8, 300.0)
		spawnRing("scenes/enemy.nscn", parent(), x(), y(), 12, 600.0)
```

Lifetime: `lifetime` (seconds, 0 = forever) in the Inspector or `setLifetime(s)`. When it runs out the body sends `expired` and is freed, so bullets clean up after themselves.
Health: `health`, `maxHealth`, `damage` in the Inspector. `hurt(n)` lowers health (sends `damaged(n)`) and calls `die()` at 0; `die()` sends `died` and frees the body.
`heal(n)`, `setHealth(n)`, `setMaxHealth(n)` (also refills), `isDead()`, `age()` (seconds since it spawned).
A bullet script can be this short (`dealDamage(other)` hurts `other` by this body's `damage`, and is `false` when `other` is not a SpawnableBody):

```
script Bullet of SpawnableBody:
	fn onHear(collided, other):
		if dealDamage(other):
			die()
```

Moving: angles are degrees, 0 = right, 90 = down. `launch(angle, speed)` sets the velocity (and turns the body), `launchAt(node, speed)` launches toward a Node2D,
`angleTo(x, y)` gives the angle to a point, and `chase(node, speed)` walks straight at a node (call it from `onFrame`, e.g. an enemy following the player).
Finding targets: `countTagged("enemy")` and `nearest("enemy", x(), y())` (the closest tagged node; check the count first, it fails when there is none).
Broadcasts: `expired`, `died`, `damaged(amount)`. Spawned bodies are placed in the same space as the parent you pass, so spawn under a container such as `World`, not under a moving node.

### Drawing sprites

The built-in sprite editor draws pixel art, regular pictures and vector sprites (PNG). Open it from the Files tab (brush icon = new sprite, tap an image = edit it), from the Inspector
(**Edit in sprite editor** under a Sprite's `image`) or the command palette. One finger uses the tool, two fingers pan and zoom (mouse wheel zooms).

* **Pixel or vector**: *New sprite* asks which, and a sprite is one or the other for good. A vector sprite is made of shapes (freehand strokes, lines, rectangles,
  ellipses) that stay editable: the eraser removes whole shapes, Fill colours the closed shape you tap (outlines keep their line), the eyedropper picks a shape's colour,
  and the brush size is the line width. Colour, opacity, undo / redo, frames, onion skin and Play work as in pixel art. It is saved as `name.png` (what the game draws, made from the
  shapes) plus `name.nvec` (the shapes themselves): keep the two together, and tap the PNG (or the `.nvec`) in the Files tab to edit it as vector again.
* **Tools**: brush, eraser, fill (paint bucket), eyedropper (picks the colour under your finger), line, rectangle and ellipse (outline or filled).
* **Brush size and opacity**: two sliders. **Pixel brush** is hard edged, **Soft brush** is anti-aliased. Opacity does not build up inside one stroke.
* **Colour**: hue / saturation / brightness sliders, a hex field, a 16 colour palette and your recent colours.
* **Undo / redo**: every stroke, shape, fill and frame change is one step.
* **Frames and onion skin**: add a blank or copied frame, delete, move and clear frames. **Onion skin** shows the frames before (red) and after (green) the current one
  faintly, so each drawing lines up with its neighbours; *Before* / *After* set how many, *Loops* treats the animation as a loop. **Play** previews the animation.
* **Saving**: automatic (every few seconds and on Done). Frames are saved as one PNG sheet, frames side by side. A sheet that an animation set (`.nanim`) describes opens as its
  frames and keeps that grid; a new animated sprite gets an animation set with a clip `all` (all frames, 8 fps, loops), and an existing set's grid follows the frame count.
  A JPG or WebP opens too, but is saved as a PNG next to the original.
* **Using the animation in a game**: the animation is *not* inside the PNG. The PNG is only the sheet, all frames side by side. The animation is the small text file
  `animations/<name>.nanim` that the editor makes for you: it names the sheet, its grid (columns x rows) and the clips (first frame, last frame, speed, loop; the first clip is `all`).
  Give a Sprite node that file (Inspector: `animations` = the `.nanim`, `animation` = a clip name such as `all`, `playing` on), or do it from a script:
  `sprite.setAnimations("animations/hero.nanim")` then `sprite.animate("all")`. Make more clips (walk, jump, ...) in the animation manager (film icon in the Files tab).

### Sounds and animation
* **Sound effects**: import `.wav`/`.ogg`/`.mp3` files, then call `play("assets/hit.wav")` from a script, or add a **Sound** node (volume, pitch, loop, autoplay)
  and call `start()` / `stop()` on it.
* **Animations**: import a sprite sheet image, tap the film icon in the Files tab to make an animation set, choose the sheet, enter its columns and rows,
  add clips (tap the sheet to pick the first and last frame, set speed and loop). Give a Sprite the set and a clip in the inspector,
  or play one from a script with `animate("run")`. A clip that does not loop sends `animationFinished`.

## 4. Scripts: SuperNova plus the Nebula layer

A script file (`scripts/hero.nbs`) is one class attached to a node. The first line says what it is:

```
script Hero of Body:
	tweak speed = 320            # editable in the inspector per node
	broadcast jumped             # a signal other scripts can listen to

	fn onReady():                # runs once when the scene starts (children before parents)
		log("hi")

	fn onFrame():                # every frame; `delta` is the frame time in seconds
		moveBy(axis("left", "right") * speed * delta, 0.0)

	fn onTick(30):               # 30 times per second, fixed step; `delta` is 1/30
		pass

	fn onEvery(2.5):             # every 2.5 seconds; several onEvery are fine if the numbers differ
		pass
```

Everything else is normal SuperNova (see the SuperNova docs): `var`, `fn`, `if/orif/else`, `for`, lists, dicts, classes, `async`, `try`.
Indent with tabs. Lists are 1-based. A script can also contain helper functions or enums after the class (at column 0).
Use `use std.math` (and friends) at the top of the file.

### Nebula additions

| Syntax | Meaning |
| --- | --- |
| `script Name of Type:` | declares the script; `Type` is a node type or another script (inheritance) |
| `tweak speed = 320` | a variable with an inspector field (Int, Float, Bool, String by its value) |
| `fn onReady() / onFrame() / onTick(30) / onEvery(2.5) / onFree()` | engine hooks |
| `@Child`, `@A/B`, `@^`, `@^/Sibling`, `@/Root/Path` | the node at that path (`^` = parent). Fails with a clear error if missing |
| `@Path as Sprite` | the same node as that type, so its methods work (`(@Icon as Sprite).setImage("a.png")`). Works for built-in types and your scripts |
| `broadcast hit(amount: Int)` | declares a broadcast (a signal) with typed parameters |
| `shout hit(5)` | sends it (only your own broadcasts) |
| `fn onHear(hit, amount):` | listen to your own broadcast; the body is the handler |
| `fn onHear(@Enemy.hit, amount):` | listen to the broadcast of another node. Values match by position, `as Type` only if the name is ambiguous |
| `spawn "enemy.nscn" under @/World` | instance a scene as a child (add `as Type` for the result) |
| `wait 1.5` | pause an `async fn` for 1.5 seconds |

An `onHear` may list fewer names than the broadcast sends, and you can have several as long as they listen to different broadcasts. Built-in broadcasts: `tapped(x, y)` (Node2D, needs `touchable`;
Buttons are touchable), `collided(other)` (Body), `freed` (every node). Handlers of freed nodes are skipped automatically.

### What every script can call (bare, inherited from `nebula.Node`)

`log(x)`, `isDown("jump")`, `axis("left","right")`, `touches()` (list of Vec2), `screenSize()`, `play("sound.wav")`, `goto("scenes/level2.nscn")`,
`save("key", value)` / `load("key", "fallback")`, tree: `name()`, `parent()`, `child(i)`, `children()`, `add("Sprite", "Name")`, `free()`, `tag("x")`, `hasTag("x")`;
`nebula.nodesTagged("enemy")` for all tagged nodes; `Vec2(x, y)` with `plus minus times length normalized distanceTo`.
Node2D: `x() y() position() setPosition(x,y) moveBy(dx,dy) rotation() setRotation(d) turnBy(d) setScale(s) setVisible(b) setLayer(n) setTouchable(b)`.
Sprite: `setImage(file) setFlipX(b) animate("clip") stopAnimating() setAnimSpeed(x) setFrame(n)`. Sound: `start() stop() setVolume(v) setPitch(p) setLoop(b)`. Shape: `setColor("#RRGGBB") setSize(w,h)`. Label: `setText(x) setColor() setFontSize()`.
Body: `velocity() setVelocity(vx,vy) push(dvx,dvy) setGravity(g) onGround()`. Node2D also: `setFixed(b) isFixed()`.
Camera: `setZoom(z) zoom() followNode(node) followTag(tag) stopFollowing() isFollowing() setSmoothing(s) smoothing() setFollowAxes(h, v) setOffset(dx, dy) setLimits(l, t, r, b) clearLimits() snapToTarget()`.
TileMap: `tileAt(c, r) setTile(c, r, n) clearTile(c, r) fillTiles(n) columns() rows() tileWidth() tileHeight() setSolid(b) colAt(wx) rowAt(wy) tileAtPoint(wx, wy) cellCenter(c, r)`.
SpawnableBody (a Body): `lifetime() setLifetime(s) age() health() maxHealth() setHealth(n) setMaxHealth(n) hurt(n) heal(n) die() isDead() damage() setDamage(n) dealDamage(other) launch(angle, speed) launchAt(node, speed) angleTo(x, y) chase(node, speed)`.
Every script: `spawnBody(scene, parent, x, y) spawnBurst(scene, parent, x, y, count, speed) spawnRing(scene, parent, x, y, count, radius) countTagged("tag") nearest("tag", x, y)`.
The full list is `api/nebula.sn` (plain SuperNova: read it). Every Nebula syntax form is explained in `docs/SYNTAX.md`.

## 5. Run, build, export

* **Run** (F5) saves, builds the scripts through Termux (a few seconds), and plays the main scene in the editor. Compile errors show in the console with the
  `.nbs` file and line; tap to jump. Mistakes in Nebula syntax are shown live in the editor as you type.
* **Test tools**: **F3** (or the FPS button over the running game) shows a frames-per-second counter.
* **Export APK** (Ctrl+E): enter app name and package id (`com.you.mygame`). Nebula packs your project and scripts into its slim player, signs it and can install it.
  Turn on *Also save a copy to the Downloads folder* to get the APK in `Download/` as well (it is always kept in the project's `export/` folder).
  The default signing key is a built-in debug key (fine for sideloading); for a store release choose your own PKCS12 keystore in Settings.
  The export is arm64 only. The player contains only what is needed to run a game: no editor screens, compiler bridge or signing code.

## 6. Project folder

```
NebulaProjects/MyGame/
  project.nebula        title, main scene, package id, size, orientation
  scenes/*.nscn         text scenes (readable, git-friendly)
  scripts/*.nbs
  animations/*.nanim    sprite sheet animation sets (made in the animation manager)
  assets/               images (.png .jpg .webp) and sounds (.wav .ogg .mp3), imported from the Files tab
  build/                generated: game.sn, scripts.meta, libscripts.so
  export/               exported APKs
```
