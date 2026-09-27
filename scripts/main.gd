extends Node3D
## Builds the level and runs the round: aiming, shots, scoring, resets.
##
## Controls (touch; mouse works the same on desktop):
##   drag starting on/near the ball  -> pull back to aim, release to putt
##   drag anywhere else              -> orbit camera
##   pinch / mouse wheel             -> zoom
##
## Command-line helpers (after `--`):
##   --screenshot=<file.png> [--view=cx,cy,cz,tx,ty,tz]  render one frame and quit
##   --autotest                                          run physics checks and quit

enum State { READY, AIMING, ROLLING, HOLED }

const Hole := preload("res://scripts/course/hole_lagoon_lane.gd")
const AIM_GRAB_RADIUS := 0.16     # fraction of screen height
const FULL_PULL := 0.32           # drag length (fraction of screen height) for max power

var state := State.READY
var strokes := 0
var last_rest := Vector3.ZERO

var course := CourseBuilder.new()
var ball: GolfBall
var rig: CameraRig
var arrow: AimArrow
var hud: Hud

var _touches := {}
var _aim_index := -1
var _aim_start := Vector2.ZERO
var _aim_power := 0.0
var _aim_dir := Vector3.FORWARD
var _args := {}


func _ready() -> void:
	_parse_args()
	var shape := Hole.shape()
	var level := Node3D.new()
	level.name = "Level"
	add_child(level)

	course.build(shape, Hole.CUP, level)
	SkyAndSea.build(level, Vector3(Hole.ISLAND_CENTRE.x, 0, Hole.ISLAND_CENTRE.y))

	var island := Island.new(CourseField.new(shape, 0.25, 3.0), Hole.ISLAND_CENTRE, Hole.ISLAND_RADIUS)
	island.add_pond(Hole.POND, Hole.POND_RADIUS)
	island.build(level)
	island.place_palms(level, Hole.PALMS)

	var windmill := Windmill.new()
	windmill.position = Hole.WINDMILL
	level.add_child(windmill)

	var flag := Props.pin_flag()
	flag.position = course.cup
	level.add_child(flag)

	var sign := Props.hole_sign(Hole.NUMBER, Hole.TITLE, Hole.PAR)
	sign.position = Vector3(-1.25, island.height_at(Vector2(-1.25, 0.9)), 0.9)
	sign.rotation.y = deg_to_rad(20)
	level.add_child(sign)

	var tee_mark := MeshInstance3D.new()
	var tee_st := MeshKit.begin()
	MeshKit.prism(tee_st, Vector3.ZERO, 0.16, 0.16, 0.004, 10, Color(0.24, 0.52, 0.2))
	tee_mark.mesh = MeshKit.finish(tee_st)
	tee_mark.material_override = MeshKit.facet_material()
	tee_mark.position = Hole.TEE
	level.add_child(tee_mark)

	ball = GolfBall.new()
	add_child(ball)
	ball.came_to_rest.connect(_on_ball_rest)
	last_rest = Hole.TEE + Vector3.UP * (GolfBall.RADIUS + 0.005)
	ball.place(last_rest)

	arrow = AimArrow.new()
	add_child(arrow)

	rig = CameraRig.new()
	add_child(rig)
	rig.target = ball
	rig.camera.current = true
	rig.face_toward(Vector3(0, 0, -6))
	rig.snap()

	hud = Hud.new()
	add_child(hud)
	hud.setup(Hole.NUMBER, Hole.TITLE, Hole.PAR)
	hud.reset_pressed.connect(_on_reset_pressed)
	hud.banner_tapped.connect(_restart)

	if _args.has("screenshot"):
		_take_screenshot.call_deferred()
	elif _args.has("autotest"):
		_autotest.call_deferred()


