# Nebula syntax reference

Nebula scripts (`.nbs`) are plain SuperNova plus the forms below. Everything else (`var`, `fn`, `if/orif/else`, `for`, lists,
dicts, classes, `async`, `try`, `use`) is normal SuperNova. Indent with tabs. Lists and strings are 1-based.

## Declaring a script

### `script Name of Type:`
The first line of a script file. The script becomes a class attached to a node. `Type` is a built-in node type
(`Node`, `Node2D`, `Sprite`, `Shape`, `Label`, `Button`, `Body`, `Camera`, `Sound`) or another script, which makes this script inherit from it.

```
script Hero of Body:
	fn onReady():
		log("hello")
```

### `tweak name = value`
A variable that also shows up as a field in the inspector, so every node using the script can set its own value.
The type comes from the value (`320` Int, `2.5` Float, `true` Bool, `"text"` String) or can be written: `tweak speed: Float = 320`.

```
	tweak speed = 320
	tweak title = "Boss"
```

## Engine hooks
Write them as normal functions; Nebula calls them for you.

| Hook | When it runs |
| --- | --- |
| `fn onReady():` | once when the scene starts (children before their parents) |
| `fn onFrame():` | every frame; `delta` is the frame time in seconds |
| `fn onTick(30):` | a fixed number of times per second (1 to 240); `delta` is `1 / rate`. Different rates can exist side by side |
| `fn onEvery(2.5):` | every 2.5 seconds. Several are allowed if the numbers differ |
| `fn onFree():` | just before the node is removed |

## Finding nodes

| Syntax | Meaning |
| --- | --- |
| `@Child` | the child called `Child` of this node |
| `@A/B` | a path down through children |
| `@^` | the parent |
| `@^/Sibling` | a sibling (go up, then down) |
| `@/Root/Path` | an absolute path from the scene root |
| `@Path as Type` | the same node as `Type` (a built-in type or one of your scripts), so that type's methods work |

A missing node stops with a clear error naming the path. `@` inside strings and comments is left alone.

```
(@Icon as Sprite).setImage("assets/star.png")
var boss = @/World/Boss as BossScript
```

## Broadcasts (signals)

| Syntax | Meaning |
| --- | --- |
| `broadcast hit(amount: Int)` | declares a broadcast with typed parameters (none needed: `broadcast died`) |
| `shout hit(5)` | sends it. Only broadcasts of your own script can be shouted |
| `fn onHear(@Enemy.hit, damage):` | the body runs whenever the node at `@Enemy` shouts `hit`; `damage` is the first value it sent |
| `fn onHear(@Enemy.hit, damage, accuracy):` | values are matched by position: `shout hit(5, 10)` gives `damage = 5`, `accuracy = 10` |
| `fn onHear(@Enemy as Boss.hit, damage):` | `as Type` says whose broadcast it is. Only needed when several types have a broadcast called `hit` |
| `fn onHear(hit, damage):` | no `@`: listens to your own broadcast (built-ins too: `fn onHear(collided, other):`) |

`@Enemy` is any node path. The type is found from the broadcast name, so `@Enemy.hit` works when only one script (or built-in node) declares `hit`.
A handler may list fewer names than the broadcast sends. You can write several `onHear`s, as long as each listens to a different node or broadcast.
They connect just before `onReady()` runs, so every node in the scene already exists. Handlers whose node was freed are skipped automatically.

Built-in broadcasts:

| Broadcast | On | Sent when |
| --- | --- | --- |
| `tapped(x, y)` | every Node2D (needs `touchable`; Buttons are touchable) | the node is tapped |
| `collided(other)` | Body | it starts touching another Body |
| `expired` | SpawnableBody | its `lifetime` ran out (it is freed right after) |
| `died` | SpawnableBody | `die()` ran, or `hurt()` took its health to 0 |
| `damaged(amount)` | SpawnableBody | `hurt(amount)` was called |
| `animationFinished` | Sprite | a clip that does not loop reaches its last frame |
| `freed` | every node | it is removed |

