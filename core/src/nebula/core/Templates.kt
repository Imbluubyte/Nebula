package nebula.core

/** Files of a new project: a tiny playable game that shows scripts, broadcasts, @paths and onEvery. */
object Templates {
    fun starter(title: String): Map<String, String> = mapOf(
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
	node Hero Body script "scripts/hero.nbs" tags "hero"
		x = 200
		y = 400
		width = 48
		height = 48
		gravity = 1400
		solid = true
		color = "#FFB300"
		tweak speed = 320
	node Coin Body script "scripts/coin.nbs"
		x = 700
		y = 620
		width = 32
		height = 32
		static = true
		color = "#FFEB3B"
	node Score Label script "scripts/score.nbs"
		x = 120
		y = 50
		text = "Score 0"
		fontSize = 36
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
        "scripts/hero.nbs" to """
# The hero: moves with the arrow keys / on-screen buttons and jumps.
script Hero of Body:
	tweak speed = 320
	tweak jumpPower = 640

	broadcast jumped

	fn onReady():
		hear collided -> onTouch
		hear tapped from @/World/Jump as Button -> jump

	fn onFrame():
		setVelocity(axis("left", "right") * speed, velocity().y)
		if isDown("jump") and onGround():
			jump()

	fn jump():
		if onGround():
			push(0.0, -jumpPower)
			shout jumped

	fn onTouch(other: Node):
		if other.hasTag("coin"):
			log("touched a coin")
""".trimStart(),
        "scripts/coin.nbs" to """
# A coin: tells the score when the hero touches it, then vanishes.
script Coin of Body:
	broadcast collected(value: Int)

	fn onReady():
		tag("coin")
		hear collided -> onHit

	fn onHit(other: Node):
		if other.hasTag("hero"):
			shout collected(10)
			free()
""".trimStart(),
        "scripts/score.nbs" to """
# Shows the score. Listens to the coin's broadcast.
script Score of Label:
	var total = 0

	fn onReady():
		hear collected from @/World/Coin as Coin -> addScore

	fn addScore(value: Int):
		total += value
		setText(f"Score {total}")

	fn onEvery(5):
		log(f"still playing, score {total}")
""".trimStart(),
    )
}
