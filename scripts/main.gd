extends Node3D
## Lagoon Links: explore one big island in first person and play its 18
## holes in any order.
##
## Walk mode:  left thumb = virtual joystick, right thumb = look around.
##             Walk up to a tee and tap "Play Hole N"; walk up to your ball and
##             tap "Putt".
## Putt mode:  touch the ball, pull back, let go. Drag elsewhere to orbit,
##             pinch to zoom. "Walk" returns to first person.
##
## Command-line helpers (after `--`):
##   --bake                       generate the island and save res://world/world.scn
##   --autotest                   run gameplay/geometry/touch checks and quit
##   --screenshot=<file.png>      render one frame, with one of:
##       --fp=N          first person, standing behind hole N's tee
##       --putt=N        putt mode on hole N's tee
##       --aerial        view of the whole island
##       --view=cx,cy,cz,tx,ty,tz

enum Mode { LOADING, WALK, PUTT, AIMING, ROLLING, HOLED }

const AIM_GRAB_RADIUS := 0.16     # fraction of screen height
const FULL_PULL := 0.32           # drag length (fraction of screen height) for max power
const STICK_ZONE := 0.42          # left fraction of the screen that spawns the joystick
const TEE_REACH := 2.8
const BALL_REACH := 2.4

var mode := Mode.LOADING
var holes: Array = []
var scores: Array = []
var active := 0                   # hole being played, 0 = just exploring
var strokes := 0
var last_rest := Vector3.ZERO

var world: Node3D
var ball: GolfBall
var walker: Walker
var rig: CameraRig
var arrow: AimArrow
var hud: Hud
var beacon: Node3D

var _primary_action := ""
var _primary_arg := 0
var _secondary_action := ""
var _touches := {}
var _stick_index := -1
var _stick_origin := Vector2.ZERO
var _aim_index := -1
var _aim_start := Vector2.ZERO
var _aim_power := 0.0
var _aim_dir := Vector3.FORWARD
var _args := {}


func _ready() -> void:
	_parse_args()
	if _args.has("bake"):
		var t0 := Time.get_ticks_msec()
		var err := WorldBuilder.bake()
		print("Baked world to %s in %d ms (error %d)" % [WorldBuilder.BAKED_PATH, Time.get_ticks_msec() - t0, err])
		get_tree().quit(0 if err == OK else 1)
		return

	scores.resize(Holes.COUNT)
	scores.fill(0)
	hud = Hud.new()
	add_child(hud)
	hud.show_loading("Lagoon Links")
	await get_tree().process_frame
	await get_tree().process_frame

	var t_load := Time.get_ticks_msec()
	world = WorldBuilder.load_or_build()
	add_child(world)
	print("World ready in %d ms" % (Time.get_ticks_msec() - t_load))
	holes = world.get_meta("holes")

	ball = GolfBall.new()
	add_child(ball)
	ball.visible = false
	ball.came_to_rest.connect(_on_ball_rest)
	arrow = AimArrow.new()
	add_child(arrow)
	rig = CameraRig.new()
	add_child(rig)
	rig.target = ball
	walker = Walker.new()
	add_child(walker)
	walker.exclude = [ball.get_rid()]
	beacon = _make_beacon()
	add_child(beacon)

	hud.minimap.setup(holes, world.get_meta("island_radius"))
	hud.primary_pressed.connect(_on_primary)
	hud.secondary_pressed.connect(_on_secondary)
	hud.banner_tapped.connect(_on_banner_tapped)
	hud.hole_chosen.connect(_travel_to_hole)
	hud.play_again.connect(_new_round)

	await get_tree().physics_frame
	_enter_walk_at(world.get_meta("spawn"), world.get_meta("spawn_yaw"))
	hud.set_hint("Left thumb: walk  ·  Right thumb: look around\nFollow the path to the Hole 1 tee")
	hud.hide_loading()

	if _args.has("screenshot"):
		_take_screenshot()
	elif _args.has("autotest"):
		_autotest()


# --- modes ------------------------------------------------------------------

func _enter_walk_at(pos: Vector3, yaw: float) -> void:
	mode = Mode.WALK
	_clear_touches()
	arrow.visible = false
	hud.set_power(0.0, false)
	walker.place(pos, yaw)
	walker.camera.current = true


