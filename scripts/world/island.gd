class_name Island
extends RefCounted
## The big low-poly island that all 18 courses sit on: terrain (walkable, with
## collision on its own layer so the ball ignores it), sandy footpaths between
## holes, a central volcano mountain, and scattered palms, bushes, rocks,
## flowers and grass. Small scenery uses visibility ranges so a phone only
## draws what is near the player.

const STEP := 0.8
const TERRAIN_LAYER := 2
const PATH_WIDTH := 1.3
const GRASS_CHUNK := 12.0

var fields: Array = []          # CourseField per hole (coarse, wide margin)
var centre := Vector2.ZERO
var radius := 60.0
var mountain_radius := 18.0
var mountain_height := 7.0
var ponds: Array = []           # [Vector2 centre, float radius]
var walkways: Array = []        # [Vector2 a, Vector2 b]
var _noise := FastNoiseLite.new()
var _detail := FastNoiseLite.new()
var _tint := FastNoiseLite.new()


func _init(island_centre: Vector2, island_radius: float) -> void:
	centre = island_centre
	radius = island_radius
	_noise.seed = 11
	_noise.frequency = 0.02
	_detail.seed = 3
	_detail.frequency = 0.22
	_tint.frequency = 0.12


func add_course(field: CourseField) -> void:
	fields.append(field)


func add_pond(c: Vector2, r: float) -> void:
	ponds.append([c, r])


func add_walkway(a: Vector2, b: Vector2) -> void:
	walkways.append([a, b])


## Nearest course: Vector2(signed distance to its lane edge, its floor height).
func course_sample(p: Vector2) -> Vector2:
	var best := Vector2(CourseField.FAR, 0.0)
	for f: CourseField in fields:
		var s := f.sample(p)
		if s.x < best.x:
			best = s
	return best


func walkway_distance(p: Vector2) -> float:
	var best := INF
	for w: Array in walkways:
		best = minf(best, Geometry2D.get_closest_point_to_segment(p, w[0], w[1]).distance_to(p))
	return best


func natural_height(p: Vector2) -> float:
	var r := p.distance_to(centre) / (radius * (1.0 + _noise.get_noise_2dv(p) * 0.18))
	var h := lerpf(0.25, -2.6, smoothstep(0.86, 1.06, r))
	var inland := 1.0 - smoothstep(0.8, 0.95, r)
	h += _detail.get_noise_2dv(p) * 0.25 * inland
	# Rolling hills across the interior.
	h += maxf(_noise.get_noise_2dv(p * 2.2 + Vector2(90, -40)), 0.0) * 2.4 * inland
	# Central volcano mountain, visible from everywhere as a landmark.
	var m := 1.0 - clampf(p.distance_to(centre) / mountain_radius, 0.0, 1.0)
	h += pow(m, 1.6) * mountain_height * (1.0 + _detail.get_noise_2dv(p * 0.5) * 0.15)
	for pond in ponds:
		var pc: Vector2 = pond[0]
		var pr: float = pond[1]
		var k := 1.0 - smoothstep(pr * 0.55, pr * 1.25, p.distance_to(pc))
		h = lerpf(h, -1.2, k)
	return h


## Terrain height, pulled down to a skirt just below any nearby course.
func height_at(p: Vector2) -> float:
	var h := natural_height(p)
	var s := course_sample(p)
	var near := 1.0 - smoothstep(CourseBuilder.RAIL_WIDTH, CourseBuilder.RAIL_WIDTH + 1.4, s.x)
	return lerpf(h, s.y - 0.3, near)


func is_land(p: Vector2, clearance: float) -> bool:
	return height_at(p) > SkyAndSea.WATER_LEVEL + 0.15 and course_sample(p).x > CourseBuilder.RAIL_WIDTH + clearance


func build(parent: Node3D) -> void:
	var node := Node3D.new()
	node.name = "Island"
	parent.add_child(node)
	_terrain(node)
	_scatter(node)


