class_name MeshKit
extends RefCounted
## Procedural low-poly mesh helpers.
##
## Everything in the game is built from code so the whole level lives in plain
## text. Meshes are emitted un-indexed so every triangle gets its own flat
## normal, which is what gives the faceted, polygonal look.

const SHADER_DIR := "res://shaders/"


static func shader_material(shader_name: String, params := {}) -> ShaderMaterial:
	var m := ShaderMaterial.new()
	m.shader = load(SHADER_DIR + shader_name + ".gdshader")
	for key in params:
		m.set_shader_parameter(key, params[key])
	return m


## Flat-shaded material that takes its colour from the mesh vertex colours.
static func facet_material(roughness := 0.9) -> ShaderMaterial:
	return shader_material("facet", {"roughness": roughness})


static func solid_material(color: Color, roughness := 0.8) -> ShaderMaterial:
	return shader_material("facet", {"roughness": roughness, "tint": color, "use_vertex_color": false})


## Adds one triangle, flipping its winding when needed so the front face
## points along `facing` (Godot treats clockwise triangles as front faces).
static func tri(st: SurfaceTool, a: Vector3, b: Vector3, c: Vector3,
		ca: Color, cb: Color, cc: Color, facing: Vector3) -> void:
	if (c - a).cross(b - a).dot(facing) < 0.0:
		var tv := b
		b = c
		c = tv
		var tc := cb
		cb = cc
		cc = tc
	st.set_color(ca)
	st.add_vertex(a)
	st.set_color(cb)
	st.add_vertex(b)
	st.set_color(cc)
	st.add_vertex(c)


## Same as tri() but the whole face gets a single colour.
static func face(st: SurfaceTool, a: Vector3, b: Vector3, c: Vector3, col: Color, facing: Vector3) -> void:
	tri(st, a, b, c, col, col, col, facing)


static func begin() -> SurfaceTool:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	return st


static func finish(st: SurfaceTool) -> ArrayMesh:
	st.generate_normals()
	return st.commit()


## Low-poly rock / bush / cloud: a jittered UV sphere, flat shaded, with a
## vertical colour ramp (darker underneath acts as baked ambient occlusion).
static func blob(size: Vector3, color_top: Color, color_bottom: Color, seed_value := 0,
		jitter := 0.22, rings := 5, segments := 8, sway := 0.0) -> ArrayMesh:
	var rng := RandomNumberGenerator.new()
	rng.seed = seed_value
	var pts: Array = []
	for r in rings + 1:
		var phi := float(r) / rings * PI
		var row: Array = []
		var offset := 0.5 * (r % 2)
		for s in segments:
			var theta := (float(s) + offset) / segments * TAU
			var dir := Vector3(sin(phi) * cos(theta), cos(phi), sin(phi) * sin(theta))
			var amount := 1.0
			if r != 0 and r != rings:
				amount += rng.randf_range(-jitter, jitter)
			row.append(dir * amount * size)
		pts.append(row)
	var st := begin()
	for r in rings:
		for s in segments:
			var s2 := (s + 1) % segments
			var a: Vector3 = pts[r][s]
			var b: Vector3 = pts[r][s2]
			var c: Vector3 = pts[r + 1][s2]
			var d: Vector3 = pts[r + 1][s]
			for quad_tri in [[a, b, c], [a, c, d]]:
				var p0: Vector3 = quad_tri[0]
				var p1: Vector3 = quad_tri[1]
				var p2: Vector3 = quad_tri[2]
				if p0.is_equal_approx(p1) or p1.is_equal_approx(p2) or p0.is_equal_approx(p2):
					continue
				var centre := (p0 + p1 + p2) / 3.0
				var t := clampf(centre.y / size.y * 0.5 + 0.5, 0.0, 1.0)
				var col := color_bottom.lerp(color_top, smoothstep(0.0, 1.0, t))
				col = col.lightened(rng.randf_range(0.0, 0.06))
				col.a = sway * t
				face(st, p0, p1, p2, col, centre)
	return finish(st)