func start_hole(n: int) -> void:
	var h: Dictionary = holes[n - 1]
	active = n
	strokes = 0
	ball.visible = true
	last_rest = (h["tee"] as Vector3) + Vector3.UP * (GolfBall.RADIUS + 0.005)
	ball.place(last_rest)
	hud.set_hint("")
	enter_putt()
	var t: Vector3 = h["tee"]
	var f: Vector2 = h["forward"]
	rig.face_toward(t + Vector3(f.x, 0, f.y))
	rig.snap()


func enter_putt() -> void:
	mode = Mode.PUTT
	_clear_touches()
	rig.yaw = walker.yaw
	rig.pitch = deg_to_rad(24.0)
	rig.distance = 2.6
	rig.snap()
	rig.camera.current = true


func exit_putt() -> void:
	var back := Basis(Vector3.UP, rig.yaw) * Vector3(0, 0, 1.1)
	_enter_walk_at(ball.global_position + back, rig.yaw)


# --- per-frame ----------------------------------------------------------------

func _process(_delta: float) -> void:
	if mode == Mode.LOADING or walker == null:
		return
	_update_context()
	var target_hole := active if active != 0 else _next_unplayed()
	var ball_pos: Variant = ball.global_position if active != 0 else null
	var viewer := walker.global_position if mode == Mode.WALK else rig.global_position
	hud.minimap.update_state(viewer, walker.yaw if mode == Mode.WALK else rig.yaw, ball_pos, target_hole)
	# Beacon floats over your ball, or over the next tee when not playing.
	if active != 0:
		beacon.global_position = ball.global_position + Vector3.UP * 1.1
		beacon.visible = mode == Mode.WALK
	elif target_hole != 0:
		beacon.global_position = (holes[target_hole - 1]["tee"] as Vector3) + Vector3.UP * 3.2
		beacon.visible = true
	else:
		beacon.visible = false


func _update_context() -> void:
	var primary := ""
	var secondary := ""
	_primary_action = ""
	_secondary_action = ""
	match mode:
		Mode.WALK:
			var p := walker.global_position
			var near_tee := _hole_near(p)
			if active != 0 and _flat_dist(p, ball.global_position) < BALL_REACH:
				_primary_action = "putt"
				primary = "Putt"
			elif near_tee != 0 and near_tee != active:
				_primary_action = "play"
				_primary_arg = near_tee
				primary = "Play Hole %d" % near_tee
			if active != 0 and _flat_dist(p, ball.global_position) > 8.0:
				_secondary_action = "goto_ball"
				secondary = "Go to ball"
		Mode.PUTT:
			_primary_action = "walk"
			primary = "Walk"
			_secondary_action = "reset"
			secondary = "Reset ball"
	hud.set_actions(primary, secondary)
	if active != 0:
		var h: Dictionary = holes[active - 1]
		hud.set_info("Hole %d  ·  %s  ·  Par %d" % [active, h["title"], h["par"]], "Strokes: %d" % strokes)
	else:
		var n := _next_unplayed()
		hud.set_info("Exploring Lagoon Links", "Next: Hole %d" % n if n != 0 else "Round complete!")


func _physics_process(_delta: float) -> void:
	if mode != Mode.ROLLING:
		return
	var h: Dictionary = holes[active - 1]
	var cup: Vector3 = h["cup"]
	var p := ball.global_position
	if Vector2(p.x - cup.x, p.z - cup.z).length() < CourseBuilder.CUP_RADIUS and p.y < cup.y - GolfBall.RADIUS * 1.2:
		_holed()
	elif p.y < SkyAndSea.WATER_LEVEL + 0.2 or p.y < last_rest.y - 4.0:
		hud.toast("Splash!  +1")
		strokes += 1
		_return_ball()


# --- actions ------------------------------------------------------------------

func _on_primary() -> void:
	match _primary_action:
		"putt":
			enter_putt()
		"play":
			start_hole(_primary_arg)
		"walk":
			if mode == Mode.PUTT:
				exit_putt()


func _on_secondary() -> void:
	match _secondary_action:
		"goto_ball":
			var to_ball := ball.global_position - walker.global_position
			to_ball.y = 0.0
			var dir := to_ball.normalized()
			_enter_walk_at(ball.global_position - dir * 1.4, atan2(-dir.x, -dir.z))
		"reset":
			_return_ball()


