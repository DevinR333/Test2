extends Node3D
## Runs an 18-hole round: builds each hole from Holes data, handles aiming,
## shots, scoring and moving on to the next hole.
##
## Controls (touch; mouse works the same on desktop):
##   drag starting on/near the ball  -> pull back to aim, release to putt
##   drag anywhere else              -> orbit camera
##   pinch / mouse wheel             -> zoom
##
## Command-line helpers (after `--`):
##   --hole=N                                   start on hole N
##   --screenshot=<file.png> [--view=cx,cy,cz,tx,ty,tz | --view=auto]
##   --contact=<file.png>                       render all 18 holes into one sheet
##   --autotest                                 run physics/geometry checks and quit

enum State { LOADING, READY, AIMING, ROLLING, HOLED }

const AIM_GRAB_RADIUS := 0.16     # fraction of screen height
const FULL_PULL := 0.32           # drag length (fraction of screen height) for max power
const TEE_PAD_RADIUS := 0.85

var state := State.LOADING
var hole_number := 1
var hole: Dictionary
var strokes := 0
var scores: Array = []
var last_rest := Vector3.ZERO
var tee := Vector3.ZERO

var course: CourseBuilder
var level: Node3D
var island: Island
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
	scores.resize(Holes.COUNT)
	scores.fill(0)

	ball = GolfBall.new()
	add_child(ball)
	ball.came_to_rest.connect(_on_ball_rest)
	arrow = AimArrow.new()
	add_child(arrow)
	rig = CameraRig.new()
	add_child(rig)
	rig.target = ball
	rig.camera.current = true
	hud = Hud.new()
	add_child(hud)
	hud.reset_pressed.connect(_on_reset_pressed)
	hud.banner_tapped.connect(_on_banner_tapped)
	hud.hole_chosen.connect(func(n: int): load_hole(n))
	hud.play_again.connect(_new_round)

	var start := clampi(int(_args.get("hole", "1")), 1, Holes.COUNT)
	if _args.has("contact"):
		_contact_sheet.call_deferred()
		return
	await load_hole(start)
	if _args.has("screenshot"):
		_take_screenshot()
	elif _args.has("autotest"):
		_autotest()


# --- building a hole -------------------------------------------------------

func load_hole(n: int) -> void:
	state = State.LOADING
	hud.hide_banner()
	hud.hide_scorecard()
	hud.show_loading("Hole %d" % n)
	# Let the loading card draw before the (blocking) build.
	await get_tree().process_frame
	await get_tree().process_frame

	if level:
		remove_child(level)
		level.queue_free()
	hole_number = n
	hole = Holes.get_hole(n)
	_build_level()

	strokes = 0
	hud.setup(n, hole["title"], int(hole["par"]))
	_update_totals()
	last_rest = tee + Vector3.UP * (GolfBall.RADIUS + 0.005)
	ball.place(last_rest)
	var first_path: Array = hole["paths"][0][0]
	rig.face_toward(first_path[2] if first_path.size() > 2 else first_path[1])
	rig.distance = 3.0
	rig.pitch = deg_to_rad(24.0)
	rig.snap()
	hud.hide_loading()
	if n == 1 and scores[0] == 0:
		hud.set_hint("Touch the ball and pull back to aim, let go to putt  ·  drag to look  ·  pinch to zoom")
	state = State.READY


func _build_level() -> void:
	level = Node3D.new()
	level.name = "Hole%d" % hole_number
	add_child(level)

	var shape := LaneShape.new()
	for p: Array in hole["paths"]:
		shape.add_path(p[0], p[1])
	var first_path: Array = hole["paths"][0][0]
	tee = first_path[0]
	shape.add_pad(tee, TEE_PAD_RADIUS)
	for pad: Array in hole["pads"]:
		shape.add_pad(pad[0], pad[1])

	course = CourseBuilder.new()
	course.build(shape, hole["cup"], level)

	var bounds := shape.bounds()
	var centre := bounds.get_center()
	var radius := maxf(bounds.size.x, bounds.size.y) * 0.5 + 3.5
	SkyAndSea.build(level, Vector3(centre.x, 0, centre.y), hole["golden"])

	island = Island.new(CourseField.new(shape, 0.25, 3.0), centre, radius, hole_number)
	for pond: Array in hole["ponds"]:
		island.add_pond(pond[0], pond[1])
	island.build(level)
	island.place_palms(level, 6 + hole_number % 3, 77 + hole_number)

	Obstacles.build(level, hole["obstacles"], course.field)
	for d: Dictionary in hole["decor"]:
		var node := Props.decor(d)
		var at: Vector2 = d["at"]
		var on_course: bool = d["type"] == "log_tunnel"
		var y := course.field.sample(at).y if on_course else island.height_at(at) - 0.05
		node.position = Vector3(at.x, y, at.y)
		node.rotation.y = d.get("yaw", 0.0)
		level.add_child(node)

	var flag := Props.pin_flag()
	flag.position = course.cup
	level.add_child(flag)

	_place_sign(first_path)

	var tee_mark := MeshInstance3D.new()
	var tee_st := MeshKit.begin()
	MeshKit.prism(tee_st, Vector3.ZERO, 0.16, 0.16, 0.004, 10, Color(0.24, 0.52, 0.2))
	tee_mark.mesh = MeshKit.finish(tee_st)
	tee_mark.material_override = MeshKit.facet_material()
	tee_mark.position = tee
	level.add_child(tee_mark)


