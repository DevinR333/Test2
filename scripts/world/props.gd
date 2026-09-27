class_name Props
extends RefCounted
## Low-poly decoration: palms, bushes, rocks, flowers, grass tufts, lily pads,
## the pin flag and the hole sign.

const LEAF_DARK := Color(0.16, 0.45, 0.16)
const LEAF_LIGHT := Color(0.45, 0.78, 0.25)

static var _foliage_mat: ShaderMaterial
static var _facet_mat: ShaderMaterial


static func foliage_mat() -> ShaderMaterial:
	if _foliage_mat == null:
		_foliage_mat = MeshKit.shader_material("foliage")
	return _foliage_mat


static func facet_mat() -> ShaderMaterial:
	if _facet_mat == null:
		_facet_mat = MeshKit.facet_material(0.9)
	return _facet_mat


static func palm(seed_value: int, height := 3.2) -> Node3D:
	var rng := RandomNumberGenerator.new()
	rng.seed = seed_value
	var root := Node3D.new()
	root.name = "Palm"
	root.rotation.y = rng.randf() * TAU

	# Curved, banded trunk.
	var lean := Vector3(rng.randf_range(0.5, 1.1), 0, 0)
	var pts: Array = []
	var radii: Array = []
	var segs := 9
	for i in segs + 1:
		var t := float(i) / segs
		pts.append(Vector3(0, t * height, 0) + lean * t * t)
		radii.append(lerpf(0.13, 0.075, t))
	var bands := [Color(0.62, 0.45, 0.28), Color(0.52, 0.37, 0.22)]
	var trunk := MeshInstance3D.new()
	trunk.mesh = MeshKit.tube_mesh(pts, radii, 6, bands)
	trunk.material_override = facet_mat()
	root.add_child(trunk)

	var top: Vector3 = pts[pts.size() - 1]
	var st := MeshKit.begin()
	var fronds := rng.randi_range(7, 9)
	for f in fronds:
		var ang := float(f) / fronds * TAU + rng.randf_range(-0.2, 0.2)
		var dir := Vector3(cos(ang), 0, sin(ang))
		_frond(st, top, dir, rng.randf_range(1.3, 1.7), rng.randf_range(0.25, 0.45), rng)
	var crown := MeshInstance3D.new()
	crown.mesh = MeshKit.finish(st)
	crown.material_override = foliage_mat()
	root.add_child(crown)

	# Coconuts.
	var nut_st := MeshKit.begin()
	for c in 3:
		var a := float(c) / 3.0 * TAU
		_blob_into(nut_st, top + Vector3(cos(a) * 0.12, -0.12, sin(a) * 0.12), 0.085, Color(0.45, 0.3, 0.14), Color(0.3, 0.2, 0.1), rng)
	var nuts := MeshInstance3D.new()
	nuts.mesh = MeshKit.finish(nut_st)
	nuts.material_override = facet_mat()
	root.add_child(nuts)
	return root


static func _frond(st: SurfaceTool, base: Vector3, dir: Vector3, length: float, rise: float, rng: RandomNumberGenerator) -> void:
	var steps := 7
	var prev := base
	var side := dir.cross(Vector3.UP).normalized()
	for i in range(1, steps + 1):
		var t := float(i) / steps
		var p := base + dir * length * t + Vector3.UP * (rise * t - (rise + 0.55) * t * t)
		var w := 0.32 * sin(PI * (0.12 + 0.88 * t)) + 0.03
		var tangent := (p - prev)
		var c_in := LEAF_DARK.lerp(LEAF_LIGHT, t * 0.6)
		var c_out := LEAF_LIGHT.lerp(Color(0.62, 0.86, 0.3), t)
		c_in.a = t
		c_out.a = t
		for sgn in [-1.0, 1.0]:
			var tip: Vector3 = prev + tangent * 1.3 + side * w * sgn + Vector3.DOWN * w * 0.45
			MeshKit.tri(st, prev, p, tip, c_in, c_in, c_out, Vector3.UP)
		prev = p


