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
* **Left**: the scene tree (and a Files tab). A script appears as a row *under* its node. Tap to select, long-press to rename,
  the buttons above the tree add scripts, duplicate, move, indent/outdent and delete.
* **Centre**: the scene view (1 finger on a node drags it, on empty space pans, 2 fingers pan/zoom; buttons for zoom, grid, snap),
  script tabs, and the running game.
* **Right**: the inspector (properties, tags, script, tweaks). **Bottom**: console (tap an error line to jump to it).
* On a narrow screen the same panels are in the bottom bar.
* Everything works by touch. Shortcuts (hardware keyboard) are listed in the command palette (Ctrl+Shift+P, or the search button) and F1:
  Ctrl+S save, Ctrl+Z/Ctrl+Shift+Z undo/redo, F5 run, F6 run this scene, Shift+F5 stop, Ctrl+B build, Ctrl+E export,
  Ctrl+N add node, Ctrl+Shift+N new script, Ctrl+D duplicate, F2 rename, Ctrl+Backspace delete, Ctrl+1/2/3 scene/script/game.

## 3. Nodes

Every node has a name and a type. Node names are unique among siblings. Paths: `Child/Grandchild`, `..` (parent), `/Root/Child` (absolute).

| Type | Use | Main properties |
| --- | --- | --- |
| Node | plain container | |
| Node2D | position holder / group | x, y, rotation (degrees), scale, layer, visible, touchable |
| Sprite | picture or animated sprite sheet | image, flipX, width, height, hframes, vframes, frame, animations, animation, playing, speed |
| Shape | rectangle or circle | shape, width, height, color |
| Label | text | text, fontSize, color |
| Button | on-screen button | text, width, height, color, **action** |
| Body | moving thing with collisions | vx, vy, gravity, width, height, solid, static, bounce, color |
| SpawnableBody | a Body made to be spawned many times (bullets, enemies, pickups) | everything a Body has, plus lifetime, health, maxHealth, damage |
| Camera | view centre | zoom |
| Sound | sound player | file, volume, pitch, loop, autoplay |

Positions are in game units (project size, default 1280x720, y grows downward) and add up through parents.
A **Button with an `action`** (`left`, `right`, `up`, `down`, `jump`, `action`) counts as that action while held: scripts read it with `isDown("left")` /
`axis("left", "right")`. The keyboard (arrows/WASD, space, Z) feeds the same actions.
**Body**: gravity pulls it down; a body that is `solid` and `static` is ground that other bodies stand on (`onGround()` becomes true).

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
Body: `velocity() setVelocity(vx,vy) push(dvx,dvy) setGravity(g) onGround()`. Camera: `setZoom(z)`.
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