func _place_sign(first_path: Array) -> void:
	var t2 := Vector2(tee.x, tee.z)
	var nxt: Vector3 = first_path[1]
	var dir := (Vector2(nxt.x, nxt.z) - t2).normalized()
	var side := Vector2(-dir.y, dir.x)
	for offset in [-side * 1.4 - dir * 0.3, side * 1.4 - dir * 0.3, -side * 1.9 + dir * 0.6, -dir * 1.6 - side * 0.9]:
		var p: Vector2 = t2 + offset
		if island.is_land(p, 0.35):
			var sign := Props.hole_sign(hole_number, hole["title"], int(hole["par"]))
			sign.position = Vector3(p.x, island.height_at(p), p.y)
			sign.rotation.y = atan2(-dir.x, -dir.y)
			level.add_child(sign)
			return


# --- per-frame rules -------------------------------------------------------

func _physics_process(_delta: float) -> void:
	if state != State.ROLLING:
		return
	var p := ball.global_position
	var to_cup := Vector2(p.x - course.cup.x, p.z - course.cup.z).length()
	if to_cup < CourseBuilder.CUP_RADIUS and p.y < course.cup.y - GolfBall.RADIUS * 1.2:
		_holed()
	elif p.y < SkyAndSea.WATER_LEVEL + 0.2 or p.y < tee.y - 3.0:
		hud.toast("Splash!  +1")
		strokes += 1
		hud.set_strokes(strokes)
		_return_ball()


# --- input -----------------------------------------------------------------

func _unhandled_input(event: InputEvent) -> void:
	if state == State.LOADING:
		return
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
	var hit: Variant = plane.intersects_ray(cam.project_ray_origin(screen_pos), cam.project_ray_normal(screen_pos))
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


# --- game flow -------------------------------------------------------------

func shoot(direction: Vector3, power: float) -> void:
	hud.set_hint("")
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
	if state == State.READY or state == State.ROLLING or state == State.AIMING:
		arrow.visible = false
		hud.set_power(0.0, false)
		_return_ball()


func _holed() -> void:
	state = State.HOLED
	scores[hole_number - 1] = strokes
	_update_totals()
	var par := int(hole["par"])
	var names := {-3: "Albatross!", -2: "Eagle!", -1: "Birdie!", 0: "Par", 1: "Bogey", 2: "Double Bogey"}
	var title: String = "Hole in One!" if strokes == 1 else names.get(strokes - par, "+%d" % (strokes - par))
	var next := "tap for Hole %d" % (hole_number + 1) if hole_number < Holes.COUNT else "tap for your scorecard"
	hud.show_banner(title, "%d stroke%s  ·  %s" % [strokes, "" if strokes == 1 else "s", next])


func _on_banner_tapped() -> void:
	if state != State.HOLED:
		return
	if hole_number < Holes.COUNT:
		load_hole(hole_number + 1)
	else:
		hud.hide_banner()
		hud.show_scorecard(scores, true)


func _new_round() -> void:
	scores.fill(0)
	load_hole(1)


func _update_totals() -> void:
	var played := 0
	var par_played := 0
	for i in Holes.COUNT:
		if scores[i] > 0:
			played += scores[i]
			par_played += int(Holes.get_hole(i + 1)["par"])
	hud.set_total(played, played - par_played)


# --- tooling ---------------------------------------------------------------

func _parse_args() -> void:
	for a in OS.get_cmdline_user_args():
		var parts := a.trim_prefix("--").split("=", true, 1)
		_args[parts[0]] = parts[1] if parts.size() > 1 else ""


func _overview_view() -> void:
	var b := course.field
	var centre := b.origin + Vector2(b.nx, b.nz) * b.cell * 0.5
	var size := maxf(b.nx, b.nz) * b.cell
	var target := Vector3(centre.x, 0.0, centre.y)
	rig.set_process(false)
	rig.camera.global_position = target + Vector3(-0.4, 0.9, 0.75) * (size * 0.5 + 1.5)
	rig.camera.look_at(target)


