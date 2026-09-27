class_name WorldBuilder
extends RefCounted
## Builds the whole explorable course: all 18 holes laid out in playing order
## around one big island, connected by sandy footpaths, with a volcano
## mountain in the middle, a clubhouse by the first tee and a lighthouse.
##
## Generating everything takes a while in GDScript, so the result is baked to
## BAKED_PATH (a normal Godot scene) and the game just loads that.
## Re-bake after changing hole data:  godot --headless -- --bake
##
## The world root carries metadata the game needs at runtime:
##   holes: Array of {number, title, par, tee, cup, forward, paths, obstacles}
##   spawn: Vector3, spawn_yaw: float, island_centre: Vector2, island_radius: float

const BAKED_PATH := "res://world/world.scn"
const RING_GAP := 5.0
const HOLE_VIEW_RANGE := 75.0

## Scripted nodes rebuild their own children in _ready, so baking skips them.
const SELF_BUILDING := ["Windmill", "Spinner", "SlidingBlock"]


static func load_or_build() -> Node3D:
	if ResourceLoader.exists(BAKED_PATH):
		var ps: PackedScene = load(BAKED_PATH)
		if ps:
			return ps.instantiate()
	push_warning("Baked world missing; generating (slow). Run with -- --bake to create it.")
	return build()


static func bake() -> Error:
	var root := build()
	_own(root, root)
	var ps := PackedScene.new()
	var err := ps.pack(root)
	if err != OK:
		return err
	DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path(BAKED_PATH.get_base_dir()))
	err = ResourceSaver.save(ps, BAKED_PATH, ResourceSaver.FLAG_COMPRESS)
	root.free()
	return err


static func _own(node: Node, owner_node: Node) -> void:
	for child in node.get_children():
		child.owner = owner_node
		var script: Script = child.get_script()
		if script and script.get_global_name() in SELF_BUILDING:
			continue
		_own(child, owner_node)


# --- layout -----------------------------------------------------------------

## Returns every hole's data transformed into world space, laid out
## counter-clockwise around a ring so each green leads to the next tee.
static func layout() -> Dictionary:
	var infos: Array = []
	var total := 0.0
	for n in range(1, Holes.COUNT + 1):
		var h := Holes.get_hole(n)
		var first: Array = h["paths"][0][0]
		var tee: Vector3 = first[0]
		var tee2 := Vector2(tee.x, tee.z)
		var cup: Vector2 = h["cup"]
		var f := (cup - tee2)
		if f.length() < 2.0:
			var last: Vector3 = first[first.size() - 1]
			f = Vector2(last.x, last.z) - tee2
		f = f.normalized()
		var side := Vector2(-f.y, f.x)
		var amin := INF
		var amax := -INF
		var lmin := INF
		var lmax := -INF
		for p2: Vector2 in _local_points(h):
			var rel := p2 - tee2
			amin = minf(amin, rel.dot(f))
			amax = maxf(amax, rel.dot(f))
			lmin = minf(lmin, rel.dot(side))
			lmax = maxf(lmax, rel.dot(side))
		var pivot := tee2 + f * (amin + amax) * 0.5 + side * (lmin + lmax) * 0.5
		var half := (amax - amin) * 0.5 + 1.0
		infos.append({"hole": h, "forward": f, "pivot": pivot, "half": half, "lateral": (lmax - lmin) * 0.5})
		total += half * 2.0 + RING_GAP
	var ring := total / (TAU * 0.93)
	var widest := 0.0
	for info in infos:
		widest = maxf(widest, info["lateral"])

	var holes: Array = []
	var angle := PI * 0.5
	for info in infos:
		angle += (float(info["half"]) + RING_GAP * 0.5) / ring
		var pos := Vector2(cos(angle), sin(angle)) * ring
		var tangent := Vector2(-sin(angle), cos(angle))
		var rot := tangent.angle() - (info["forward"] as Vector2).angle()
		holes.append(_transform_hole(info["hole"], info["pivot"], rot, pos))
		angle += (float(info["half"]) + RING_GAP * 0.5) / ring
	return {"holes": holes, "ring": ring, "radius": ring + widest + 12.0}


static func _local_points(h: Dictionary) -> Array:
	var pts: Array = []
	for p: Array in h["paths"]:
		for v: Vector3 in p[0]:
			pts.append(Vector2(v.x, v.z))
	for pad: Array in h["pads"]:
		var c: Vector3 = pad[0]
		var r: float = pad[1]
		for k in 8:
			pts.append(Vector2(c.x, c.z) + Vector2.from_angle(k * TAU / 8.0) * r)
	return pts


