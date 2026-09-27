extends Node3D
## Slowly rotates its children around the island (used for the clouds).

@export var speed := 0.004


func _process(delta: float) -> void:
	rotate_y(speed * delta)
