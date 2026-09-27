class_name SlidingBlock
extends Node3D
## A painted block that slides back and forth across the lane.

@export var axis := Vector3.RIGHT
@export var travel := 0.4
@export var speed := 1.4
@export var phase := 0.0

var _block: AnimatableBody3D
var _time := 0.0


func _ready() -> void:
	_time = phase
	_block = AnimatableBody3D.new()
	var pm := PhysicsMaterial.new()
	pm.bounce = 0.55
	_block.physics_material_override = pm
	add_child(_block)
	var size := Vector3(0.36, 0.2, 0.26)
	var st := MeshKit.begin()
	MeshKit.box(st, Vector3(0, size.y * 0.5, 0), size, Color(0.98, 0.55, 0.25))
	MeshKit.box(st, Vector3(0, size.y * 0.5, 0), Vector3(size.x + 0.01, 0.05, size.z + 0.01), Color(0.98, 0.95, 0.85))
	MeshKit.box(st, Vector3(0, size.y + 0.015, 0), Vector3(size.x * 0.7, 0.03, size.z * 0.7), Color(0.86, 0.35, 0.2))
	var mi := MeshInstance3D.new()
	mi.mesh = MeshKit.finish(st)
	mi.material_override = MeshKit.facet_material(0.7)
	_block.add_child(mi)
	var cs := CollisionShape3D.new()
	var box := BoxShape3D.new()
	box.size = size
	cs.shape = box
	cs.position.y = size.y * 0.5
	_block.add_child(cs)
	# Orient so the block's long side runs across its direction of travel.
	rotation.y = atan2(-axis.z, axis.x)


func _physics_process(delta: float) -> void:
	_time += delta
	_block.position = Vector3.RIGHT * sin(_time * speed) * travel
