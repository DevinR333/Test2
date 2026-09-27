class_name Walker
extends Node3D
## First-person explorer. Floats along the ground using ray casts against the
## terrain and course floors, steps up small ledges, slides along blocked
## directions and refuses to walk into the sea.

const EYE_HEIGHT := 1.45
const SPEED := 3.4
const MAX_STEP := 0.75
const GROUND_MASK := 0b11      # course bodies (layer 1) + terrain (layer 2)

var yaw := 0.0
var pitch := -0.18
var camera: Camera3D
## Joystick input: x = strafe right, y = forward. Length 0..1.
var move_input := Vector2.ZERO
var exclude: Array[RID] = []

var _ground_y := 0.0


func _init() -> void:
	camera = Camera3D.new()
	camera.fov = 72.0
	camera.near = 0.05
	camera.far = 600.0
	camera.position.y = EYE_HEIGHT
	add_child(camera)


func place(pos: Vector3, facing_yaw: float) -> void:
	yaw = facing_yaw
	pitch = -0.18
	global_position = pos
	var g: Variant = ground_at(pos)
	_ground_y = g if g != null else pos.y
	global_position.y = _ground_y
	_apply_rotation()


func look(relative: Vector2, viewport_height: float) -> void:
	yaw -= relative.x / viewport_height * 2.6
	pitch = clampf(pitch - relative.y / viewport_height * 2.0, -1.25, 1.0)
	_apply_rotation()


## Horizontal direction the player is facing.
func forward() -> Vector3:
	return Basis(Vector3.UP, yaw) * Vector3.FORWARD


## Height of walkable ground under p, or null.
func ground_at(p: Vector3) -> Variant:
	if not is_inside_tree():
		return null
	var space := get_world_3d().direct_space_state
	var from := Vector3(p.x, maxf(p.y, _ground_y) + MAX_STEP + 0.4, p.z)
	var q := PhysicsRayQueryParameters3D.create(from, Vector3(p.x, from.y - 30.0, p.z), GROUND_MASK, exclude)
	var hit := space.intersect_ray(q)
	return (hit["position"] as Vector3).y if not hit.is_empty() else null


func _physics_process(delta: float) -> void:
	var inp := move_input + _keyboard()
	if inp.length() > 1.0:
		inp = inp.normalized()
	if inp.length() > 0.02:
		var step := Basis(Vector3.UP, yaw) * Vector3(inp.x, 0, -inp.y) * SPEED * delta
		if not _try_move(step):
			# Slide along whichever axis is free.
			if not _try_move(Vector3(step.x, 0, 0)):
				_try_move(Vector3(0, 0, step.z))
	var p := global_position
	p.y = lerpf(p.y, _ground_y, 1.0 - exp(-delta * 14.0))
	global_position = p


func _try_move(step: Vector3) -> bool:
	if step.length() < 1e-5:
		return false
	var target := global_position + step
	var g: Variant = ground_at(target)
	if g == null:
		return false
	var gy: float = g
	if gy < SkyAndSea.WATER_LEVEL + 0.08 or gy - _ground_y > MAX_STEP:
		return false
	global_position = Vector3(target.x, global_position.y, target.z)
	_ground_y = gy
	return true


func _keyboard() -> Vector2:
	var v := Vector2.ZERO
	if Input.is_physical_key_pressed(KEY_W) or Input.is_physical_key_pressed(KEY_UP):
		v.y += 1
	if Input.is_physical_key_pressed(KEY_S) or Input.is_physical_key_pressed(KEY_DOWN):
		v.y -= 1
	if Input.is_physical_key_pressed(KEY_D) or Input.is_physical_key_pressed(KEY_RIGHT):
		v.x += 1
	if Input.is_physical_key_pressed(KEY_A) or Input.is_physical_key_pressed(KEY_LEFT):
		v.x -= 1
	return v


func _apply_rotation() -> void:
	rotation = Vector3(0, yaw, 0)
	camera.rotation = Vector3(pitch, 0, 0)