func _travel_to_hole(n: int) -> void:
	hud.hide_scorecard()
	var h: Dictionary = holes[n - 1]
	var t: Vector3 = h["tee"]
	var f: Vector2 = h["forward"]
	_enter_walk_at(t - Vector3(f.x, 0, f.y) * 1.8, atan2(-f.x, -f.y))


func _new_round() -> void:
	hud.hide_scorecard()
	scores.fill(0)
	active = 0
	ball.visible = false
	_update_totals()
	_enter_walk_at(world.get_meta("spawn"), world.get_meta("spawn_yaw"))


func shoot(direction: Vector3, power: float) -> void:
	strokes += 1
	mode = Mode.ROLLING
	var speed := lerpf(0.25, GolfBall.MAX_SPEED, pow(power, 1.35))
	ball.strike(Vector3(direction.x, 0, direction.z).normalized() * speed)


func _on_ball_rest() -> void:
	if mode != Mode.ROLLING:
		return
	last_rest = ball.global_position
	mode = Mode.PUTT


func _return_ball() -> void:
	arrow.visible = false
	hud.set_power(0.0, false)
	ball.place(last_rest)
	rig.snap()
	mode = Mode.PUTT


func _holed() -> void:
	mode = Mode.HOLED
	scores[active - 1] = strokes
	_update_totals()
	var par := int(holes[active - 1]["par"])
	var names := {-3: "Albatross!", -2: "Eagle!", -1: "Birdie!", 0: "Par", 1: "Bogey", 2: "Double Bogey"}
	var title: String = "Hole in One!" if strokes == 1 else names.get(strokes - par, "+%d" % (strokes - par))
	hud.show_banner(title, "%d stroke%s  ·  tap to continue" % [strokes, "" if strokes == 1 else "s"])


func _on_banner_tapped() -> void:
	if mode != Mode.HOLED:
		return
	hud.hide_banner()
	var finished := active
	active = 0
	ball.visible = false
	var cup: Vector3 = holes[finished - 1]["cup"]
	var nxt := _next_unplayed()
	var yaw := walker.yaw
	if nxt != 0:
		var d: Vector3 = (holes[nxt - 1]["tee"] as Vector3) - cup
		yaw = atan2(-d.x, -d.z)
	_enter_walk_at(cup + Basis(Vector3.UP, yaw) * Vector3(0.6, 0, 0.8), yaw)
	if nxt == 0:
		hud.show_scorecard(scores, true)


func _update_totals() -> void:
	var played := 0
	var par_played := 0
	for i in Holes.COUNT:
		if scores[i] > 0:
			played += scores[i]
			par_played += int(holes[i]["par"])
	hud.set_total(played, played - par_played)


func _next_unplayed() -> int:
	for i in Holes.COUNT:
		if scores[i] == 0:
			return i + 1
	return 0


func _hole_near(p: Vector3) -> int:
	for h: Dictionary in holes:
		var t: Vector3 = h["tee"]
		if _flat_dist(p, t) < TEE_REACH and absf(p.y - t.y) < 1.6:
			return h["number"]
	return 0


func _flat_dist(a: Vector3, b: Vector3) -> float:
	return Vector2(a.x - b.x, a.z - b.z).length()


func _make_beacon() -> Node3D:
	var node := Node3D.new()
	node.set_script(load("res://scripts/world/spin_bob.gd"))
	var st := MeshKit.begin()
	MeshKit.prism(st, Vector3(0, -0.35, 0), 0.001, 0.28, 0.35, 4, Color(1.0, 0.8, 0.25), true)
	MeshKit.prism(st, Vector3(0, 0.0, 0), 0.12, 0.12, 0.35, 4, Color(1.0, 0.8, 0.25), true)
	var mi := MeshInstance3D.new()
	mi.mesh = MeshKit.finish(st)
	var mat := StandardMaterial3D.new()
	mat.vertex_color_use_as_albedo = true
	mat.emission_enabled = true
	mat.emission = Color(1.0, 0.6, 0.15)
	mat.emission_energy_multiplier = 0.8
	mi.material_override = mat
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	node.add_child(mi)
	return node


# --- input ------------------------------------------------------------------

func _clear_touches() -> void:
	_touches.clear()
	_stick_index = -1
	_aim_index = -1
	if walker:
		walker.move_input = Vector2.ZERO
	if hud:
		hud.joystick.hide_stick()


