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
| `hear hit -> onHit` | calls `onHit` whenever your own `hit` is shouted |
| `hear hit from @Enemy as EnemyScript -> onHit` | listens to another node's broadcast; the `as` type says whose broadcast it is |
| `hear collided -> onTouch` | built-in broadcasts work the same way |

A handler may take fewer parameters than the broadcast sends. Handlers whose node was freed are skipped automatically.

Built-in broadcasts:

| Broadcast | On | Sent when |
| --- | --- | --- |
| `tapped(x, y)` | every Node2D (needs `touchable`; Buttons are touchable) | the node is tapped |
| `collided(other)` | Body | it starts touching another Body |
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

### Shape, Label, Body, Camera
Shape: `color() setColor("#RRGGBB") width() height() setSize(w, h)`.
Label: `text() setText(x) setColor(hex) setFontSize(n)`.
Button: `text() setText(x)`.
Body: `velocity() setVelocity(vx, vy) push(dvx, dvy) setGravity(g) onGround()`.
Camera: `setZoom(z)`.

## Properties in scenes (`.nscn`) and the inspector

| Node | Properties |
| --- | --- |
| Node2D | `x y rotation scale layer visible touchable` |
| Sprite | `image flipX width height` and for sheets: `hframes vframes frame`; for animation: `animations animation playing speed` |
| Shape | `shape (rect or circle) width height color` |
| Label | `text fontSize color` |
| Button | `text width height fontSize color action` |
| Body | `vx vy gravity width height solid static bounce color` |
| Camera | `zoom` |
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
