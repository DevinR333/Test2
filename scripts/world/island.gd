class_name Island
extends RefCounted
## Low-poly island terrain that the course sits on, plus scattered grass,
## flowers, bushes, rocks and palms.

const STEP := 0.7
const EXTENT := 26.0

var field: CourseField          # coarse field, reaches a few metres past the rails
var centre: Vector2
var radius: float
var ponds: Array = []           # [Vector2 centre, float radius]
var _noise := FastNoiseLite.new()
var _detail := FastNoiseLite.new()


var seed_value := 0


func _init(course_field: CourseField, island_centre: Vector2, island_radius: float, variation := 0) -> void:
	field = course_field
	centre = island_centre
	radius = island_radius
	seed_value = variation
	_noise.seed = 11 + variation * 17
	_noise.frequency = 0.06
	_detail.seed = 3 + variation * 5
	_detail.frequency = 0.28


func add_pond(c: Vector2, r: float) -> void:
	ponds.append([c, r])


## Terrain height at p, blending down to a skirt just below the course.
func height_at(p: Vector2) -> float:
	var r := p.distance_to(centre) / (radius * (1.0 + _noise.get_noise_2dv(p) * 0.25))
	var h := lerpf(0.25, -2.4, smoothstep(0.72, 1.12, r))
	h += _detail.get_noise_2dv(p) * 0.22 * (1.0 - smoothstep(0.7, 1.0, r))
	h += maxf(_noise.get_noise_2dv(p * 1.7 + Vector2(40, 9)), 0.0) * 0.9 * (1.0 - smoothstep(0.5, 0.9, r))
	for pond in ponds:
		var pc: Vector2 = pond[0]
		var pr: float = pond[1]
		var k := 1.0 - smoothstep(pr * 0.55, pr * 1.25, p.distance_to(pc))
		h = lerpf(h, -1.2, k)
	var s := field.sample(p)
	var near := 1.0 - smoothstep(CourseBuilder.RAIL_WIDTH, CourseBuilder.RAIL_WIDTH + 1.4, s.x)
	h = lerpf(h, s.y - 0.3, near)
	return h


func is_land(p: Vector2, clearance: float) -> bool:
	return height_at(p) > SkyAndSea.WATER_LEVEL + 0.15 and field.sample(p).x > CourseBuilder.RAIL_WIDTH + clearance


func build(parent: Node3D) -> void:
	var node := Node3D.new()
	node.name = "Island"
	parent.add_child(node)
	_terrain(node)
	_scatter(node)


func _terrain(parent: Node3D) -> void:
	var n := int(EXTENT * 2.0 / STEP) + 1
	var jitter := RandomNumberGenerator.new()
	jitter.seed = 99
	var grid: Array = []
	for j in n:
		var row: Array = []
		for i in n:
			var p := centre + Vector2(i * STEP - EXTENT, j * STEP - EXTENT)
			if i > 0 and j > 0 and i < n - 1 and j < n - 1:
				p += Vector2(jitter.randf_range(-0.28, 0.28), jitter.randf_range(-0.28, 0.28)) * STEP
			row.append(Vector3(p.x, height_at(p), p.y))
		grid.append(row)
	var st := MeshKit.begin()
	var tint := FastNoiseLite.new()
	tint.frequency = 0.15
	for j in n - 1:
		for i in n - 1:
			var a: Vector3 = grid[j][i]
			var b: Vector3 = grid[j][i + 1]
			var c: Vector3 = grid[j + 1][i + 1]
			var d: Vector3 = grid[j + 1][i]
			var tris := [[a, b, c], [a, c, d]] if (i + j) % 2 == 0 else [[a, b, d], [b, c, d]]
			for t in tris:
				var p0: Vector3 = t[0]
				var p1: Vector3 = t[1]
				var p2: Vector3 = t[2]
				var mid := (p0 + p1 + p2) / 3.0
				MeshKit.face(st, p0, p1, p2, _ground_color(mid, tint), Vector3.UP)
	var mi := MeshInstance3D.new()
	mi.name = "Terrain"
	mi.mesh = MeshKit.finish(st)
	mi.material_override = MeshKit.facet_material(0.95)
	parent.add_child(mi)


func _ground_color(p: Vector3, tint: FastNoiseLite) -> Color:
	var water := SkyAndSea.WATER_LEVEL
	var sand := Color(0.96, 0.86, 0.6)
	var wet := Color(0.78, 0.68, 0.46)
	var grass_a := Color(0.42, 0.72, 0.26)
	var grass_b := Color(0.3, 0.6, 0.22)
	var t := tint.get_noise_2d(p.x, p.z) * 0.5 + 0.5
	var grass := grass_a.lerp(grass_b, t)
	var col: Color
	if p.y < water - 0.05:
		col = wet.darkened(clampf((water - p.y) * 0.25, 0.0, 0.4))
	elif p.y < water + 0.28:
		col = sand
	else:
		col = sand.lerp(grass, smoothstep(water + 0.28, water + 0.45, p.y))
	# Darken next to the foundation walls (baked contact shadow).
	var d := field.sample(Vector2(p.x, p.z)).x
	col = col.darkened(0.25 * (1.0 - smoothstep(CourseBuilder.RAIL_WIDTH, CourseBuilder.RAIL_WIDTH + 0.9, d)))
	return col.lightened(randf() * 0.03)