## A tapered tube through `points`, used for palm trunks and posts.
static func tube(st: SurfaceTool, points: Array, radii: Array, sides: int, colors: Array) -> void:
	var rings: Array = []
	for i in points.size():
		var p: Vector3 = points[i]
		var tangent: Vector3
		if i == 0:
			tangent = (points[1] as Vector3) - p
		elif i == points.size() - 1:
			tangent = p - (points[i - 1] as Vector3)
		else:
			tangent = (points[i + 1] as Vector3) - (points[i - 1] as Vector3)
		tangent = tangent.normalized()
		var side := tangent.cross(Vector3.FORWARD if absf(tangent.z) < 0.9 else Vector3.RIGHT).normalized()
		var up := side.cross(tangent).normalized()
		var ring: Array = []
		for s in sides:
			var ang := float(s) / sides * TAU + 0.35 * i
			ring.append(p + (side * cos(ang) + up * sin(ang)) * float(radii[i]))
		rings.append(ring)
	for i in points.size() - 1:
		var axis_mid: Vector3 = ((points[i] as Vector3) + (points[i + 1] as Vector3)) * 0.5
		var col: Color = colors[i % colors.size()]
		for s in sides:
			var s2 := (s + 1) % sides
			var a: Vector3 = rings[i][s]
			var b: Vector3 = rings[i][s2]
			var c: Vector3 = rings[i + 1][s2]
			var d: Vector3 = rings[i + 1][s]
			face(st, a, b, c, col, (a + b + c) / 3.0 - axis_mid)
			face(st, a, c, d, col.darkened(0.04), (a + c + d) / 3.0 - axis_mid)
	# Cap the top so the tube never looks hollow.
	var last: Array = rings[rings.size() - 1]
	var top: Vector3 = points[points.size() - 1]
	var tip_dir := top - (points[points.size() - 2] as Vector3)
	for s in sides:
		face(st, top, last[s], last[(s + 1) % sides], colors[0], tip_dir)


static func tube_mesh(points: Array, radii: Array, sides: int, colors: Array) -> ArrayMesh:
	var st := begin()
	tube(st, points, radii, sides, colors)
	return finish(st)


## Axis aligned box with per-face shading, centred on `center`.
static func box(st: SurfaceTool, center: Vector3, size: Vector3, col: Color) -> void:
	var h := size * 0.5
	var corners: Array = []
	for i in 8:
		corners.append(center + Vector3(h.x * (1 if i & 1 else -1), h.y * (1 if i & 2 else -1), h.z * (1 if i & 4 else -1)))
	# Corner index bits: x = 1, y = 2, z = 4.
	var faces := [[0, 1, 5, 4, Vector3.DOWN], [2, 3, 7, 6, Vector3.UP], [0, 1, 3, 2, Vector3.FORWARD],
		[4, 5, 7, 6, Vector3.BACK], [0, 2, 6, 4, Vector3.LEFT], [1, 3, 7, 5, Vector3.RIGHT]]
	for f in faces:
		var n: Vector3 = f[4]
		var shade := col
		if n == Vector3.UP:
			shade = col.lightened(0.05)
		elif n == Vector3.DOWN:
			shade = col.darkened(0.25)
		var a: Vector3 = corners[f[0]]
		var b: Vector3 = corners[f[1]]
		var c: Vector3 = corners[f[2]]
		var d: Vector3 = corners[f[3]]
		face(st, a, b, c, shade, n)
		face(st, a, c, d, shade, n)


## Regular n-sided prism / frustum standing on `base`, flat shaded.
static func prism(st: SurfaceTool, base: Vector3, r_bottom: float, r_top: float, height: float,
		sides: int, col: Color, cap_top := true, rot := 0.0) -> void:
	var top := base + Vector3.UP * height
	for s in sides:
		var a0 := rot + float(s) / sides * TAU
		var a1 := rot + float(s + 1) / sides * TAU
		var d0 := Vector3(cos(a0), 0, sin(a0))
		var d1 := Vector3(cos(a1), 0, sin(a1))
		var b0 := base + d0 * r_bottom
		var b1 := base + d1 * r_bottom
		var t0 := top + d0 * r_top
		var t1 := top + d1 * r_top
		var out := (d0 + d1).normalized()
		var shade := col.darkened(0.08 * (0.5 + 0.5 * sin(a0 * 1.0 + 1.0)))
		if r_top > 0.0001:
			face(st, b0, b1, t1, shade, out)
			face(st, b0, t1, t0, shade, out)
		else:
			face(st, b0, b1, top, shade, out + Vector3.UP * 0.3)
		if cap_top and r_top > 0.0001:
			face(st, top, t0, t1, col.lightened(0.05), Vector3.UP)
		face(st, base, b0, b1, col.darkened(0.3), Vector3.DOWN)
