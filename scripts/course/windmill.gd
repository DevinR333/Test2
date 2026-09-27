class_name Windmill
extends Node3D
## Classic mini golf windmill. The lane runs through a tunnel in its base and
## the sails sweep across the tunnel mouth, so shots need timing.
## Local -Z is the direction of play; the sails face +Z (toward the tee).

@export var spin_speed := 1.1
@export var tunnel_width := 0.6
@export var lane_width := 1.3

var _sails: AnimatableBody3D


func _ready() -> void:
	_build()


func _physics_process(delta: float) -> void:
	_sails.rotate_object_local(Vector3.BACK, spin_speed * delta)


func _build() -> void:
	var stone := Color(0.66, 0.64, 0.6)
	var plaster := Color(0.96, 0.93, 0.86)
	var roof := Color(0.86, 0.3, 0.24)
	var wood := Color(0.55, 0.36, 0.2)
	var dark := Color(0.1, 0.08, 0.07)
	var mat := MeshKit.facet_material(0.85)

	var leg_w := (lane_width - tunnel_width) * 0.5 + 0.06
	var leg_x := tunnel_width * 0.5 + leg_w * 0.5
	var base_h := 0.46
	var depth := 1.3

	var body := StaticBody3D.new()
	add_child(body)
	var st := MeshKit.begin()
	for side in [-1.0, 1.0]:
		var c := Vector3(side * leg_x, base_h * 0.5, 0)
		MeshKit.box(st, c, Vector3(leg_w, base_h, depth), stone)
		var cs := CollisionShape3D.new()
		var shape := BoxShape3D.new()
		shape.size = Vector3(leg_w, base_h, depth)
		cs.shape = shape
		cs.position = c
		body.add_child(cs)
	# Dark tunnel ceiling so you can't see into the tower.
	MeshKit.box(st, Vector3(0, base_h + 0.02, 0), Vector3(tunnel_width + 0.02, 0.04, depth), dark)
	# Octagonal tower, trim band, conical roof.
	MeshKit.prism(st, Vector3(0, base_h, 0), 0.78, 0.8, 0.08, 8, wood, true, PI / 8.0)
	MeshKit.prism(st, Vector3(0, base_h + 0.08, 0), 0.74, 0.5, 1.45, 8, plaster, false, PI / 8.0)
	MeshKit.prism(st, Vector3(0, base_h + 1.53, 0), 0.6, 0.6, 0.06, 8, wood, true, PI / 8.0)
	MeshKit.prism(st, Vector3(0, base_h + 1.59, 0), 0.62, 0.0, 0.62, 8, roof, false, PI / 8.0)
	# Door and windows on the front face.
	MeshKit.box(st, Vector3(0, base_h + 0.34, 0.7), Vector3(0.26, 0.4, 0.04), wood.darkened(0.2))
	MeshKit.box(st, Vector3(0.22, base_h + 1.12, 0.54), Vector3(0.14, 0.16, 0.04), dark.lightened(0.15))
	MeshKit.box(st, Vector3(-0.22, base_h + 0.9, 0.58), Vector3(0.14, 0.16, 0.04), dark.lightened(0.15))
	var mi := MeshInstance3D.new()
	mi.mesh = MeshKit.finish(st)
	mi.material_override = mat
	body.add_child(mi)

	# Rotating sails (kinematic, so they push the ball).
	var hub := Vector3(0, base_h + 0.75, 0.82)
	_sails = AnimatableBody3D.new()
	_sails.position = hub
	var pm := PhysicsMaterial.new()
	pm.bounce = 0.5
	_sails.physics_material_override = pm
	add_child(_sails)
	var sst := MeshKit.begin()
	MeshKit.box(sst, Vector3(0, 0, -0.08), Vector3(0.1, 0.1, 0.22), wood.darkened(0.2))
	var length := hub.y - 0.035
	for k in 4:
		var rot := Basis(Vector3.BACK, k * PI * 0.5 + PI * 0.25)
		var arm := MeshKit.begin()
		MeshKit.box(arm, Vector3(0, length * 0.5, 0), Vector3(0.05, length, 0.04), wood)
		MeshKit.box(arm, Vector3(0.1, length * 0.58, 0.01), Vector3(0.16, length * 0.72, 0.015), plaster)
		for r in 4:
			MeshKit.box(arm, Vector3(0.1, length * (0.28 + r * 0.19), 0.025), Vector3(0.18, 0.018, 0.015), wood)
		var arm_mesh := MeshKit.finish(arm)
		var arrays := arm_mesh.surface_get_arrays(0)
		var verts: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
		var cols: PackedColorArray = arrays[Mesh.ARRAY_COLOR]
		for v in range(0, verts.size(), 3):
			var a := rot * verts[v]
			var b := rot * verts[v + 1]
			var c := rot * verts[v + 2]
			MeshKit.face(sst, a, b, c, cols[v], (b - a).cross(c - a) * -1.0)
		var cs := CollisionShape3D.new()
		var shape := BoxShape3D.new()
		shape.size = Vector3(0.26, length, 0.08)
		cs.shape = shape
		cs.transform = Transform3D(rot, rot * Vector3(0.06, length * 0.5, 0))
		_sails.add_child(cs)
	var smi := MeshInstance3D.new()
	smi.mesh = MeshKit.finish(sst)
	smi.material_override = mat
	_sails.add_child(smi)