func _unhandled_input(event: InputEvent) -> void:
	if mode == Mode.LOADING or mode == Mode.HOLED:
		return
	var vp := get_viewport().get_visible_rect().size
	if event is InputEventScreenTouch:
		if event.pressed:
			_touches[event.index] = event.position
			_on_press(event.index, event.position, vp)
		else:
			_on_release(event.index)
			_touches.erase(event.index)
	elif event is InputEventScreenDrag:
		var prev: Vector2 = _touches.get(event.index, event.position)
		_touches[event.index] = event.position
		_on_drag(event, prev, vp)
	elif event is InputEventMouseButton and event.pressed and mode != Mode.WALK:
		if event.button_index == MOUSE_BUTTON_WHEEL_UP:
			rig.zoom(0.9)
		elif event.button_index == MOUSE_BUTTON_WHEEL_DOWN:
			rig.zoom(1.1)


func _on_press(index: int, pos: Vector2, vp: Vector2) -> void:
	if mode == Mode.WALK:
		if pos.x < vp.x * STICK_ZONE and _stick_index == -1:
			_stick_index = index
			_stick_origin = pos
			hud.joystick.show_at(pos, pos)
	elif mode == Mode.PUTT and _touches.size() == 1 and _near_ball(pos, vp.y):
		mode = Mode.AIMING
		_aim_index = index
		_aim_start = pos
		_aim_power = 0.0


func _on_release(index: int) -> void:
	if index == _stick_index:
		_stick_index = -1
		walker.move_input = Vector2.ZERO
		hud.joystick.hide_stick()
	elif mode == Mode.AIMING and index == _aim_index:
		_release_shot()


func _on_drag(event: InputEventScreenDrag, prev: Vector2, vp: Vector2) -> void:
	if mode == Mode.WALK:
		if event.index == _stick_index:
			var v := (event.position - _stick_origin) / JoystickView.RADIUS
			if v.length() > 1.0:
				v = v.normalized()
			walker.move_input = Vector2(v.x, -v.y)
			hud.joystick.show_at(_stick_origin, _stick_origin + v * JoystickView.RADIUS)
		else:
			walker.look(event.relative, vp.y)
		return
	if mode == Mode.AIMING and event.index == _aim_index:
		_update_aim(event.position, vp.y)
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
		rig.orbit(event.relative, vp.y)


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
		mode = Mode.PUTT
		return
	shoot(_aim_dir, _aim_power)


# --- tooling ------------------------------------------------------------------

func _parse_args() -> void:
	for a in OS.get_cmdline_user_args():
		var parts := a.trim_prefix("--").split("=", true, 1)
		_args[parts[0]] = parts[1] if parts.size() > 1 else ""


func _take_screenshot() -> void:
	hud.set_hint("")
	if _args.has("fp"):
		_travel_to_hole(int(_args["fp"]))
		walker.pitch = -0.12
		walker.look(Vector2.ZERO, 1.0)
	elif _args.has("putt"):
		_travel_to_hole(int(_args["putt"]))
		start_hole(int(_args["putt"]))
	elif _args.has("aerial"):
		var r: float = world.get_meta("island_radius")
		rig.set_process(false)
		rig.camera.global_position = Vector3(-r * 0.55, r * 1.05, r * 1.05)
		rig.camera.look_at(Vector3(0, 0, -r * 0.05))
		rig.camera.current = true
	elif _args.has("view"):
		var v: PackedFloat64Array = (_args["view"] as String).split_floats(",")
		rig.set_process(false)
		rig.camera.global_position = Vector3(v[0], v[1], v[2])
		rig.camera.look_at(Vector3(v[3], v[4], v[5]))
		rig.camera.current = true
	if _args.has("card"):
		hud.show_scorecard(scores, false)
	for i in 90:
		await get_tree().process_frame
	get_viewport().get_texture().get_image().save_png(_args["screenshot"])
	print("Saved screenshot to ", _args["screenshot"])
	get_tree().quit()


func _autotest() -> void:
	var ok := true
	for h: Dictionary in holes:
		ok = _check_hole(h) and ok
	ok = await _walk_test() and ok
	ok = await _flow_test() and ok
	print("AUTOTEST ", "PASS" if ok else "FAIL")
	get_tree().quit(0 if ok else 1)


func _ray_down(p: Vector3, mask := 1) -> Dictionary:
	var q := PhysicsRayQueryParameters3D.create(p + Vector3.UP * 2.5, p + Vector3.DOWN * 1.0, mask, [ball.get_rid()])
	return get_world_3d().direct_space_state.intersect_ray(q)


