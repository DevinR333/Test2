class_name JoystickView
extends Control
## Draws the floating virtual joystick where the left thumb touches.

const RADIUS := 90.0

var base := Vector2.ZERO
var knob := Vector2.ZERO
var active := false


func _init() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	set_anchors_preset(Control.PRESET_FULL_RECT)


func show_at(origin: Vector2, knob_pos: Vector2) -> void:
	base = origin
	knob = knob_pos
	active = true
	queue_redraw()


func hide_stick() -> void:
	active = false
	queue_redraw()


func _draw() -> void:
	if not active:
		return
	draw_circle(base, RADIUS, Color(1, 1, 1, 0.18))
	draw_arc(base, RADIUS, 0, TAU, 48, Color(1, 1, 1, 0.55), 4.0, true)
	draw_circle(knob, RADIUS * 0.42, Color(1, 1, 1, 0.75))