## Creating scenes and waiting

| Syntax | Meaning |
| --- | --- |
| `spawn "enemy.nscn" under @/World` | instances a scene as a child of that node |
| `spawn "enemy.nscn" under @/World as EnemyScript` | the same, and the result is typed |
| `wait 1.5` | pauses an `async fn` for 1.5 seconds |

## Functions every script can call
Inherited from `nebula.Node`, so no prefix is needed.

| Call | What it does |
| --- | --- |
| `log(x)` | prints to the console |
| `isDown("jump")` / `axis("left", "right")` | held actions (keyboard or on-screen Buttons with an `action`); `axis` gives -1, 0 or 1 |
| `touches()` | list of `Vec2` for every finger on the screen |
| `screenSize()` | the game's size as a `Vec2` |
| `play("assets/hit.wav")` | plays a sound once |
| `goto("scenes/level2.nscn")` | switches scene at the end of the frame |
| `save("key", value)` / `load("key", "fallback")` | stored data that survives restarts |
| `name() parent() child(i) children() childCount() add("Sprite", "Name") free() alive()` | the node tree |
| `tag("x") untag("x") hasTag("x")` and `nebula.nodesTagged("x")` | tags |
| `Vec2(x, y)` | `plus minus times length normalized distanceTo` |

### Node2D
`x() y() position() setPosition(x, y) moveBy(dx, dy) rotation() setRotation(d) turnBy(d) scale() setScale(s) visible() setVisible(b) layer() setLayer(n) touchable() setTouchable(b)`

### Sprite (pictures and sprite sheet animation)
`setImage(file) image() setFlipX(b) flipX()`

Frames of a sheet are numbered from 1, left to right then top to bottom.

| Call | What it does |
| --- | --- |
| `animate("run")` | plays the clip `run` of the sprite's animation set from its start (also replays a finished clip) |
| `stopAnimating()` | freezes the animation on the current frame |
| `isAnimating()` / `animation()` | whether it is playing / the current clip name |
| `setAnimations("animations/hero.nanim")` | changes the animation set |
| `setAnimSpeed(1.5)` | playback speed multiplier (1 is normal) |
| `frame()` / `setFrame(3)` | the shown frame; set it directly for a sprite sheet without clips |

### Sound
`start()` plays the node's sound (with its volume, pitch and loop), `stop()` stops it, `setFile(file) setVolume(0..1) setPitch(0.5..2) setLoop(b)`.
Set `autoplay` in the inspector to start it when the scene starts.

### TileMap
A grid of tiles. Properties `image tileWidth tileHeight hframes vframes columns rows solid solidTiles tiles`; `tiles` is stored as rows joined by `;` and cells by `,` (`1,1,1;;0,2`), trailing empties left out, and is normally written by the tile painter.
`columns() rows() tileWidth() tileHeight() tileAt(col, row) -> Int setTile(col, row, tile) clearTile(col, row) fillTiles(tile) setSolid(b)`,
`colAt(worldX) -> Int rowAt(worldY) -> Int tileAtPoint(worldX, worldY) -> Int cellCenter(col, row) -> Vec2`. Columns and rows count from 1; tile 0 is empty.
A solid TileMap stops every Body type; it sends `collided(map)` to a body when it first touches it.

### SpawnableBody
A Body (so everything below Body applies) made to be spawned many times. Properties `lifetime health maxHealth damage`.
`lifetime() setLifetime(s) age() health() maxHealth() setHealth(n) setMaxHealth(n) hurt(n) heal(n) die() isDead() damage() setDamage(n) dealDamage(other) -> Bool`.
`launch(angle, speed)`, `launchAt(node, speed)`, `angleTo(x, y)`, `chase(node, speed)`; angles are degrees, 0 = right, 90 = down.
Callable from any script: `spawnBody(scene, parent, x, y) -> SpawnableBody` (the scene root must be a SpawnableBody), `spawnBurst(scene, parent, x, y, count, speed, startAngle = 0.0)`,
`spawnRing(scene, parent, x, y, count, radius, startAngle = 0.0)` (both return the list of bodies), `countTagged(label)`, `nearest(label, x, y) -> Node2D` (throws when nothing is tagged).

