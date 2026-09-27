class_name Spinner
extends Node3D
## A bar that sweeps around a centre post at ball height.

@export var length := 1.1
@export var speed := 1.4

var _bar: AnimatableBody3D


func _ready() -> void:
	var red := Color(0.92, 0.3, 0.25)
	var white := Color(0.97, 0.95, 0.9)
	var mat := MeshKit.facet_material(0.6)

	var post := StaticBody3D.new()
	add_child(post)
	var pst := MeshKit.begin()
	MeshKit.prism(pst, Vector3.ZERO, 0.07, 0.07, 0.16, 8, white)
	MeshKit.prism(pst, Vector3(0, 0.16, 0), 0.09, 0.0, 0.08, 8, red)
	var pm := MeshInstance3D.new()
	pm.mesh = MeshKit.finish(pst)
	pm.material_override = mat
	post.add_child(pm)
	var pcs := CollisionShape3D.new()
	var cyl := CylinderShape3D.new()
	cyl.radius = 0.07
	cyl.height = 0.2
	pcs.shape = cyl
	pcs.position.y = 0.1
	post.add_child(pcs)

	_bar = AnimatableBody3D.new()
	var bm := PhysicsMaterial.new()
	bm.bounce = 0.5
	_bar.physics_material_override = bm
	add_child(_bar)
	var bst := MeshKit.begin()
	var half := length * 0.5
	var stripes := 6
	for i in stripes:
		var x0 := -half + length * float(i) / stripes
		var seg := length / stripes
		MeshKit.box(bst, Vector3(x0 + seg * 0.5, 0.055, 0), Vector3(seg, 0.07, 0.06), red if i % 2 == 0 else white)
	var bmi := MeshInstance3D.new()
	bmi.mesh = MeshKit.finish(bst)
	bmi.material_override = mat
	_bar.add_child(bmi)
	var bcs := CollisionShape3D.new()
	var box := BoxShape3D.new()
	box.size = Vector3(length, 0.07, 0.06)
	bcs.shape = box
	bcs.position.y = 0.055
	_bar.add_child(bcs)


func _physics_process(delta: float) -> void:
	_bar.rotate_y(speed * delta)
