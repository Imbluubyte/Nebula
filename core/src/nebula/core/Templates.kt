package nebula.core

/** The starting points offered by "New project". Every preset is a plain project folder: scenes/main.nscn plus scripts. */
enum class Preset(val id: String, val label: String, val blurb: String) {
    PLATFORMER("platformer", "Platformer", "Side view. A player that runs and jumps on a floor and a platform."),
    TOP_DOWN("topdown", "Top down", "Bird's-eye view. A player that walks in every direction inside a walled room."),
    BLANK("blank", "Blank", "An empty scene and no scripts.");

    companion object {
        fun byId(id: String): Preset = values().firstOrNull { it.id == id } ?: PLATFORMER
    }
}

/** Files of a new project. Scripts are written to be read: every line says what it does and what to change. */
object Templates {
    fun files(preset: Preset, title: String): Map<String, String> = when (preset) {
        Preset.PLATFORMER -> platformer()
        Preset.TOP_DOWN -> topDown()
        Preset.BLANK -> blank()
    }

    private fun blank() = mapOf("scenes/main.nscn" to "scene \"Main\"\nnode World Node2D\n")

    // ------------------------------------------------------------------ platformer
    private fun platformer() = mapOf(
        "scenes/main.nscn" to """
scene "Main"
node World Node2D
	node Ground Body
		x = 640
		y = 680
		width = 1280
		height = 40
		solid = true
		static = true
		color = "#37474F"
	node Platform Body
		x = 900
		y = 560
		width = 240
		height = 24
		solid = true
		static = true
		color = "#546E7A"
	node Player Body script "scripts/player.nbs" tags "player"
		x = 200
		y = 400
		width = 48
		height = 48
		gravity = 1400
		solid = true
		color = "#FFB300"
	node Left Button
		action = "left"
		x = 120
		y = 640
		text = "<"
		width = 120
		height = 96
	node Right Button
		action = "right"
		x = 270
		y = 640
		text = ">"
		width = 120
		height = 96
	node Jump Button
		action = "jump"
		x = 1160
		y = 640
		text = "Jump"
		width = 160
		height = 96
""".trimStart(),
        "scripts/player.nbs" to """
# PLAYER - platformer preset
#
# This script is attached to the Player node in scenes/main.nscn.
# Controls: arrow keys or A / D to run, Space to jump. On a touch screen the on-screen buttons do the same,
# because each Button node has an "action" (left, right, jump) that isDown() and axis() understand.
#
# Things you can tweak without touching any code:
#   - the numbers under "tweak" below (they also show up in the Inspector when the Player is selected)
#   - the Player node's gravity, width, height and color in the Inspector
script Player of Body:
	# How fast the player runs, in pixels per second. Bigger = faster.
	tweak speed = 320
	# How hard a jump pushes upwards. Bigger = a higher jump.
	# (With gravity 1400 a power of 640 jumps about 145 pixels high.)
	tweak jumpPower = 640

	# onFrame() runs once for every frame drawn, so it is the place for movement and controls.
	fn onFrame():
		# axis("left", "right") is -1 while "left" is held, +1 while "right" is held, and 0 otherwise.
		# We set only the sideways speed and keep the current up/down speed, so gravity and jumps still work.
		setVelocity(axis("left", "right") * speed, velocity().y)

		# onGround() is true while the player stands on something solid.
		# Remove "and onGround()" to allow jumping in mid-air (a double jump that never ends).
		if isDown("jump") and onGround():
			# push() adds to the current speed. Up is negative because y grows downwards on screen.
			push(0.0, -jumpPower)
""".trimStart(),
    )

    // ------------------------------------------------------------------ top down
    private fun topDown() = mapOf(
        "scenes/main.nscn" to """
scene "Main"
node World Node2D
	node Floor Shape
		x = 640
		y = 360
		width = 1280
		height = 720
		color = "#4E6E3A"
	node WallTop Body
		x = 640
		y = 20
		width = 1280
		height = 40
		solid = true
		static = true
		color = "#37474F"
	node WallBottom Body
		x = 640
		y = 700
		width = 1280
		height = 40
		solid = true
		static = true
		color = "#37474F"
	node WallLeft Body
		x = 20
		y = 360
		width = 40
		height = 720
		solid = true
		static = true
		color = "#37474F"
	node WallRight Body
		x = 1260
		y = 360
		width = 40
		height = 720
		solid = true
		static = true
		color = "#37474F"
	node Player Body script "scripts/player.nbs" tags "player"
		x = 640
		y = 360
		width = 40
		height = 40
		solid = true
		color = "#FFB300"
	node Left Button
		action = "left"
		x = 100
		y = 560
		text = "<"
		width = 100
		height = 90
	node Right Button
		action = "right"
		x = 300
		y = 560
		text = ">"
		width = 100
		height = 90
	node Up Button
		action = "up"
		x = 200
		y = 470
		text = "^"
		width = 100
		height = 90
	node Down Button
		action = "down"
		x = 200
		y = 650
		text = "v"
		width = 100
		height = 90
""".trimStart(),
        "scripts/player.nbs" to """
# PLAYER - top down preset
#
# This script is attached to the Player node in scenes/main.nscn.
# Controls: arrow keys or WASD to walk in any direction. On a touch screen the four on-screen buttons do the same,
# because each Button node has an "action" (left, right, up, down) that axis() understands.
#
# The Player has no gravity (see its Inspector), so it only moves when this script tells it to.
# The four Wall nodes are solid and static, so the player cannot walk through them.
#
# Things you can tweak without touching any code:
#   - the number under "tweak" below (it also shows up in the Inspector when the Player is selected)
#   - the Player node's width, height and color in the Inspector
script Player of Body:
	# How fast the player walks, in pixels per second. Bigger = faster.
	tweak speed = 300

	# onFrame() runs once for every frame drawn, so it is the place for movement and controls.
	fn onFrame():
		# axis(a, b) is -1 while "a" is held, +1 while "b" is held and 0 otherwise.
		# Together the two axes make a direction: for example right + down = (1, 1).
		var dir = Vec2(axis("left", "right"), axis("up", "down"))

		# Walking diagonally would be about 40 percent faster (the direction (1, 1) is longer than (1, 0)).
		# normalized() shrinks any direction to length 1, so every direction is equally fast.
		# Delete these two lines if you want the faster diagonal.
		if dir.length() > 0.0:
			dir = dir.normalized()

		# Turn the direction into a speed. Standing still gives (0, 0), which stops the player.
		setVelocity(dir.x * speed, dir.y * speed)
""".trimStart(),
    )
}