func _take_screenshot() -> void:
	if _args.has("view"):
		if _args["view"] == "auto":
			_overview_view()
		else:
			var v: PackedFloat64Array = (_args["view"] as String).split_floats(",")
			rig.set_process(false)
			rig.camera.global_position = Vector3(v[0], v[1], v[2])
			rig.camera.look_at(Vector3(v[3], v[4], v[5]))
	if _args.has("aim"):
		var p := float(_args["aim"])
		arrow.show_aim(ball.global_position - Vector3.UP * GolfBall.RADIUS, -rig.camera.global_basis.z, p)
		hud.set_power(p, true)
	if _args.has("banner"):
		strokes = 2
		_holed()
	if _args.has("card"):
		hud.show_scorecard(scores, false)
	for i in 90:
		await get_tree().process_frame
	get_viewport().get_texture().get_image().save_png(_args["screenshot"])
	print("Saved screenshot to ", _args["screenshot"])
	get_tree().quit()


func _contact_sheet() -> void:
	var cols := 6
	var tw := 480
	var th := 270
	var sheet := Image.create(tw * cols, th * 3, false, Image.FORMAT_RGB8)
	for n in range(1, Holes.COUNT + 1):
		await load_hole(n)
		hud.visible = false
		_overview_view()
		for i in 40:
			await get_tree().process_frame
		var img := get_viewport().get_texture().get_image()
		img.convert(Image.FORMAT_RGB8)
		img.resize(tw, th, Image.INTERPOLATE_BILINEAR)
		sheet.blit_rect(img, Rect2i(0, 0, tw, th), Vector2i(((n - 1) % cols) * tw, ((n - 1) / cols) * th))
		print("captured hole ", n)
	sheet.save_png(_args["contact"])
	print("Saved contact sheet to ", _args["contact"])
	get_tree().quit()


func _autotest() -> void:
	var ok := true
	# Every hole: geometry sanity + floor exists under the whole route.
	for n in range(1, Holes.COUNT + 1):
		var t0 := Time.get_ticks_msec()
		await load_hole(n)
		var build_ms := Time.get_ticks_msec() - t0
		ok = await _check_hole(build_ms) and ok

	# Touch controls: press on the ball, drag back toward the bottom of the
	# screen, release -> the ball is struck away from the camera.
	await load_hole(1)
	ok = await _touch_test() and ok

	# Gameplay on hole 1.
	await load_hole(1)
	shoot(Vector3.FORWARD, 0.25)
	ok = await _wait_rest(8.0, "short putt") and ok
	ok = _check(ball.global_position.z < tee.z - 0.3 and ball.global_position.y > 0.55, "putt rolled forward on the raised tee") and ok
	shoot(Vector3.FORWARD, 1.0)
	ok = await _wait_rest(20.0, "drive") and ok
	ok = _check(ball.global_position.z < -4.0, "drive reached lower level (%s)" % ball.global_position) and ok

	ball.place(course.cup + Vector3(-0.5, GolfBall.RADIUS + 0.005, 0))
	shoot(Vector3.RIGHT, 0.22)
	for i in 600:
		await get_tree().physics_frame
		if state == State.HOLED:
			break
	ok = _check(state == State.HOLED, "putt into cup is holed") and ok
	ok = _check(scores[0] == strokes, "score recorded on the card") and ok

	await load_hole(1)
	var pond: Vector2 = hole["ponds"][0][0]
	ball.place(Vector3(pond.x, 1.0, pond.y))
	ball.strike(Vector3.ZERO)
	state = State.ROLLING
	for i in 400:
		await get_tree().physics_frame
		if state == State.READY:
			break
	ok = _check(state == State.READY and strokes == 1, "water hazard resets with penalty") and ok

	print("AUTOTEST ", "PASS" if ok else "FAIL")
	get_tree().quit(0 if ok else 1)


func _touch(index: int, pos: Vector2, pressed: bool) -> void:
	var e := InputEventScreenTouch.new()
	e.index = index
	e.position = pos
	e.pressed = pressed
	get_viewport().push_input(e, true)


func _drag(index: int, pos: Vector2, relative: Vector2) -> void:
	var e := InputEventScreenDrag.new()
	e.index = index
	e.position = pos
	e.relative = relative
	get_viewport().push_input(e, true)


