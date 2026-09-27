class_name CameraRig
extends Node3D
## Orbit camera that follows the ball. Drag to orbit, pinch / wheel to zoom.

@export var min_distance := 1.2
@export var max_distance := 9.0
@export var min_pitch := deg_to_rad(8.0)
@export var max_pitch := deg_to_rad(75.0)

var yaw := 0.0
var pitch := deg_to_rad(24.0)
var distance := 3.0
var target: Node3D
var camera: Camera3D


func _init() -> void:
	camera = Camera3D.new()
	camera.fov = 62.0
	camera.near = 0.03
	camera.far = 600.0
	add_child(camera)


func snap() -> void:
	if target:
		global_position = target.global_position
	_update_camera()


func orbit(relative: Vector2, viewport_height: float) -> void:
	yaw -= relative.x / viewport_height * 3.0
	pitch = clampf(pitch + relative.y / viewport_height * 2.0, min_pitch, max_pitch)


func zoom(factor: float) -> void:
	distance = clampf(distance * factor, min_distance, max_distance)


## Turns the camera so it looks from behind the ball toward `point`.
func face_toward(point: Vector3) -> void:
	var d := point - global_position
	yaw = atan2(d.x, d.z) + PI


func _process(delta: float) -> void:
	if target:
		global_position = global_position.lerp(target.global_position, 1.0 - exp(-delta * 7.0))
	_update_camera()


func _update_camera() -> void:
	var b := Basis.from_euler(Vector3(-pitch, yaw, 0.0))
	camera.position = b * Vector3(0, 0, distance)
	camera.look_at(global_position + Vector3.UP * 0.1)