static func _blob_into(st: SurfaceTool, centre: Vector3, r: float, top: Color, bottom: Color, rng: RandomNumberGenerator) -> void:
	var mesh := MeshKit.blob(Vector3.ONE * r, top, bottom, rng.randi(), 0.15, 3, 5)
	var arrays := mesh.surface_get_arrays(0)
	var verts: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var cols: PackedColorArray = arrays[Mesh.ARRAY_COLOR]
	for i in range(0, verts.size(), 3):
		MeshKit.face(st, centre + verts[i], centre + verts[i + 1], centre + verts[i + 2], cols[i], verts[i] + verts[i + 1] + verts[i + 2])


static func bush(seed_value: int, size := 0.5, flowers := true) -> Node3D:
	var rng := RandomNumberGenerator.new()
	rng.seed = seed_value
	var root := Node3D.new()
	root.name = "Bush"
	var st := MeshKit.begin()
	var count := rng.randi_range(2, 4)
	for i in count:
		var r := size * rng.randf_range(0.6, 1.0)
		var off := Vector3(rng.randf_range(-0.5, 0.5) * size, r * 0.55, rng.randf_range(-0.5, 0.5) * size)
		var mesh := MeshKit.blob(Vector3(r, r * 0.85, r), Color(0.42, 0.74, 0.26), Color(0.15, 0.38, 0.14), rng.randi(), 0.2, 4, 7, 1.0)
		var arrays := mesh.surface_get_arrays(0)
		var verts: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
		var cols: PackedColorArray = arrays[Mesh.ARRAY_COLOR]
		for v in range(0, verts.size(), 3):
			var c := cols[v]
			c.a *= 0.3
			MeshKit.face(st, off + verts[v], off + verts[v + 1], off + verts[v + 2], c, verts[v] + verts[v + 1] + verts[v + 2])
	var mi := MeshInstance3D.new()
	mi.mesh = MeshKit.finish(st)
	mi.material_override = foliage_mat()
	root.add_child(mi)
	if flowers:
		var palette := [Color(1.0, 0.45, 0.62), Color(1.0, 0.85, 0.3), Color(0.98, 0.98, 0.95)]
		var col: Color = palette[rng.randi() % palette.size()]
		var fst := MeshKit.begin()
		for i in rng.randi_range(4, 8):
			var a := rng.randf() * TAU
			var up := rng.randf_range(0.35, 0.95)
			var dir := Vector3(cos(a) * sqrt(1.0 - up * up), up, sin(a) * sqrt(1.0 - up * up))
			var p := Vector3(0, size * 0.5, 0) + dir * size * 0.85
			_flower_head(fst, p, 0.06, col, dir)
		var fm := MeshInstance3D.new()
		fm.mesh = MeshKit.finish(fst)
		fm.material_override = facet_mat()
		root.add_child(fm)
	return root


static func _flower_head(st: SurfaceTool, centre: Vector3, r: float, col: Color, normal: Vector3) -> void:
	var t1 := normal.cross(Vector3.RIGHT if absf(normal.x) < 0.9 else Vector3.FORWARD).normalized()
	var t2 := normal.cross(t1)
	var petals := 5
	var heart := Color(1.0, 0.8, 0.2) if col.b < 0.5 or col.r < 0.99 else Color(1.0, 0.75, 0.2)
	for p in petals:
		var a0 := float(p) / petals * TAU
		var a1 := a0 + TAU / petals * 0.5
		var a2 := a0 + TAU / petals
		var v0 := centre + (t1 * cos(a0) + t2 * sin(a0)) * r * 0.35
		var v1 := centre + (t1 * cos(a1) + t2 * sin(a1)) * r + normal * r * 0.15
		var v2 := centre + (t1 * cos(a2) + t2 * sin(a2)) * r * 0.35
		MeshKit.face(st, v0, v1, v2, col, normal)
		MeshKit.face(st, centre + normal * r * 0.12, v0, v2, heart, normal)


static func rock(seed_value: int, size: Vector3) -> MeshInstance3D:
	var rng := RandomNumberGenerator.new()
	rng.seed = seed_value
	var mi := MeshInstance3D.new()
	mi.name = "Rock"
	var grey := rng.randf_range(0.55, 0.7)
	mi.mesh = MeshKit.blob(size, Color(grey + 0.08, grey + 0.07, grey + 0.02), Color(grey * 0.6, grey * 0.62, grey * 0.6), seed_value, 0.28, 4, 6)
	mi.material_override = facet_mat()
	mi.rotation.y = rng.randf() * TAU
	return mi