func _touch_test() -> bool:
	var ok := true
	await get_tree().process_frame
	var start := rig.camera.unproject_position(ball.global_position)
	var vh := get_viewport().get_visible_rect().size.y

	# One-finger drag away from the ball orbits the camera.
	var yaw0 := rig.yaw
	var far := start + Vector2(vh * 0.5, -vh * 0.3)
	_touch(0, far, true)
	_drag(0, far + Vector2(80, 0), Vector2(80, 0))
	_touch(0, far + Vector2(80, 0), false)
	ok = _check(not is_equal_approx(rig.yaw, yaw0) and strokes == 0, "touch: drag off the ball orbits the camera") and ok
	rig.face_toward(Vector3(0, 0, -6))
	rig.snap()
	await get_tree().process_frame
	start = rig.camera.unproject_position(ball.global_position)

	# Two-finger pinch zooms.
	var d0 := rig.distance
	_touch(0, start + Vector2(-200, -150), true)
	_touch(1, start + Vector2(200, -150), true)
	_drag(1, start + Vector2(300, -150), Vector2(100, 0))
	_touch(1, start + Vector2(300, -150), false)
	_touch(0, start + Vector2(-200, -150), false)
	ok = _check(rig.distance < d0, "touch: pinch zooms in") and ok

	# Pull back from the ball and release to putt.
	await get_tree().process_frame
	start = rig.camera.unproject_position(ball.global_position)
	var z0 := ball.global_position.z
	_touch(0, start, true)
	ok = _check(state == State.AIMING, "touch: pressing on the ball starts aiming") and ok
	var pos := start
	for i in 6:
		pos += Vector2(0, vh * 0.02)
		_drag(0, pos, Vector2(0, vh * 0.02))
	ok = _check(arrow.visible and _aim_power > 0.2, "touch: pulling back shows the aim arrow (power %.2f)" % _aim_power) and ok
	_touch(0, pos, false)
	ok = _check(state == State.ROLLING and strokes == 1, "touch: releasing takes the shot") and ok
	ok = await _wait_rest(10.0, "touch putt") and ok
	ok = _check(ball.global_position.z < z0 - 0.5, "touch: ball rolled toward the hole") and ok
	return ok


func _check_hole(build_ms: int) -> bool:
	var ok := true
	var label := "hole %d %s" % [hole_number, hole["title"]]
	var cup2: Vector2 = hole["cup"]
	ok = _check(course.field.sample(cup2).x < -0.25, label + ": cup well inside the lane") and ok
	ok = _check(course.field.sample(Vector2(tee.x, tee.z)).x < -0.3, label + ": tee inside the lane") and ok

	# Ray-cast down along every path: there must be turf at the expected height.
	var space := get_world_3d().direct_space_state
	var misses := 0
	var samples := 0
	for p: Array in hole["paths"]:
		var pts: Array = p[0]
		for i in pts.size() - 1:
			var a: Vector3 = pts[i]
			var b: Vector3 = pts[i + 1]
			var steps := maxi(1, int(a.distance_to(b) / 0.3))
			for s in steps:
				# Nudge off exact grid lines so rays never graze a triangle edge.
				var q := a.lerp(b, float(s) / steps) + Vector3(0.0137, 0, 0.0071)
				var q2 := Vector2(q.x, q.z)
				if _near_obstacle(q2) or q2.distance_to(cup2) < 0.3:
					continue
				var expect := course.field.sample(q2).y
				var ray := PhysicsRayQueryParameters3D.create(Vector3(q.x, expect + 2.5, q.z), Vector3(q.x, expect - 1.0, q.z))
				ray.exclude = [ball.get_rid()]
				var hit := space.intersect_ray(ray)
				samples += 1
				if hit.is_empty() or absf((hit["position"] as Vector3).y - expect) > 0.03:
					misses += 1
	ok = _check(misses == 0, "%s: turf under route (%d/%d samples ok)" % [label, samples - misses, samples]) and ok

	# Ball dropped on the tee settles there.
	ball.place(tee + Vector3.UP * 0.2)
	ball.strike(Vector3.ZERO)
	state = State.ROLLING
	for i in 120:
		await get_tree().physics_frame
	var y := ball.global_position.y
	ok = _check(absf(y - (tee.y + GolfBall.RADIUS)) < 0.01, "%s: ball rests on tee (build %d ms)" % [label, build_ms]) and ok
	ball.place(last_rest)
	state = State.READY
	return ok


func _near_obstacle(p: Vector2) -> bool:
	for o: Dictionary in hole["obstacles"]:
		var r := 0.95 if o["type"] == "windmill" else 0.75
		if p.distance_to(o["at"]) < r:
			return true
	for d: Dictionary in hole["decor"]:
		if d["type"] == "log_tunnel" and p.distance_to(d["at"]) < 1.0:
			return true
	return false


func _wait_rest(timeout: float, label: String) -> bool:
	var t := 0.0
	while state == State.ROLLING and t < timeout:
		await get_tree().physics_frame
		t += get_physics_process_delta_time()
	return _check(state == State.READY, "%s came to rest (at %s, speed %.3f, state %d, moving %s, frozen %s)" % [label, ball.global_position, ball.linear_velocity.length(), state, ball.moving, ball.freeze])


func _check(cond: bool, label: String) -> bool:
	print(("  ok   " if cond else "  FAIL ") + label)
	return cond
