class_name Hud
extends CanvasLayer
## On-screen UI: hole info, stroke counter, power meter, banners and buttons.

signal reset_pressed
signal banner_tapped

const INK := Color(0.12, 0.25, 0.3)

var _hole_label: Label
var _strokes: Label
var _banner: PanelContainer
var _banner_title: Label
var _banner_sub: Label
var _power_back: Panel
var _power_fill: ColorRect
var _toast: Label
var _toast_time := 0.0


func _init() -> void:
	var root := Control.new()
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(root)

	var card := PanelContainer.new()
	card.add_theme_stylebox_override("panel", _style(Color(1, 1, 1, 0.88), 22))
	card.position = Vector2(24, 20)
	root.add_child(card)
	var box := VBoxContainer.new()
	card.add_child(box)
	_hole_label = _label("", 26, INK)
	box.add_child(_hole_label)
	_strokes = _label("", 36, Color(0.1, 0.55, 0.5))
	box.add_child(_strokes)

	var reset := Button.new()
	reset.text = "  Reset ball  "
	reset.add_theme_font_size_override("font_size", 24)
	reset.add_theme_color_override("font_color", INK)
	reset.add_theme_stylebox_override("normal", _style(Color(1, 1, 1, 0.85), 26))
	reset.add_theme_stylebox_override("pressed", _style(Color(0.85, 0.95, 0.95, 0.95), 26))
	reset.add_theme_stylebox_override("hover", _style(Color(1, 1, 1, 0.95), 26))
	_anchor(reset, Vector2(1, 0), Vector2(-230, 24), Vector2(206, 56))
	reset.focus_mode = Control.FOCUS_NONE
	reset.pressed.connect(func(): reset_pressed.emit())
	root.add_child(reset)

	_power_back = Panel.new()
	_power_back.add_theme_stylebox_override("panel", _style(Color(0, 0, 0, 0.35), 14))
	_anchor(_power_back, Vector2(0.5, 1), Vector2(-210, -70), Vector2(420, 28))
	_power_back.visible = false
	root.add_child(_power_back)
	_power_fill = ColorRect.new()
	_power_fill.position = Vector2(4, 4)
	_power_fill.size = Vector2(0, 20)
	_power_back.add_child(_power_fill)

	_toast = _label("", 44, Color.WHITE)
	_toast.add_theme_constant_override("outline_size", 12)
	_toast.add_theme_color_override("font_outline_color", INK)
	_toast.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_anchor(_toast, Vector2(0.5, 0), Vector2(-300, 110), Vector2(600, 60))
	root.add_child(_toast)

	_banner = PanelContainer.new()
	_banner.add_theme_stylebox_override("panel", _style(Color(1, 1, 1, 0.94), 32))
	_anchor(_banner, Vector2(0.5, 0.5), Vector2.ZERO, Vector2.ZERO)
	_banner.grow_horizontal = Control.GROW_DIRECTION_BOTH
	_banner.grow_vertical = Control.GROW_DIRECTION_BOTH
	_banner.visible = false
	_banner.gui_input.connect(_on_banner_input)
	root.add_child(_banner)
	var bbox := VBoxContainer.new()
	bbox.alignment = BoxContainer.ALIGNMENT_CENTER
	_banner.add_child(bbox)
	_banner_title = _label("", 72, Color(1.0, 0.45, 0.3))
	_banner_title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	bbox.add_child(_banner_title)
	_banner_sub = _label("", 28, INK)
	_banner_sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	bbox.add_child(_banner_sub)


func setup(number: int, hole_name: String, par: int) -> void:
	_hole_label.text = "Hole %d  ·  %s  ·  Par %d" % [number, hole_name, par]
	set_strokes(0)


func set_strokes(n: int) -> void:
	_strokes.text = "Strokes: %d" % n


func set_power(power: float, show: bool) -> void:
	_power_back.visible = show
	_power_fill.size.x = 412.0 * clampf(power, 0.0, 1.0)
	var c := Color(0.35, 0.95, 0.4).lerp(Color(1.0, 0.85, 0.2), clampf(power * 2.0, 0.0, 1.0))
	_power_fill.color = c.lerp(Color(1.0, 0.3, 0.25), clampf(power * 2.0 - 1.0, 0.0, 1.0))


func toast(text: String) -> void:
	_toast.text = text
	_toast.modulate.a = 1.0
	_toast_time = 1.8


func show_banner(title: String, subtitle: String) -> void:
	_banner_title.text = title
	_banner_sub.text = subtitle
	_banner.visible = true


func hide_banner() -> void:
	_banner.visible = false


func _process(delta: float) -> void:
	if _toast_time > 0.0:
		_toast_time -= delta
		_toast.modulate.a = clampf(_toast_time / 0.5, 0.0, 1.0)


func _on_banner_input(event: InputEvent) -> void:
	if (event is InputEventScreenTouch and not event.pressed) or (event is InputEventMouseButton and not event.pressed):
		banner_tapped.emit()


## Pins a control to an anchor point of the screen with a fixed-size box.
func _anchor(c: Control, anchor: Vector2, offset: Vector2, size: Vector2) -> void:
	c.anchor_left = anchor.x
	c.anchor_right = anchor.x
	c.anchor_top = anchor.y
	c.anchor_bottom = anchor.y
	c.offset_left = offset.x
	c.offset_top = offset.y
	c.offset_right = offset.x + size.x
	c.offset_bottom = offset.y + size.y


func _label(text: String, size: int, color: Color) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	return l


func _style(color: Color, radius: int) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.set_corner_radius_all(radius)
	s.content_margin_left = 22
	s.content_margin_right = 22
	s.content_margin_top = 12
	s.content_margin_bottom = 12
	s.shadow_color = Color(0, 0, 0, 0.18)
	s.shadow_size = 6
	s.shadow_offset = Vector2(0, 3)
	return s