func _physics_process(_delta: float) -> void:
	if state != State.ROLLING:
		return
	var p := ball.global_position
	var to_cup := Vector2(p.x - course.cup.x, p.z - course.cup.z).length()
	if to_cup < CourseBuilder.CUP_RADIUS and p.y < course.cup.y - GolfBall.RADIUS * 1.2:
		_holed()
	elif p.y < SkyAndSea.WATER_LEVEL + 0.2:
		hud.toast("Splash!  +1")
		strokes += 1
		hud.set_strokes(strokes)
		_return_ball()


# --- input ------------------------------------------------------------------

func _unhandled_input(event: InputEvent) -> void:
	var vh := get_viewport().get_visible_rect().size.y
	if event is InputEventScreenTouch:
		if event.pressed:
			_touches[event.index] = event.position
			if _touches.size() == 1 and state == State.READY and _near_ball(event.position, vh):
				state = State.AIMING
				_aim_index = event.index
				_aim_start = event.position
				_aim_power = 0.0
		else:
			if state == State.AIMING and event.index == _aim_index:
				_release_shot()
			_touches.erase(event.index)
	elif event is InputEventScreenDrag:
		var prev: Vector2 = _touches.get(event.index, event.position)
		_touches[event.index] = event.position
		if state == State.AIMING and event.index == _aim_index:
			_update_aim(event.position, vh)
		elif _touches.size() == 2:
			var other: Vector2
			for k in _touches:
				if k != event.index:
					other = _touches[k]
			var before: float = prev.distance_to(other)
			var after: float = event.position.distance_to(other)
			if before > 1.0 and after > 1.0:
				rig.zoom(before / after)
		elif _touches.size() == 1:
			rig.orbit(event.relative, vh)
	elif event is InputEventMouseButton and event.pressed:
		if event.button_index == MOUSE_BUTTON_WHEEL_UP:
			rig.zoom(0.9)
		elif event.button_index == MOUSE_BUTTON_WHEEL_DOWN:
			rig.zoom(1.1)


func _near_ball(screen_pos: Vector2, vh: float) -> bool:
	var cam := rig.camera
	if cam.is_position_behind(ball.global_position):
		return false
	return cam.unproject_position(ball.global_position).distance_to(screen_pos) < vh * AIM_GRAB_RADIUS


func _update_aim(screen_pos: Vector2, vh: float) -> void:
	var cam := rig.camera
	var plane := Plane(Vector3.UP, ball.global_position.y)
	var from := cam.project_ray_origin(screen_pos)
	var hit: Variant = plane.intersects_ray(from, cam.project_ray_normal(screen_pos))
	if hit == null:
		return
	var pull: Vector3 = ball.global_position - (hit as Vector3)
	pull.y = 0.0
	if pull.length() < 0.001:
		return
	_aim_dir = pull.normalized()
	_aim_power = clampf(screen_pos.distance_to(_aim_start) / (vh * FULL_PULL), 0.0, 1.0)
	arrow.show_aim(ball.global_position - Vector3.UP * GolfBall.RADIUS, _aim_dir, _aim_power)
	hud.set_power(_aim_power, true)


func _release_shot() -> void:
	arrow.visible = false
	hud.set_power(0.0, false)
	_aim_index = -1
	if _aim_power < 0.04:
		state = State.READY
		return
	shoot(_aim_dir, _aim_power)


# --- game flow --------------------------------------------------------------

func shoot(direction: Vector3, power: float) -> void:
	strokes += 1
	hud.set_strokes(strokes)
	state = State.ROLLING
	var speed := lerpf(0.25, GolfBall.MAX_SPEED, pow(power, 1.35))
	ball.strike(Vector3(direction.x, 0, direction.z).normalized() * speed)


func _on_ball_rest() -> void:
	if state != State.ROLLING:
		return
	last_rest = ball.global_position
	state = State.READY


func _return_ball() -> void:
	ball.place(last_rest)
	rig.snap()
	state = State.READY


func _on_reset_pressed() -> void:
	if state == State.HOLED:
		return
	_return_ball()