func _terrain(parent: Node3D) -> void:
	var extent := radius * 1.15
	var n := int(extent * 2.0 / STEP) + 1
	var jitter := RandomNumberGenerator.new()
	jitter.seed = 99
	var grid: Array = []
	for j in n:
		var row: Array = []
		for i in n:
			var p := centre + Vector2(i * STEP - extent, j * STEP - extent)
			if i > 0 and j > 0 and i < n - 1 and j < n - 1:
				p += Vector2(jitter.randf_range(-0.28, 0.28), jitter.randf_range(-0.28, 0.28)) * STEP
			row.append(Vector3(p.x, height_at(p), p.y))
		grid.append(row)
	var st := MeshKit.begin()
	for j in n - 1:
		for i in n - 1:
			var a: Vector3 = grid[j][i]
			var b: Vector3 = grid[j][i + 1]
			var c: Vector3 = grid[j + 1][i + 1]
			var d: Vector3 = grid[j + 1][i]
			# Skip deep sea floor: the ocean plane covers it.
			if maxf(maxf(a.y, b.y), maxf(c.y, d.y)) < -2.3:
				continue
			var tris := [[a, b, c], [a, c, d]] if (i + j) % 2 == 0 else [[a, b, d], [b, c, d]]
			for t in tris:
				var p0: Vector3 = t[0]
				var p1: Vector3 = t[1]
				var p2: Vector3 = t[2]
				MeshKit.face(st, p0, p1, p2, _ground_color((p0 + p1 + p2) / 3.0, jitter), Vector3.UP)
	var mesh := MeshKit.finish(st)
	var body := StaticBody3D.new()
	body.name = "Terrain"
	body.collision_layer = 1 << (TERRAIN_LAYER - 1)
	body.collision_mask = 0
	parent.add_child(body)
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = MeshKit.facet_material(0.95)
	body.add_child(mi)
	var cs := CollisionShape3D.new()
	cs.shape = mesh.create_trimesh_shape()
	body.add_child(cs)


func _ground_color(p: Vector3, rng: RandomNumberGenerator) -> Color:
	var water := SkyAndSea.WATER_LEVEL
	var sand := Color(0.96, 0.86, 0.6)
	var wet := Color(0.78, 0.68, 0.46)
	var grass := Color(0.42, 0.72, 0.26).lerp(Color(0.3, 0.6, 0.22), _tint.get_noise_2d(p.x, p.z) * 0.5 + 0.5)
	var rock := Color(0.5, 0.44, 0.4)
	var col: Color
	if p.y < water - 0.05:
		col = wet.darkened(clampf((water - p.y) * 0.25, 0.0, 0.4))
	elif p.y < water + 0.28:
		col = sand
	else:
		col = sand.lerp(grass, smoothstep(water + 0.28, water + 0.45, p.y))
	# Bare rock up the mountain.
	col = col.lerp(rock, smoothstep(2.2, 4.0, p.y))
	var p2 := Vector2(p.x, p.z)
	var wd := walkway_distance(p2)
	if wd < PATH_WIDTH * 0.5 and p.y > water + 0.1:
		col = Color(0.88, 0.76, 0.54).darkened(0.05 * rng.randf())
	var d := course_sample(p2).x
	col = col.darkened(0.25 * (1.0 - smoothstep(CourseBuilder.RAIL_WIDTH, CourseBuilder.RAIL_WIDTH + 0.9, d)))
	return col.lightened(rng.randf() * 0.03)


func _place(parent: Node3D, node: Node3D, p: Vector2, sink: float, view_range: float) -> void:
	node.position = Vector3(p.x, height_at(p) - sink, p.y)
	parent.add_child(node)
	if view_range > 0.0:
		for child in node.get_children():
			if child is GeometryInstance3D:
				child.visibility_range_end = view_range
				child.visibility_range_end_margin = 4.0


func _random_point(rng: RandomNumberGenerator, scale := 1.0) -> Vector2:
	var a := rng.randf() * TAU
	return centre + Vector2(cos(a), sin(a)) * radius * scale * sqrt(rng.randf())


func _clear_of_paths(p: Vector2, margin: float) -> bool:
	return walkway_distance(p) > PATH_WIDTH * 0.5 + margin


