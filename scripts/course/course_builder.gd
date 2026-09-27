class_name CourseBuilder
extends RefCounted
## Turns a LaneShape into geometry: turf floor (with the cup cut out),
## chamfered rails, stone foundation walls and the cup itself, plus the static
## collision bodies the ball rolls on.
##
## The floor and rail tops come from marching squares over a CourseField, so
## any lane shape (curves, ramps, round greens) builds with clean edges.

const RAIL_WIDTH := 0.15
const RAIL_HEIGHT := 0.14
const RAIL_CHAMFER := 0.045
const CUP_RADIUS := 0.12
const CUP_DEPTH := 0.2
const CUP_SIDES := 16
const FOUNDATION_DEPTH := 1.8

const CELL := 0.05

var field: CourseField
var cup := Vector3.ZERO

var _floor_f := PackedFloat32Array()
var _rail_f := PackedFloat32Array()


func build(shape: LaneShape, cup_xz: Vector2, parent: Node3D) -> void:
	field = CourseField.new(shape, CELL, RAIL_WIDTH + 0.2)
	cup = Vector3(cup_xz.x, field.sample(cup_xz).y, cup_xz.y)

	var n := field.nx * field.nz
	_floor_f.resize(n)
	_rail_f.resize(n)
	for j in field.nz:
		for i in field.nx:
			var k := j * field.nx + i
			var d := field.dist[k]
			var to_cup := field.pos(i, j).distance_to(cup_xz)
			_floor_f[k] = maxf(d, CUP_RADIUS - to_cup)
			_rail_f[k] = maxf(-d, d - RAIL_WIDTH)

	var turf := MeshKit.shader_material("turf")
	var rail_mat := MeshKit.shader_material("stone_cap")
	var wall_mat := MeshKit.shader_material("brick")

	# Floor + cup share one static body so the ball rolls straight into the hole.
	var floor_st := MeshKit.begin()
	_march(_floor_f, floor_st, 0)
	var floor_mesh := MeshKit.finish(floor_st)
	var cup_mesh := _build_cup()

	var rail_st := MeshKit.begin()
	_march(_rail_f, rail_st, 1)
	_walls(rail_st, 0.0, true)
	var rail_mesh := MeshKit.finish(rail_st)

	var wall_st := MeshKit.begin()
	_walls(wall_st, RAIL_WIDTH, false)
	var wall_mesh := MeshKit.finish(wall_st)

	_add_body(parent, "Turf", [floor_mesh, cup_mesh], [turf, MeshKit.facet_material(0.6)], 0.55, 0.05)
	_add_body(parent, "Rails", [rail_mesh], [rail_mat], 0.15, 0.62)
	var walls := MeshInstance3D.new()
	walls.name = "Foundation"
	walls.mesh = wall_mesh
	walls.material_override = wall_mat
	parent.add_child(walls)


func _add_body(parent: Node3D, body_name: String, meshes: Array, mats: Array, friction: float, bounce: float) -> void:
	var body := StaticBody3D.new()
	body.name = body_name
	var pm := PhysicsMaterial.new()
	pm.friction = friction
	pm.bounce = bounce
	body.physics_material_override = pm
	parent.add_child(body)
	for i in meshes.size():
		var mesh: ArrayMesh = meshes[i]
		var mi := MeshInstance3D.new()
		mi.mesh = mesh
		mi.material_override = mats[i]
		body.add_child(mi)
		var cs := CollisionShape3D.new()
		cs.shape = mesh.create_trimesh_shape()
		body.add_child(cs)


## Height of the rail cap above the floor at signed distance d (chamfered edges).
static func rail_profile(d: float) -> float:
	var x := clampf(minf(d, RAIL_WIDTH - d), 0.0, RAIL_WIDTH)
	return RAIL_HEIGHT - maxf(RAIL_CHAMFER - x, 0.0)


# --- marching squares -------------------------------------------------------

func _edge_point(f: PackedFloat32Array, ka: int, kb: int) -> Vector4:
	# Always interpolate from the lower index so neighbouring cells agree exactly.
	if ka > kb:
		var t := ka
		ka = kb
		kb = t
	var s := f[ka] / (f[ka] - f[kb])
	var pa := field.pos(ka % field.nx, ka / field.nx)
	var pb := field.pos(kb % field.nx, kb / field.nx)
	var p := pa.lerp(pb, s)
	return Vector4(p.x, p.y, lerpf(field.dist[ka], field.dist[kb], s), lerpf(field.height[ka], field.height[kb], s))


func _corner(k: int) -> Vector4:
	var p := field.pos(k % field.nx, k / field.nx)
	return Vector4(p.x, p.y, field.dist[k], field.height[k])