func _holed() -> void:
	state = State.HOLED
	var names := {-3: "Albatross!", -2: "Eagle!", -1: "Birdie!", 0: "Par", 1: "Bogey", 2: "Double Bogey"}
	var title: String = "Hole in One!" if strokes == 1 else names.get(strokes - Hole.PAR, "+%d" % (strokes - Hole.PAR))
	hud.show_banner(title, "%d stroke%s  ·  tap to play again" % [strokes, "" if strokes == 1 else "s"])


func _restart() -> void:
	hud.hide_banner()
	strokes = 0
	hud.set_strokes(0)
	last_rest = Hole.TEE + Vector3.UP * (GolfBall.RADIUS + 0.005)
	ball.place(last_rest)
	rig.face_toward(Vector3(0, 0, -6))
	rig.snap()
	state = State.READY


# --- tooling ----------------------------------------------------------------

func _parse_args() -> void:
	for a in OS.get_cmdline_user_args():
		var parts := a.trim_prefix("--").split("=", true, 1)
		_args[parts[0]] = parts[1] if parts.size() > 1 else ""


func _take_screenshot() -> void:
	if _args.has("view"):
		var v: PackedFloat64Array = (_args["view"] as String).split_floats(",")
		rig.set_process(false)
		rig.camera.global_position = Vector3(v[0], v[1], v[2])
		rig.camera.look_at(Vector3(v[3], v[4], v[5]))
	if _args.has("aim"):
		var p := float(_args["aim"])
		arrow.show_aim(ball.global_position - Vector3.UP * GolfBall.RADIUS, Vector3.FORWARD, p)
		hud.set_power(p, true)
	if _args.has("banner"):
		strokes = 2
		_holed()
	for i in 90:
		await get_tree().process_frame
	var img := get_viewport().get_texture().get_image()
	img.save_png(_args["screenshot"])
	print("Saved screenshot to ", _args["screenshot"])
	get_tree().quit()


func _autotest() -> void:
	var ok := true
	# 1. A soft putt on the flat tee should roll, stop, and stay on the course.
	_restart()
	shoot(Vector3.FORWARD, 0.25)
	ok = await _wait_rest(8.0, "short putt") and ok
	print("short putt rest at ", ball.global_position, " strokes=", strokes)
	ok = _check(ball.global_position.z < Hole.TEE.z - 0.3, "ball moved forward") and ok
	ok = _check(ball.global_position.y > 0.55, "ball stayed on raised tee") and ok

	# 2. A full-power drive goes down the ramp and toward the windmill.
	shoot(Vector3.FORWARD, 1.0)
	ok = await _wait_rest(20.0, "drive") and ok
	print("drive rest at ", ball.global_position)
	ok = _check(ball.global_position.z < -4.0, "drive reached lower level") and ok

	# 3. Gentle putt from next to the cup drops in.
	_restart()
	ball.place(course.cup + Vector3(-0.5, GolfBall.RADIUS + 0.005, 0))
	shoot(Vector3.RIGHT, 0.22)
	for i in 600:
		await get_tree().physics_frame
		if state == State.HOLED:
			break
	print("putt ended at ", ball.global_position, " cup ", course.cup)
	ok = _check(state == State.HOLED, "putt into cup is holed") and ok

	# 4. Rolling off into the water returns the ball with a penalty.
	_restart()
	ball.place(Vector3(Hole.POND.x, 1.0, Hole.POND.y))
	ball.strike(Vector3.ZERO)
	state = State.ROLLING
	for i in 400:
		await get_tree().physics_frame
		if state == State.READY:
			break
	ok = _check(state == State.READY and strokes == 1, "water hazard resets with penalty") and ok

	print("AUTOTEST ", "PASS" if ok else "FAIL")
	get_tree().quit(0 if ok else 1)


func _wait_rest(timeout: float, label: String) -> bool:
	var t := 0.0
	while state == State.ROLLING and t < timeout:
		await get_tree().physics_frame
		t += get_physics_process_delta_time()
	return _check(state == State.READY, label + " came to rest")


func _check(cond: bool, label: String) -> bool:
	print(("  ok   " if cond else "  FAIL ") + label)
	return cond