func _place(parent: Node3D, node: Node3D, p: Vector2, sink := 0.02) -> void:
	node.position = Vector3(p.x, height_at(p) - sink, p.y)
	parent.add_child(node)


func _scatter(parent: Node3D) -> void:
	var rng := RandomNumberGenerator.new()
	rng.seed = 1234 + seed_value

	# Grass tufts and flower patches via MultiMesh (cheap on mobile).
	var tufts: Array = []
	var flowers: Array = [[], [], []]
	for i in 5000:
		var p := centre + Vector2(rng.randf_range(-1, 1), rng.randf_range(-1, 1)) * radius * 1.05
		var h := height_at(p)
		if h < SkyAndSea.WATER_LEVEL + 0.35 or field.sample(p).x < CourseBuilder.RAIL_WIDTH + 0.12:
			continue
		var xf := Transform3D(Basis(Vector3.UP, rng.randf() * TAU).scaled(Vector3.ONE * rng.randf_range(0.7, 1.4)), Vector3(p.x, h - 0.01, p.y))
		if rng.randf() < 0.07:
			flowers[rng.randi() % 3].append(xf)
		else:
			tufts.append(xf)
	_multimesh(parent, Props.grass_tuft_mesh(), tufts, Props.foliage_mat())
	var colors := [Color(1.0, 0.45, 0.62), Color(1.0, 0.85, 0.3), Color(0.98, 0.98, 0.95)]
	for c in 3:
		_multimesh(parent, Props.flower_patch_mesh(colors[c]), flowers[c], Props.facet_mat())

	# Bushes and rocks hug the course edges, like a planted border.
	var placed: Array = []
	var tries := 0
	while placed.size() < 34 and tries < 3000:
		tries += 1
		var p := centre + Vector2(rng.randf_range(-1, 1), rng.randf_range(-1, 1)) * radius
		var d := field.sample(p).x
		if d > 3.5 or not is_land(p, 0.45):
			continue
		var ok := true
		for q: Vector2 in placed:
			if q.distance_to(p) < 1.1:
				ok = false
				break
		if not ok:
			continue
		placed.append(p)
		if rng.randf() < 0.65:
			_place(parent, Props.bush(rng.randi(), rng.randf_range(0.3, 0.5), rng.randf() < 0.6), p)
		else:
			_place(parent, Props.rock(rng.randi(), Vector3(rng.randf_range(0.2, 0.45), rng.randf_range(0.15, 0.3), rng.randf_range(0.2, 0.4))), p, 0.05)

	# Rocks around the pond shores.
	for pond in ponds:
		var pc: Vector2 = pond[0]
		var pr: float = pond[1]
		for k in 7:
			var a := rng.randf() * TAU
			var p := pc + Vector2(cos(a), sin(a)) * pr * rng.randf_range(0.9, 1.1)
			if field.sample(p).x < CourseBuilder.RAIL_WIDTH + 0.3:
				continue
			var rock := Props.rock(rng.randi(), Vector3(rng.randf_range(0.2, 0.5), rng.randf_range(0.15, 0.35), rng.randf_range(0.2, 0.4)))
			rock.position = Vector3(p.x, maxf(height_at(p), SkyAndSea.WATER_LEVEL - 0.1), p.y)
			parent.add_child(rock)
		for k in 6:
			var a := rng.randf() * TAU
			var p := pc + Vector2(cos(a), sin(a)) * pr * rng.randf_range(0.0, 0.6)
			var pad := Props.lily_pad(rng.randi())
			pad.position = Vector3(p.x, SkyAndSea.WATER_LEVEL + 0.02, p.y)
			parent.add_child(pad)


## Scatters palms around the course, keeping them off the lane and apart.
func place_palms(parent: Node3D, count: int, seed_value: int) -> void:
	var rng := RandomNumberGenerator.new()
	rng.seed = seed_value
	var placed: Array = []
	var tries := 0
	while placed.size() < count and tries < 2000:
		tries += 1
		var p := centre + Vector2(rng.randf_range(-1, 1), rng.randf_range(-1, 1)) * radius * 0.95
		var d := field.sample(p).x
		if d < 1.8 or d > 6.0 or not is_land(p, 0.8):
			continue
		var ok := true
		for q: Vector2 in placed:
			if q.distance_to(p) < 2.4:
				ok = false
				break
		if ok:
			placed.append(p)
			_place(parent, Props.palm(rng.randi(), rng.randf_range(2.6, 3.6)), p, 0.05)


func _multimesh(parent: Node3D, mesh: Mesh, xforms: Array, mat: Material) -> void:
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
	parent.add_child(mmi)