## kind 0 = turf floor, kind 1 = rail cap.
func _march(f: PackedFloat32Array, st: SurfaceTool, kind: int) -> void:
	var nx := field.nx
	for j in field.nz - 1:
		for i in nx - 1:
			var k0 := j * nx + i
			var ks := [k0, k0 + 1, k0 + nx + 1, k0 + nx]
			var inside := 0
			for k in ks:
				if f[k] < 0.0:
					inside += 1
			if inside == 0:
				continue
			var poly: Array = []
			for c in 4:
				var ka: int = ks[c]
				var kb: int = ks[(c + 1) % 4]
				if f[ka] < 0.0:
					poly.append(_corner(ka))
				if (f[ka] < 0.0) != (f[kb] < 0.0):
					poly.append(_edge_point(f, ka, kb))
			for t in range(1, poly.size() - 1):
				_emit(st, [poly[0], poly[t], poly[t + 1]], kind)


func _emit(st: SurfaceTool, v: Array, kind: int) -> void:
	var pts: Array = []
	var cols: Array = []
	for q: Vector4 in v:
		if kind == 0:
			# Snap cup-edge vertices exactly onto the cup circle.
			var p2 := Vector2(q.x, q.y)
			var to_cup := p2 - Vector2(cup.x, cup.z)
			if absf(to_cup.length() - CUP_RADIUS) < CELL * 0.75 and q.z < -0.01:
				p2 = Vector2(cup.x, cup.z) + to_cup.normalized() * CUP_RADIUS
			pts.append(Vector3(p2.x, q.w, p2.y))
			var edge_ao := lerpf(0.7, 1.0, smoothstep(0.0, 0.3, -q.z))
			var cup_ao := lerpf(0.8, 1.0, smoothstep(CUP_RADIUS, CUP_RADIUS + 0.08, to_cup.length()))
			var ao := edge_ao * cup_ao
			cols.append(Color(ao, ao, ao))
		else:
			pts.append(Vector3(q.x, q.w + rail_profile(q.z), q.y))
			cols.append(Color.WHITE)
	MeshKit.tri(st, pts[0], pts[1], pts[2], cols[0], cols[1], cols[2], Vector3.UP)


## Vertical walls along the contour where lane distance == iso.
## Inner walls face into the lane; outer walls face away and drop to the ground.
func _walls(st: SurfaceTool, iso: float, inner: bool) -> void:
	var nx := field.nx
	for j in field.nz - 1:
		for i in nx - 1:
			var k0 := j * nx + i
			var ks := [k0, k0 + 1, k0 + nx + 1, k0 + nx]
			var g: Array = []
			var any_in := false
			var any_out := false
			for k in ks:
				var v: float = field.dist[k] - iso
				g.append(v)
				if v < 0.0:
					any_in = true
				else:
					any_out = true
			if not (any_in and any_out):
				continue
			var crossings: Array = []
			var f := PackedFloat32Array(g)
			for c in 4:
				var a: float = g[c]
				var b: float = g[(c + 1) % 4]
				if (a < 0.0) != (b < 0.0):
					var s := a / (a - b)
					var ka: int = ks[c]
					var kb: int = ks[(c + 1) % 4]
					var p := field.pos(ka % nx, ka / nx).lerp(field.pos(kb % nx, kb / nx), s)
					crossings.append(Vector3(p.x, lerpf(field.height[ka], field.height[kb], s), p.y))
			var grad := Vector3(((f[1] - f[0]) + (f[2] - f[3])), 0.0, ((f[3] - f[0]) + (f[2] - f[1]))).normalized()
			var facing := -grad if inner else grad
			for pair in range(0, crossings.size() - 1, 2):
				var a3: Vector3 = crossings[pair]
				var b3: Vector3 = crossings[pair + 1]
				var top_off := rail_profile(iso)
				var bottom_off := -0.03 if inner else -FOUNDATION_DEPTH
				var at := a3 + Vector3.UP * top_off
				var bt := b3 + Vector3.UP * top_off
				var ab := a3 + Vector3.UP * bottom_off
				var bb := b3 + Vector3.UP * bottom_off
				var col := Color.WHITE
				MeshKit.face(st, ab, at, bt, col, facing)
				MeshKit.face(st, ab, bt, bb, col, facing)


func _build_cup() -> ArrayMesh:
	var st := MeshKit.begin()
	var r := CUP_RADIUS * 1.02
	var liner := Color(0.95, 0.95, 0.92)
	var inside := Color(0.12, 0.12, 0.1)
	var bottom := cup + Vector3.DOWN * CUP_DEPTH
	for s in CUP_SIDES:
		var a0 := float(s) / CUP_SIDES * TAU
		var a1 := float(s + 1) / CUP_SIDES * TAU
		var d0 := Vector3(cos(a0), 0, sin(a0)) * r
		var d1 := Vector3(cos(a1), 0, sin(a1)) * r
		var inward := -(d0 + d1)
		var lip := Vector3.DOWN * 0.025
		MeshKit.face(st, cup + d0, cup + d1, cup + d1 + lip, liner, inward)
		MeshKit.face(st, cup + d0, cup + d1 + lip, cup + d0 + lip, liner, inward)
		MeshKit.face(st, cup + d0 + lip, cup + d1 + lip, bottom + d1, inside, inward)
		MeshKit.face(st, cup + d0 + lip, bottom + d1, bottom + d0, inside, inward)
		MeshKit.face(st, bottom, bottom + d0, bottom + d1, inside.darkened(0.3), Vector3.UP)
	return MeshKit.finish(st)
