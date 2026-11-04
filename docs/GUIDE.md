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

## 2. The editor

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
| Sprite | picture | image, flipX, width, height |
| Shape | rectangle or circle | shape, width, height, color |
| Label | text | text, fontSize, color |
| Button | on-screen button | text, width, height, color, **action** |
| Body | moving thing with collisions | vx, vy, gravity, width, height, solid, static, bounce, color |
| Camera | view centre | zoom |
| Sound | sound holder | file |

Positions are in game units (project size, default 1280x720, y grows downward) and add up through parents.
A **Button with an `action`** (`left`, `right`, `up`, `down`, `jump`, `action`) counts as that action while held: scripts read it with `isDown("left")` /
`axis("left", "right")`. The keyboard (arrows/WASD, space, Z) feeds the same actions.
**Body**: gravity pulls it down; a body that is `solid` and `static` is ground that other bodies stand on (`onGround()` becomes true).

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
| `hear hit -> onHit` | listen to your own broadcast |
| `hear hit from @Enemy as EnemyScript -> onHit` | listen to another node's broadcast |
| `spawn "enemy.nscn" under @/World` | instance a scene as a child (add `as Type` for the result) |
| `wait 1.5` | pause an `async fn` for 1.5 seconds |

A handler may take fewer parameters than the broadcast sends. Built-in broadcasts: `tapped(x, y)` (Node2D, needs `touchable`;
Buttons are touchable), `collided(other)` (Body), `freed` (every node). Handlers of freed nodes are skipped automatically.

### What every script can call (bare, inherited from `nebula.Node`)

`log(x)`, `isDown("jump")`, `axis("left","right")`, `touches()` (list of Vec2), `screenSize()`, `play("sound.wav")`, `goto("scenes/level2.nscn")`,
`save("key", value)` / `load("key", "fallback")`, tree: `name()`, `parent()`, `child(i)`, `children()`, `add("Sprite", "Name")`, `free()`, `tag("x")`, `hasTag("x")`;
`nebula.nodesTagged("enemy")` for all tagged nodes; `Vec2(x, y)` with `plus minus times length normalized distanceTo`.
Node2D: `x() y() position() setPosition(x,y) moveBy(dx,dy) rotation() setRotation(d) turnBy(d) setScale(s) setVisible(b) setLayer(n) setTouchable(b)`.
Sprite: `setImage(file) setFlipX(b)`. Shape: `setColor("#RRGGBB") setSize(w,h)`. Label: `setText(x) setColor() setFontSize()`.
Body: `velocity() setVelocity(vx,vy) push(dvx,dvy) setGravity(g) onGround()`. Camera: `setZoom(z)`.
The full list is `api/nebula.sn` (plain SuperNova: read it).

## 5. Run, build, export

* **Run** (F5) saves, builds the scripts through Termux (a few seconds), and plays the main scene in the editor. Compile errors show in the console with the
  `.nbs` file and line; tap to jump. Mistakes in Nebula syntax are shown live in the editor as you type.
* **Export APK** (Ctrl+E): enter app name and package id (`com.you.mygame`). Nebula copies its own player, adds your project and scripts, signs it and can install it.
  The default signing key is a built-in debug key (fine for sideloading); for a store release choose your own PKCS12 keystore in Settings.
  The export is arm64 only. Exported games contain the player code, so they are larger than a bare game would be.

## 6. Project folder

```
NebulaProjects/MyGame/
  project.nebula        title, main scene, package id, size, orientation
  scenes/*.nscn         text scenes (readable, git-friendly)
  scripts/*.nbs
  assets/               images (.png .jpg .webp) and sounds (.wav .ogg .mp3), imported from the Files tab
  build/                generated: game.sn, scripts.meta, libscripts.so
  export/               exported APKs
```