## Small fan of grass blades, used via MultiMesh.
static func grass_tuft_mesh() -> ArrayMesh:
	var st := MeshKit.begin()
	var rng := RandomNumberGenerator.new()
	rng.seed = 5
	for b in 5:
		var a := float(b) / 5.0 * TAU + rng.randf_range(-0.3, 0.3)
		var dir := Vector3(cos(a), 0, sin(a))
		var side := dir.cross(Vector3.UP)
		var h := rng.randf_range(0.1, 0.18)
		var base := dir * 0.02
		var tip := base + dir * h * 0.35 + Vector3.UP * h
		var c0 := Color(0.22, 0.5, 0.17, 0.0)
		var c1 := Color(0.5, 0.8, 0.3, 1.0)
		MeshKit.tri(st, base - side * 0.022, base + side * 0.022, tip, c0, c0, c1, dir + Vector3.UP)
	return MeshKit.finish(st)


static func flower_patch_mesh(col: Color) -> ArrayMesh:
	var st := MeshKit.begin()
	var rng := RandomNumberGenerator.new()
	rng.seed = int(col.r * 100 + col.g * 10)
	for f in 3:
		var p := Vector3(rng.randf_range(-0.12, 0.12), rng.randf_range(0.08, 0.14), rng.randf_range(-0.12, 0.12))
		var stem := Color(0.25, 0.55, 0.2, 0.0)
		MeshKit.tri(st, Vector3(p.x - 0.008, 0, p.z), Vector3(p.x + 0.008, 0, p.z), p, stem, stem, stem, Vector3.BACK)
		_flower_head(st, p, 0.045, col, Vector3.UP)
	return MeshKit.finish(st)


static func lily_pad(seed_value: int) -> MeshInstance3D:
	var rng := RandomNumberGenerator.new()
	rng.seed = seed_value
	var st := MeshKit.begin()
	var r := rng.randf_range(0.18, 0.3)
	var sides := 9
	var notch := rng.randi() % sides
	for s in sides:
		if s == notch:
			continue
		var a0 := float(s) / sides * TAU
		var a1 := float(s + 1) / sides * TAU
		var col := Color(0.28, 0.6, 0.22).lightened(rng.randf_range(0.0, 0.08))
		MeshKit.face(st, Vector3.ZERO, Vector3(cos(a0), 0, sin(a0)) * r, Vector3(cos(a1), 0, sin(a1)) * r, col, Vector3.UP)
	if rng.randf() < 0.5:
		_flower_head(st, Vector3(r * 0.2, 0.04, 0), 0.07, Color(1.0, 0.6, 0.78), Vector3.UP)
	var mi := MeshInstance3D.new()
	mi.mesh = MeshKit.finish(st)
	mi.material_override = facet_mat()
	mi.rotation.y = rng.randf() * TAU
	return mi


static func pin_flag() -> Node3D:
	var root := Node3D.new()
	root.name = "Flag"
	var st := MeshKit.begin()
	MeshKit.prism(st, Vector3(0, -0.18, 0), 0.012, 0.012, 1.35, 6, Color(0.97, 0.97, 0.95))
	MeshKit.prism(st, Vector3(0, 1.17, 0), 0.025, 0.0, 0.05, 6, Color(1.0, 0.82, 0.3))
	var pole := MeshInstance3D.new()
	pole.mesh = MeshKit.finish(st)
	pole.material_override = facet_mat()
	root.add_child(pole)
	var cloth_mesh := PlaneMesh.new()
	cloth_mesh.size = Vector2(0.42, 0.28)
	cloth_mesh.subdivide_width = 6
	cloth_mesh.subdivide_depth = 3
	cloth_mesh.orientation = PlaneMesh.FACE_Z
	var cloth := MeshInstance3D.new()
	cloth.mesh = cloth_mesh
	cloth.material_override = MeshKit.shader_material("flag")
	cloth.position = Vector3(0.21, 1.0, 0)
	root.add_child(cloth)
	return root