func _scatter(parent: Node3D) -> void:
	var rng := RandomNumberGenerator.new()
	rng.seed = 1234

	# Grass and flowers in chunks so distant patches can be culled.
	var chunks := {}
	var tries := int(PI * radius * radius * 2.5)
	for i in tries:
		var p := _random_point(rng)
		var h := height_at(p)
		if h < SkyAndSea.WATER_LEVEL + 0.35 or h > 3.0 or course_sample(p).x < CourseBuilder.RAIL_WIDTH + 0.12 or not _clear_of_paths(p, 0.0):
			continue
		var key := Vector2i(floori(p.x / GRASS_CHUNK), floori(p.y / GRASS_CHUNK))
		if not chunks.has(key):
			chunks[key] = [[], [], [], []]
		var xf := Transform3D(Basis(Vector3.UP, rng.randf() * TAU).scaled(Vector3.ONE * rng.randf_range(0.8, 1.5)), Vector3(p.x, h - 0.01, p.y))
		var kind := 0 if rng.randf() > 0.08 else 1 + rng.randi() % 3
		chunks[key][kind].append(xf)
	var tuft := Props.grass_tuft_mesh()
	var colors := [Color(1.0, 0.45, 0.62), Color(1.0, 0.85, 0.3), Color(0.98, 0.98, 0.95)]
	var flower_meshes := [Props.flower_patch_mesh(colors[0]), Props.flower_patch_mesh(colors[1]), Props.flower_patch_mesh(colors[2])]
	var grass_node := Node3D.new()
	grass_node.name = "Grass"
	parent.add_child(grass_node)
	for key: Vector2i in chunks:
		var lists: Array = chunks[key]
		_multimesh(grass_node, tuft, lists[0], Props.foliage_mat(), 24.0)
		for k in 3:
			_multimesh(grass_node, flower_meshes[k], lists[k + 1], Props.facet_mat(), 24.0)

	# Palms everywhere except on courses and paths.
	var props := Node3D.new()
	props.name = "Props"
	parent.add_child(props)
	var palms: Array = []
	var attempts := 0
	var target_palms := int(radius * 2.6)
	while palms.size() < target_palms and attempts < 20000:
		attempts += 1
		var p := _random_point(rng, 0.97)
		if course_sample(p).x < 1.8 or not is_land(p, 0.8) or height_at(p) > 2.5 or not _clear_of_paths(p, 0.9):
			continue
		if not _spaced(palms, p, 3.2):
			continue
		palms.append(p)
		_place(props, Props.palm(rng.randi(), rng.randf_range(2.8, 4.2)), p, 0.05, 90.0)

	# Bushes and rocks: planted borders around courses and along paths.
	var placed: Array = []
	attempts = 0
	var target := int(radius * 5.0)
	while placed.size() < target and attempts < 40000:
		attempts += 1
		var p := _random_point(rng)
		var d := course_sample(p).x
		var near_path := walkway_distance(p) < 2.5
		if (d > 3.5 and not near_path and rng.randf() < 0.8) or not is_land(p, 0.45) or not _clear_of_paths(p, 0.3):
			continue
		if not _spaced(placed, p, 1.2):
			continue
		placed.append(p)
		if rng.randf() < 0.62:
			_place(props, Props.bush(rng.randi(), rng.randf_range(0.3, 0.55), rng.randf() < 0.6), p, 0.02, 45.0)
		else:
			_place(props, Props.rock(rng.randi(), Vector3(rng.randf_range(0.2, 0.5), rng.randf_range(0.15, 0.35), rng.randf_range(0.2, 0.45))), p, 0.05, 45.0)

	# Big boulders on the mountain slopes.
	for k in 26:
		var a := rng.randf() * TAU
		var p := centre + Vector2(cos(a), sin(a)) * rng.randf_range(4.0, mountain_radius * 0.9)
		var s := rng.randf_range(0.6, 1.4)
		var rock := Props.rock(rng.randi(), Vector3(s, s * 0.7, s))
		rock.position = Vector3(p.x, height_at(p) - 0.2, p.y)
		props.add_child(rock)

	# Pond shores and lily pads.
	for pond in ponds:
		var pc: Vector2 = pond[0]
		var pr: float = pond[1]
		for k in 7:
			var a := rng.randf() * TAU
			var p := pc + Vector2(cos(a), sin(a)) * pr * rng.randf_range(0.9, 1.1)
			if course_sample(p).x < CourseBuilder.RAIL_WIDTH + 0.3:
				continue
			var rock := Props.rock(rng.randi(), Vector3(rng.randf_range(0.2, 0.5), rng.randf_range(0.15, 0.35), rng.randf_range(0.2, 0.4)))
			rock.position = Vector3(p.x, maxf(height_at(p), SkyAndSea.WATER_LEVEL - 0.1), p.y)
			props.add_child(rock)
		for k in 6:
			var a := rng.randf() * TAU
			var p := pc + Vector2(cos(a), sin(a)) * pr * rng.randf_range(0.0, 0.6)
			var pad := Props.lily_pad(rng.randi())
			pad.position = Vector3(p.x, SkyAndSea.WATER_LEVEL + 0.02, p.y)
			props.add_child(pad)


func _spaced(list: Array, p: Vector2, min_dist: float) -> bool:
	for q: Vector2 in list:
		if q.distance_squared_to(p) < min_dist * min_dist:
			return false
	return true


func _multimesh(parent: Node3D, mesh: Mesh, xforms: Array, mat: Material, view_range: float) -> void:
	if xforms.is_empty():
		return
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.mesh = mesh
	mm.instance_count = xforms.size()
	for i in xforms.size():
		mm.set_instance_transform(i, xforms[i])
	var mmi := MultiMeshInstance3D.new()
	mmi.multimesh = mm
	mmi.material_override = mat
	mmi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	mmi.visibility_range_end = view_range
	mmi.visibility_range_end_margin = 4.0
	parent.add_child(mmi)