static func _transform_hole(h: Dictionary, pivot: Vector2, rot: float, pos: Vector2) -> Dictionary:
	var map := func(v: Vector2) -> Vector2: return pos + (v - pivot).rotated(rot)
	var out := h.duplicate(true)
	var paths: Array = []
	for p: Array in h["paths"]:
		var pts: Array = []
		for v: Vector3 in p[0]:
			var m: Vector2 = map.call(Vector2(v.x, v.z))
			pts.append(Vector3(m.x, v.y, m.y))
		paths.append([pts, p[1]])
	out["paths"] = paths
	var pads: Array = []
	for pad: Array in h["pads"]:
		var c: Vector3 = pad[0]
		var m: Vector2 = map.call(Vector2(c.x, c.z))
		pads.append([Vector3(m.x, c.y, m.y), pad[1]])
	out["pads"] = pads
	out["cup"] = map.call(h["cup"])
	var ponds: Array = []
	for pond: Array in h["ponds"]:
		ponds.append([map.call(pond[0]), pond[1]])
	out["ponds"] = ponds
	for key in ["obstacles", "decor"]:
		var list: Array = []
		for o: Dictionary in h[key]:
			var o2 := o.duplicate()
			o2["at"] = map.call(o["at"])
			o2["yaw"] = float(o.get("yaw", 0.0)) - rot
			if o.has("axis"):
				o2["axis"] = (o["axis"] as Vector2).rotated(rot)
			list.append(o2)
		out[key] = list
	return out


# --- building ---------------------------------------------------------------

static func build() -> Node3D:
	var lay := layout()
	var holes: Array = lay["holes"]
	var radius: float = lay["radius"]
	var ring: float = lay["ring"]
	var root := Node3D.new()
	root.name = "World"

	SkyAndSea.build(root, Vector3.ZERO, false, radius)
	var island := Island.new(Vector2.ZERO, radius)
	island.mountain_radius = ring * 0.42
	island.mountain_height = 7.5

	var meta_holes: Array = []
	var courses := Node3D.new()
	courses.name = "Courses"
	root.add_child(courses)
	for h: Dictionary in holes:
		var node := Node3D.new()
		node.name = "Hole%d" % h["number"]
		courses.add_child(node)
		var shape := LaneShape.new()
		for p: Array in h["paths"]:
			shape.add_path(p[0], p[1])
		var first: Array = h["paths"][0][0]
		var tee: Vector3 = first[0]
		shape.add_pad(tee, 0.85)
		for pad: Array in h["pads"]:
			shape.add_pad(pad[0], pad[1])
		var course := CourseBuilder.new()
		course.build(shape, h["cup"], node)
		island.add_course(CourseField.new(shape, 0.25, 3.0))
		for pond: Array in h["ponds"]:
			island.add_pond(pond[0], pond[1])
		Obstacles.build(node, h["obstacles"], course.field)

		var flag := Props.pin_flag()
		flag.position = course.cup
		node.add_child(flag)
		var nxt: Vector3 = first[1]
		var fwd := Vector2(nxt.x - tee.x, nxt.z - tee.z).normalized()
		var tee_mark := MeshInstance3D.new()
		var tee_st := MeshKit.begin()
		MeshKit.prism(tee_st, Vector3.ZERO, 0.16, 0.16, 0.004, 10, Color(0.24, 0.52, 0.2))
		tee_mark.mesh = MeshKit.finish(tee_st)
		tee_mark.material_override = MeshKit.facet_material()
		tee_mark.position = tee
		node.add_child(tee_mark)
		node.add_child(_tee_number(h["number"], tee))

		meta_holes.append({
			"number": h["number"], "title": h["title"], "par": h["par"],
			"tee": tee, "cup": course.cup, "forward": fwd,
			"paths": h["paths"], "obstacles": h["obstacles"], "decor": h["decor"], "ponds": h["ponds"],
		})

	# Footpaths: spawn -> 1 -> 2 ... -> 18 -> spawn, plus a trail up the mountain.
	var h1: Dictionary = meta_holes[0]
	var t1: Vector3 = h1["tee"]
	var f1: Vector2 = h1["forward"]
	var spawn2 := Vector2(t1.x, t1.z) - f1 * 4.0
	island.add_walkway(spawn2, Vector2(t1.x, t1.z) - f1 * 0.9)
	for i in meta_holes.size() - 1:
		var cup: Vector3 = meta_holes[i]["cup"]
		var nt: Vector3 = meta_holes[i + 1]["tee"]
		var nf: Vector2 = meta_holes[i + 1]["forward"]
		island.add_walkway(Vector2(cup.x, cup.z), Vector2(nt.x, nt.z) - nf * 0.9)
	var last_cup: Vector3 = meta_holes[meta_holes.size() - 1]["cup"]
	island.add_walkway(Vector2(last_cup.x, last_cup.z), spawn2)
	island.add_walkway(spawn2, spawn2.normalized() * (ring * 0.42 * 0.35))

	# Inland lakes between the mountain and the ring of holes.
	var rng := RandomNumberGenerator.new()
	rng.seed = 404
	var lakes := 0
	for attempt in 300:
		if lakes >= 6:
			break
		var a := rng.randf() * TAU
		var r := rng.randf_range(island.mountain_radius + 3.0, ring - 9.0)
		var c := Vector2.from_angle(a) * r
		var size := rng.randf_range(2.2, 4.0)
		if island.course_sample(c).x < size + 4.0 or island.walkway_distance(c) < size + 2.5:
			continue
		var clear := true
		for pond: Array in island.ponds:
			if (pond[0] as Vector2).distance_to(c) < size + float(pond[1]) + 4.0:
				clear = false
		if clear:
			island.add_pond(c, size)
			lakes += 1

	island.build(root)

	# Decor after the terrain exists so it can sit on the ground.
	for h: Dictionary in holes:
		var node: Node3D = courses.get_node("Hole%d" % h["number"])
		for d: Dictionary in h["decor"]:
			var dn := Props.decor(d)
			var at: Vector2 = d["at"]
			var y := island.course_sample(at).y if d["type"] == "log_tunnel" else island.height_at(at) - 0.05
			dn.position = Vector3(at.x, y, at.y)
			dn.rotation.y = d.get("yaw", 0.0)
			node.add_child(dn)
		var first: Array = h["paths"][0][0]
		_place_sign(node, island, h, first)
		_limit_view(node)

	var volcano := Props.volcano()
	volcano.scale = Vector3.ONE * 2.6
	volcano.position = Vector3(0, island.height_at(Vector2.ZERO) - 1.2, 0)
	root.add_child(volcano)

	var side := Vector2(-f1.y, f1.x)
	var club_at := spawn2 + side * 4.0 - f1 * 1.0
	if not island.is_land(club_at, 1.5):
		club_at = spawn2 - side * 4.0 - f1 * 1.0
	var club := Props.clubhouse()
	club.position = Vector3(club_at.x, island.height_at(club_at), club_at.y)
	var to_path := spawn2 - club_at
	club.rotation.y = atan2(to_path.x, to_path.y)
	root.add_child(club)

	for k in 64:
		var a := float(k) / 64.0 * TAU + 0.4
		var p := Vector2.from_angle(a) * radius * 0.86
		if island.is_land(p, 3.0) and island.height_at(p) < 1.0:
			var lh := Props.lighthouse()
			lh.scale = Vector3.ONE * 1.8
			lh.position = Vector3(p.x, island.height_at(p) - 0.1, p.y)
			root.add_child(lh)
			break

	root.set_meta("holes", meta_holes)
	root.set_meta("spawn", Vector3(spawn2.x, island.height_at(spawn2), spawn2.y))
	root.set_meta("spawn_yaw", atan2(-f1.x, -f1.y))
	root.set_meta("island_radius", radius)
	return root