static func hole_sign(number: int, title: String, par: int) -> Node3D:
	var root := Node3D.new()
	root.name = "Sign"
	var wood := Color(0.6, 0.4, 0.24)
	var st := MeshKit.begin()
	for x in [-0.32, 0.32]:
		MeshKit.box(st, Vector3(x, 0.45, 0), Vector3(0.07, 0.9, 0.07), wood.darkened(0.15))
	MeshKit.box(st, Vector3(0, 0.78, 0), Vector3(0.95, 0.5, 0.06), wood)
	MeshKit.box(st, Vector3(0, 1.05, 0), Vector3(1.05, 0.06, 0.12), Color(0.85, 0.35, 0.25))
	var mi := MeshInstance3D.new()
	mi.mesh = MeshKit.finish(st)
	mi.material_override = facet_mat()
	root.add_child(mi)
	var lines := [["HOLE %d" % number, 0.9, 64], [title, 0.77, 48], ["PAR %d" % par, 0.64, 44]]
	for l in lines:
		var label := Label3D.new()
		label.text = l[0]
		label.font_size = l[2]
		label.pixel_size = 0.0018
		label.position = Vector3(0, l[1], 0.035)
		label.modulate = Color(1.0, 0.96, 0.85)
		label.outline_modulate = Color(0.25, 0.14, 0.06)
		label.outline_size = 10
		label.shaded = false
		root.add_child(label)
	return root


## Decorative set pieces referenced from hole data ("decor").
static func decor(d: Dictionary) -> Node3D:
	match d["type"]:
		"volcano":
			return volcano()
		"lighthouse":
			return lighthouse()
		"chest":
			return treasure_chest()
		"log_tunnel":
			return log_tunnel()
		"rock":
			var s: float = d.get("size", 0.5)
			var holder := Node3D.new()
			holder.add_child(rock(int(s * 1000), Vector3(s, s * 0.8, s)))
			return holder
	push_warning("Unknown decor type %s" % d["type"])
	return Node3D.new()


static func _mesh_node(st: SurfaceTool, mat: Material = null) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.mesh = MeshKit.finish(st)
	mi.material_override = mat if mat else facet_mat()
	return mi


static func volcano() -> Node3D:
	var root := Node3D.new()
	root.name = "Volcano"
	var st := MeshKit.begin()
	MeshKit.prism(st, Vector3(0, -0.3, 0), 1.7, 1.1, 0.9, 9, Color(0.46, 0.38, 0.33), false)
	MeshKit.prism(st, Vector3(0, 0.6, 0), 1.1, 0.5, 1.0, 9, Color(0.38, 0.31, 0.28), false, 0.3)
	MeshKit.prism(st, Vector3(0, 1.6, 0), 0.5, 0.42, 0.1, 9, Color(0.3, 0.24, 0.22), false, 0.3)
	root.add_child(_mesh_node(st))
	var lava := MeshKit.begin()
	MeshKit.prism(lava, Vector3(0, 1.55, 0), 0.42, 0.42, 0.1, 9, Color(1.0, 0.45, 0.1), true, 0.3)
	for i in 3:
		var a := i * 2.1
		var p0 := Vector3(cos(a) * 0.4, 1.62, sin(a) * 0.4)
		var p1 := Vector3(cos(a) * 0.75, 1.0, sin(a) * 0.75)
		var side := Vector3(-sin(a), 0, cos(a)) * 0.09
		MeshKit.face(lava, p0 - side, p0 + side, p1, Color(1.0, 0.5, 0.12), Vector3(cos(a), 0.6, sin(a)))
	var glow := StandardMaterial3D.new()
	glow.vertex_color_use_as_albedo = true
	glow.emission_enabled = true
	glow.emission = Color(1.0, 0.4, 0.05)
	glow.emission_energy_multiplier = 1.6
	root.add_child(_mesh_node(lava, glow))
	return root


