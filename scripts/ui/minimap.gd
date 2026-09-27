class_name Minimap
extends Control
## North-up island map: courses, hole numbers, the player and the ball.

const SIZE := 210.0

var _holes: Array = []
var _radius := 60.0
var _player := Vector3.ZERO
var _yaw := 0.0
var _ball: Variant = null
var _target := 0
var _font: Font


func _init() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	custom_minimum_size = Vector2(SIZE, SIZE)
	size = Vector2(SIZE, SIZE)
	_font = ThemeDB.fallback_font


func setup(holes: Array, radius: float) -> void:
	_holes = holes
	_radius = radius
	queue_redraw()


func update_state(player: Vector3, yaw: float, ball: Variant, target_hole: int) -> void:
	_player = player
	_yaw = yaw
	_ball = ball
	_target = target_hole
	queue_redraw()


func _to_map(p: Vector3) -> Vector2:
	var scale := (SIZE * 0.5 - 6.0) / (_radius * 1.02)
	return Vector2(SIZE, SIZE) * 0.5 + Vector2(p.x, p.z) * scale


func _draw() -> void:
	var c := Vector2(SIZE, SIZE) * 0.5
	draw_circle(c, SIZE * 0.5, Color(0.12, 0.45, 0.6, 0.85))
	draw_circle(c, SIZE * 0.5 - 12.0, Color(0.93, 0.84, 0.6, 0.95))
	draw_circle(c, SIZE * 0.5 - 17.0, Color(0.42, 0.7, 0.3, 0.95))
	draw_circle(c, SIZE * 0.13, Color(0.5, 0.44, 0.4, 0.9))
	for h: Dictionary in _holes:
		var col := Color(1.0, 0.95, 0.5) if h["number"] == _target else Color(0.78, 0.95, 0.6)
		for p: Array in h["paths"]:
			var pts: Array = p[0]
			for i in pts.size() - 1:
				draw_line(_to_map(pts[i]), _to_map(pts[i + 1]), col, 3.0, true)
		var tee := _to_map(h["tee"])
		draw_circle(tee, 7.0, Color(0.1, 0.35, 0.4))
		var txt := str(h["number"])
		var w := _font.get_string_size(txt, HORIZONTAL_ALIGNMENT_LEFT, -1, 10).x
		draw_string(_font, tee + Vector2(-w * 0.5, 3.5), txt, HORIZONTAL_ALIGNMENT_LEFT, -1, 10, Color.WHITE)
	if _ball != null:
		draw_circle(_to_map(_ball), 4.0, Color.WHITE)
	var pp := _to_map(_player)
	var fwd := Vector2(-sin(_yaw), -cos(_yaw))
	var side := Vector2(-fwd.y, fwd.x)
	draw_colored_polygon(PackedVector2Array([pp + fwd * 10.0, pp - fwd * 6.0 + side * 6.0, pp - fwd * 6.0 - side * 6.0]), Color(1.0, 0.4, 0.3))