func _check_hole(h: Dictionary) -> bool:
	var ok := true
	var label := "hole %d %s" % [h["number"], h["title"]]
	var tee: Vector3 = h["tee"]
	var cup: Vector3 = h["cup"]
	var th := _ray_down(tee + Vector3(0.013, 0, 0.007))
	ok = _check(not th.is_empty() and absf((th["position"] as Vector3).y - tee.y) < 0.02, label + ": turf on the tee") and ok
	var ch := _ray_down(cup)
	ok = _check(not ch.is_empty() and (ch["position"] as Vector3).y < cup.y - 0.1, label + ": cup is a real hole") and ok
	var misses := 0
	var samples := 0
	for p: Array in h["paths"]:
		var pts: Array = p[0]
		for i in pts.size() - 1:
			var a: Vector3 = pts[i]
			var b: Vector3 = pts[i + 1]
			var steps := maxi(1, int(a.distance_to(b) / 0.3))
			for s in steps:
				var q := a.lerp(b, float(s) / steps) + Vector3(0.0137, 0, 0.0071)
				var q2 := Vector2(q.x, q.z)
				if _near_obstacle(h, q2) or q2.distance_to(Vector2(cup.x, cup.z)) < 0.3:
					continue
				samples += 1
				var hit := _ray_down(q)
				# Path heights are exact at control points and eased between, so
				# only require turf within a few cm of the eased height range.
				if hit.is_empty() or (hit["collider"] as Node).name != "Turf" or (hit["position"] as Vector3).y > maxf(a.y, b.y) + 0.03 or (hit["position"] as Vector3).y < minf(a.y, b.y) - 0.03:
					misses += 1
	ok = _check(misses == 0, "%s: turf under route (%d/%d)" % [label, samples - misses, samples]) and ok
	return ok


func _near_obstacle(h: Dictionary, p: Vector2) -> bool:
	for o: Dictionary in h["obstacles"]:
		if p.distance_to(o["at"]) < (0.95 if o["type"] == "windmill" else 0.75):
			return true
	for d: Dictionary in h["decor"]:
		if d["type"] == "log_tunnel" and p.distance_to(d["at"]) < 1.0:
			return true
	return false


func _walk_test() -> bool:
	var ok := true
	var spawn: Vector3 = world.get_meta("spawn")
	_enter_walk_at(spawn, world.get_meta("spawn_yaw"))
	await get_tree().physics_frame
	var start := walker.global_position
	walker.move_input = Vector2(0, 1)
	for i in 120:
		await get_tree().physics_frame
	walker.move_input = Vector2.ZERO
	var moved := _flat_dist(start, walker.global_position)
	ok = _check(moved > 2.0, "walk: joystick forward moves the player (%.1f m)" % moved) and ok
	var g: Variant = walker.ground_at(walker.global_position)
	ok = _check(g != null and absf(walker.global_position.y - float(g)) < 0.2, "walk: player follows the ground") and ok

	# Walk straight at the sea: the player must stop on the beach.
	var r: float = world.get_meta("island_radius")
	var dir := Vector3(1, 0, 0.3).normalized()
	var p := Vector3.ZERO
	for k in 400:
		p = dir * (r * 0.5 + k * 0.25)
		var gh: Variant = walker.ground_at(p + Vector3.UP * 20.0)
		if gh != null and float(gh) < SkyAndSea.WATER_LEVEL + 0.2:
			break
	var shore := p - dir * 4.0
	_enter_walk_at(shore + Vector3.UP * 5.0, atan2(-dir.x, -dir.z))
	await get_tree().physics_frame
	walker.move_input = Vector2(0, 1)
	for i in 400:
		await get_tree().physics_frame
	walker.move_input = Vector2.ZERO
	var gy: Variant = walker.ground_at(walker.global_position)
	ok = _check(gy != null and float(gy) > SkyAndSea.WATER_LEVEL, "walk: can't walk into the sea") and ok

	# Touch joystick on the left half + look drag on the right half.
	_enter_walk_at(spawn, world.get_meta("spawn_yaw"))
	var vp := get_viewport().get_visible_rect().size
	var yaw0 := walker.yaw
	_touch(1, Vector2(vp.x * 0.8, vp.y * 0.5), true)
	_drag(1, Vector2(vp.x * 0.8 + 60, vp.y * 0.5), Vector2(60, 0))
	_touch(1, Vector2(vp.x * 0.8 + 60, vp.y * 0.5), false)
	ok = _check(not is_equal_approx(walker.yaw, yaw0), "touch: right-side drag looks around") and ok
	var before := walker.global_position
	var o := Vector2(vp.x * 0.15, vp.y * 0.7)
	_touch(0, o, true)
	_drag(0, o + Vector2(0, -80), Vector2(0, -80))
	for i in 60:
		await get_tree().physics_frame
	_touch(0, o + Vector2(0, -80), false)
	ok = _check(_flat_dist(before, walker.global_position) > 1.0, "touch: left-side joystick walks") and ok
	ok = _check(walker.move_input == Vector2.ZERO, "touch: releasing the joystick stops") and ok
	return ok