static func lighthouse() -> Node3D:
	var root := Node3D.new()
	root.name = "Lighthouse"
	var st := MeshKit.begin()
	var red := Color(0.88, 0.25, 0.22)
	var white := Color(0.97, 0.95, 0.9)
	MeshKit.prism(st, Vector3(0, -0.2, 0), 0.75, 0.62, 0.3, 8, Color(0.6, 0.58, 0.55))
	var bands := 5
	for i in bands:
		var t0 := float(i) / bands
		var t1 := float(i + 1) / bands
		MeshKit.prism(st, Vector3(0, 0.1 + t0 * 2.4, 0), lerpf(0.48, 0.32, t0), lerpf(0.48, 0.32, t1), 2.4 / bands, 8, red if i % 2 == 0 else white, false)
	MeshKit.prism(st, Vector3(0, 2.5, 0), 0.46, 0.46, 0.06, 8, Color(0.2, 0.2, 0.22))
	MeshKit.prism(st, Vector3(0, 2.56, 0), 0.26, 0.26, 0.34, 8, Color(0.3, 0.3, 0.32), false)
	MeshKit.prism(st, Vector3(0, 2.9, 0), 0.34, 0.0, 0.34, 8, red, false)
	root.add_child(_mesh_node(st))
	var lamp := MeshKit.begin()
	MeshKit.prism(lamp, Vector3(0, 2.6, 0), 0.2, 0.2, 0.26, 8, Color(1.0, 0.92, 0.5))
	var glow := StandardMaterial3D.new()
	glow.vertex_color_use_as_albedo = true
	glow.emission_enabled = true
	glow.emission = Color(1.0, 0.85, 0.4)
	glow.emission_energy_multiplier = 2.0
	root.add_child(_mesh_node(lamp, glow))
	return root


static func treasure_chest() -> Node3D:
	var root := Node3D.new()
	root.name = "Chest"
	var st := MeshKit.begin()
	var wood := Color(0.55, 0.33, 0.17)
	var gold := Color(1.0, 0.8, 0.25)
	MeshKit.box(st, Vector3(0, 0.14, 0), Vector3(0.55, 0.28, 0.36), wood)
	MeshKit.box(st, Vector3(0, 0.14, 0), Vector3(0.57, 0.05, 0.38), gold)
	MeshKit.box(st, Vector3(0, 0.3, 0), Vector3(0.5, 0.06, 0.32), gold.lightened(0.1))
	root.add_child(_mesh_node(st))
	var lid := MeshKit.begin()
	MeshKit.box(lid, Vector3(0, 0.05, 0.16), Vector3(0.56, 0.1, 0.34), wood.darkened(0.1))
	var lid_node := _mesh_node(lid)
	lid_node.position = Vector3(0, 0.28, -0.18)
	lid_node.rotation.x = -1.0
	root.add_child(lid_node)
	return root


## Hollow log arch the lane passes under (decoration only).
static func log_tunnel() -> Node3D:
	var root := Node3D.new()
	root.name = "LogTunnel"
	var st := MeshKit.begin()
	var bark := Color(0.45, 0.3, 0.18)
	var inner := Color(0.8, 0.62, 0.4)
	var r_out := 0.95
	var r_in := 0.85
	var half_len := 0.55
	var sides := 10
	for s in sides:
		var a0 := PI * float(s) / sides
		var a1 := PI * float(s + 1) / sides
		var o0 := Vector3(cos(a0) * r_out, sin(a0) * r_out * 0.7, 0)
		var o1 := Vector3(cos(a1) * r_out, sin(a1) * r_out * 0.7, 0)
		var i0 := Vector3(cos(a0) * r_in, sin(a0) * r_in * 0.7, 0)
		var i1 := Vector3(cos(a1) * r_in, sin(a1) * r_in * 0.7, 0)
		var f := Vector3(0, 0, half_len)
		var out_n := (o0 + o1).normalized()
		var shade := bark.darkened(0.06 * (s % 2))
		MeshKit.face(st, o0 - f, o1 - f, o1 + f, shade, out_n)
		MeshKit.face(st, o0 - f, o1 + f, o0 + f, shade, out_n)
		MeshKit.face(st, i0 - f, i1 - f, i1 + f, inner.darkened(0.2), -out_n)
		MeshKit.face(st, i0 - f, i1 + f, i0 + f, inner.darkened(0.2), -out_n)
		for z in [-1.0, 1.0]:
			var zf: Vector3 = f * z
			MeshKit.face(st, o0 + zf, o1 + zf, i1 + zf, inner, Vector3(0, 0, z))
			MeshKit.face(st, o0 + zf, i1 + zf, i0 + zf, inner, Vector3(0, 0, z))
	# Legs down past the rail walls into the ground.
	for x in [-1.0, 1.0]:
		MeshKit.box(st, Vector3(x * (r_out + r_in) * 0.5, -0.25, 0), Vector3(r_out - r_in, 0.5, half_len * 2.0), bark)
	root.add_child(_mesh_node(st))
	return root
