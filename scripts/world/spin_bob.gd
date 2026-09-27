extends Node3D
## Spins and bobs (used for the floating "go here" beacon).

var _t := 0.0


func _process(delta: float) -> void:
	_t += delta
	rotation.y = _t * 1.8
	get_child(0).position.y = sin(_t * 2.4) * 0.12
