class_name GolfBall
extends RigidBody3D
## The ball. Uses real rigid-body physics (Jolt) for bounces, slopes and the
## windmill, plus a rolling-resistance term so it slows like it's on felt.

signal came_to_rest

const RADIUS := 0.05
const MAX_SPEED := 7.0

@export var rolling_resistance := 0.9   # m/s^2 of deceleration while rolling
@export var rest_speed := 0.06

var moving := false
var grounded := false
var _pending_velocity := Vector3.ZERO
var _has_pending := false
var _still_time := 0.0


func _init() -> void:
	mass = 0.046
	continuous_cd = true
	contact_monitor = true
	max_contacts_reported = 6
	can_sleep = false
	freeze_mode = RigidBody3D.FREEZE_MODE_KINEMATIC
	angular_damp = 0.4
	var pm := PhysicsMaterial.new()
	pm.friction = 0.6
	pm.bounce = 0.1
	physics_material_override = pm

	var cs := CollisionShape3D.new()
	var sphere := SphereShape3D.new()
	sphere.radius = RADIUS
	cs.shape = sphere
	add_child(cs)

	var mesh := SphereMesh.new()
	mesh.radius = RADIUS
	mesh.height = RADIUS * 2.0
	mesh.radial_segments = 12
	mesh.rings = 6
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = MeshKit.solid_material(Color(1.0, 1.0, 1.0), 0.35)
	add_child(mi)


func strike(velocity: Vector3) -> void:
	freeze = false
	_pending_velocity = velocity.limit_length(MAX_SPEED)
	_has_pending = true
	moving = true
	_still_time = 0.0


func place(pos: Vector3) -> void:
	freeze = true
	moving = false
	_has_pending = false
	global_position = pos
	linear_velocity = Vector3.ZERO
	angular_velocity = Vector3.ZERO


func _integrate_forces(state: PhysicsDirectBodyState3D) -> void:
	if _has_pending:
		# Start already rolling (not skidding) so putts carry the expected distance.
		state.linear_velocity = _pending_velocity
		state.angular_velocity = Vector3.UP.cross(_pending_velocity) / RADIUS
		_has_pending = false
		return
	grounded = false
	for i in state.get_contact_count():
		if absf(state.get_contact_local_normal(i).y) > 0.5:
			grounded = true
	if not moving:
		return
	var v := state.linear_velocity
	var speed := v.length()
	if grounded and speed > 0.0:
		var factor := maxf(speed - rolling_resistance * state.step, 0.0) / speed
		state.linear_velocity = v * factor
		state.angular_velocity *= factor
		if speed < rest_speed:
			_still_time += state.step
			if _still_time > 0.35:
				state.linear_velocity = Vector3.ZERO
				state.angular_velocity = Vector3.ZERO
				moving = false
				_stop.call_deferred()
		else:
			_still_time = 0.0


func _stop() -> void:
	freeze = true
	came_to_rest.emit()