func _flow_test() -> bool:
	var ok := true
	_travel_to_hole(1)
	await get_tree().process_frame
	ok = _check(_primary_action == "play" and _primary_arg == 1, "flow: standing at tee 1 offers 'Play Hole 1'") and ok
	_on_primary()
	ok = _check(mode == Mode.PUTT and active == 1 and ball.visible, "flow: playing starts putt mode at the tee") and ok

	# Touch putt: press on the ball, pull back, release.
	await get_tree().process_frame
	await get_tree().process_frame
	var vh := get_viewport().get_visible_rect().size.y
	var start := rig.camera.unproject_position(ball.global_position)
	var tee: Vector3 = holes[0]["tee"]
	_touch(0, start, true)
	ok = _check(mode == Mode.AIMING, "touch: pressing the ball starts aiming") and ok
	var pos := start
	for i in 6:
		pos += Vector2(0, vh * 0.02)
		_drag(0, pos, Vector2(0, vh * 0.02))
	ok = _check(arrow.visible and _aim_power > 0.2, "touch: pulling back shows the aim arrow") and ok
	_touch(0, pos, false)
	ok = _check(mode == Mode.ROLLING and strokes == 1, "touch: releasing takes the shot") and ok
	ok = await _wait_rest(10.0, "touch putt") and ok
	ok = _check(ball.global_position.distance_to(tee) > 0.5, "touch: ball rolled down the lane") and ok

	await get_tree().process_frame
	ok = _check(_primary_action == "walk", "flow: putt mode offers 'Walk'") and ok
	_on_primary()
	ok = _check(mode == Mode.WALK and walker.camera.current, "flow: 'Walk' returns to first person") and ok
	await get_tree().process_frame
	ok = _check(_primary_action == "putt", "flow: next to the ball offers 'Putt'") and ok
	_on_primary()
	ok = _check(mode == Mode.PUTT, "flow: 'Putt' re-enters putt mode") and ok

	# Sink it from close range.
	var cup: Vector3 = holes[0]["cup"]
	ball.place(cup + Vector3(-0.5, GolfBall.RADIUS + 0.005, 0))
	shoot(Vector3.RIGHT, 0.22)
	for i in 600:
		await get_tree().physics_frame
		if mode == Mode.HOLED:
			break
	ok = _check(mode == Mode.HOLED and scores[0] == 2, "flow: holing out records the score") and ok
	_on_banner_tapped()
	ok = _check(mode == Mode.WALK and active == 0, "flow: continuing returns to exploring") and ok
	ok = _check(_next_unplayed() == 2, "flow: next target is hole 2") and ok

	# Water hazard on hole 1's pond.
	start_hole(1)
	var pond: Vector2 = holes[0]["ponds"][0][0]
	ball.place(Vector3(pond.x, 1.0, pond.y))
	ball.strike(Vector3.ZERO)
	mode = Mode.ROLLING
	for i in 400:
		await get_tree().physics_frame
		if mode == Mode.PUTT:
			break
	ok = _check(mode == Mode.PUTT and strokes == 1, "flow: water resets the ball with a penalty") and ok
	return ok


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


func _wait_rest(timeout: float, label: String) -> bool:
	var t := 0.0
	while mode == Mode.ROLLING and t < timeout:
		await get_tree().physics_frame
		t += get_physics_process_delta_time()
	return _check(mode == Mode.PUTT, "%s came to rest (%s)" % [label, ball.global_position])


func _check(cond: bool, label: String) -> bool:
	print(("  ok   " if cond else "  FAIL ") + label)
	return cond