### DynamicBar
A bar that fills itself from `value`, `minValue` and `maxValue` (floats, defaults 100, 0, 100) with no picture of its own. Two lines at the top of its script (one tab in, not inside a function) name its sprites:
`empty @Back` is the Sprite that shows the empty bar (with its border), `full @Fill` the Sprite that shows the full bar (no border); the full sprite is cropped to the fraction `(value - minValue) / (maxValue - minValue)`.
Paths are relative to the bar (`@Frame/Fill`, `@^/Other`). They only work in a script of DynamicBar (or a script that extends one, which inherits them).
`setValue(v) value() fill(n) drain(n) fillUp() emptyOut() setMin(v) setMax(v) setRange(lo, hi) minValue() maxValue() percent() isFull() isEmpty() direction() setDirection("right"|"left"|"up"|"down")`.
`setValue` clamps to the range; `setMin`/`setMax`/`setRange` re-clamp the value. (`empty()` is called `emptyOut()` because `empty` is the sprite line.)

### Shape, Label, Body, Camera
Shape: `color() setColor("#RRGGBB") width() height() setSize(w, h)`.
Label: `text() setText(x) setColor(hex) setFontSize(n)`.
Button: `text() setText(x)`.
Body: `velocity() setVelocity(vx, vy) push(dvx, dvy) setGravity(g) onGround()`.
Camera: `setZoom(z) zoom() followNode(node) followTag(tag) stopFollowing() isFollowing() setSmoothing(seconds) smoothing() setFollowAxes(horizontal, vertical) setOffset(dx, dy) setLimits(left, top, right, bottom) clearLimits() snapToTarget()`.
`smoothing` is a time constant in seconds (0 = locked on). `target` is a node path or `#tag`. While `follow` is on the engine moves the Camera node. Node2D: `setFixed(b) isFixed()`; a `fixed` node (and its children) is placed on the screen and ignores the camera.

## Properties in scenes (`.nscn`) and the inspector

| Node | Properties |
| --- | --- |
| Node2D | `x y rotation scale layer visible touchable fixed` |
| Sprite | `image flipX width height` and for sheets: `hframes vframes frame`; for animation: `animations animation playing speed` |
| Shape | `shape (rect or circle) width height color` |
| Label | `text fontSize color` |
| Button | `text width height fontSize color action` (with Sprite children it looks like them; a child Sprite named `Pressed` is shown only while held; `isPressed()`) |
| DynamicBar | `value minValue maxValue direction` (right, left, up, down) |
| Body | `vx vy gravity width height solid static bounce color` |
| SpawnableBody | everything of Body, plus `lifetime health maxHealth damage` |
| TileMap | `image tileWidth tileHeight hframes vframes columns rows solid solidTiles tiles` |
| Camera | `zoom follow target followX followY smoothing offsetX offsetY limits limitLeft limitTop limitRight limitBottom` |
| Sound | `file volume pitch loop autoplay` |

`hframes` and `vframes` cut a Sprite's image into a grid so `frame` picks one cell without an animation file.

## Animation files (`.nanim`)
Made and edited in the animation manager (Files tab, the film icon). They are small text files:

```
sheet assets/hero.png        # the sprite sheet image
grid 4 2                     # columns and rows
anim run 1 4 10 loop         # name, first frame, last frame, frames per second, loop or once
anim hit 5 7 12 once
```

A first frame bigger than the last plays the clip backwards. A Sprite uses an animation file by setting `animations` (the file) and
`animation` (the clip to play at start) in the inspector.