static func _tee_number(number: int, tee: Vector3) -> Label3D:
	var label := Label3D.new()
	label.name = "TeeNumber"
	label.text = str(number)
	label.font_size = 96
	label.pixel_size = 0.006
	label.outline_size = 24
	label.modulate = Color(1.0, 0.97, 0.88)
	label.outline_modulate = Color(0.1, 0.35, 0.4)
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.no_depth_test = false
	label.shaded = false
	label.position = tee + Vector3.UP * 2.1
	label.visibility_range_end = 60.0
	return label


static func _place_sign(node: Node3D, island: Island, h: Dictionary, first: Array) -> void:
	var tee: Vector3 = first[0]
	var nxt: Vector3 = first[1]
	var t2 := Vector2(tee.x, tee.z)
	var dir := (Vector2(nxt.x, nxt.z) - t2).normalized()
	var side := Vector2(-dir.y, dir.x)
	for offset in [-side * 1.4 - dir * 0.3, side * 1.4 - dir * 0.3, -side * 1.9 + dir * 0.6, -dir * 1.6 - side * 0.9]:
		var p: Vector2 = t2 + offset
		if island.is_land(p, 0.35) and island.walkway_distance(p) > 0.9:
			var sign := Props.hole_sign(h["number"], h["title"], int(h["par"]))
			sign.position = Vector3(p.x, island.height_at(p), p.y)
			sign.rotation.y = atan2(-dir.x, -dir.y)
			node.add_child(sign)
			return


static func _limit_view(node: Node) -> void:
	for child in node.get_children():
		if child is GeometryInstance3D and child.visibility_range_end == 0.0:
			child.visibility_range_end = HOLE_VIEW_RANGE
			child.visibility_range_end_margin = 6.0
		_limit_view(child)
