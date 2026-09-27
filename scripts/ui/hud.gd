class_name Hud
extends CanvasLayer
## On-screen UI: hole info, stroke counter, power meter, banners, the
## scorecard (which doubles as a hole picker) and the loading card.

signal reset_pressed
signal banner_tapped
signal hole_chosen(number: int)
signal play_again

const INK := Color(0.12, 0.25, 0.3)

var _hole_label: Label
var _strokes: Label
var _total: Label
var _card: PanelContainer
var _card_grid: GridContainer
var _card_total: Label
var _card_again: Button
var _loading: PanelContainer
var _loading_label: Label
var _banner: PanelContainer
var _banner_title: Label
var _banner_sub: Label
var _power_back: Panel
var _power_fill: ColorRect
var _toast: Label
var _hint: Label
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
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 28)
	box.add_child(row)
	_strokes = _label("", 34, Color(0.1, 0.55, 0.5))
	row.add_child(_strokes)
	_total = _label("", 24, INK)
	_total.size_flags_vertical = Control.SIZE_SHRINK_END
	row.add_child(_total)

	var reset := _button("Reset ball", 24)
	_anchor(reset, Vector2(1, 0), Vector2(-230, 24), Vector2(206, 56))
	reset.pressed.connect(func(): reset_pressed.emit())
	root.add_child(reset)
	var card_btn := _button("Card", 24)
	_anchor(card_btn, Vector2(1, 0), Vector2(-360, 24), Vector2(116, 56))
	card_btn.pressed.connect(func():
		if _card.visible:
			hide_scorecard()
		else:
			show_scorecard(_last_scores, false))
	root.add_child(card_btn)

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

	_hint = _label("", 30, Color.WHITE)
	_hint.add_theme_constant_override("outline_size", 10)
	_hint.add_theme_color_override("font_outline_color", INK)
	_hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_anchor(_hint, Vector2(0.5, 1), Vector2(-450, -130), Vector2(900, 50))
	root.add_child(_hint)

	_build_scorecard(root)

	_loading = PanelContainer.new()
	_loading.add_theme_stylebox_override("panel", _style(Color(0.1, 0.42, 0.5, 0.96), 0))
	_loading.set_anchors_preset(Control.PRESET_FULL_RECT)
	_loading.visible = false
	root.add_child(_loading)
	_loading_label = _label("", 64, Color.WHITE)
	_loading_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_loading_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	_loading.add_child(_loading_label)


var _last_scores: Array = []


func _build_scorecard(root: Control) -> void:
	_card = PanelContainer.new()
	_card.add_theme_stylebox_override("panel", _style(Color(1, 1, 1, 0.97), 28))
	_anchor(_card, Vector2(0.5, 0.5), Vector2.ZERO, Vector2.ZERO)
	_card.grow_horizontal = Control.GROW_DIRECTION_BOTH
	_card.grow_vertical = Control.GROW_DIRECTION_BOTH
	_card.visible = false
	root.add_child(_card)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 12)
	_card.add_child(v)
	var title := _label("Lagoon Links  ·  Scorecard", 34, INK)
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(title)
	var hint := _label("Tap a hole number to play it", 20, Color(0.35, 0.45, 0.5))
	hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(hint)
	_card_grid = GridContainer.new()
	_card_grid.columns = 10
	_card_grid.add_theme_constant_override("h_separation", 6)
	_card_grid.add_theme_constant_override("v_separation", 4)
	v.add_child(_card_grid)
	_card_total = _label("", 28, Color(0.1, 0.55, 0.5))
	_card_total.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(_card_total)
	var buttons := HBoxContainer.new()
	buttons.alignment = BoxContainer.ALIGNMENT_CENTER
	buttons.add_theme_constant_override("separation", 20)
	v.add_child(buttons)
	_card_again = _button("Play again", 26)
	_card_again.pressed.connect(func(): play_again.emit())
	buttons.add_child(_card_again)
	var close := _button("Close", 26)
	close.pressed.connect(hide_scorecard)
	buttons.add_child(close)


## Shows the 18-hole card. scores[i] == 0 means not played yet.
func show_scorecard(scores: Array, final: bool) -> void:
	_last_scores = scores
	for c in _card_grid.get_children():
		c.queue_free()
	var total := 0
	var total_par := 0
	for half in 2:
		_card_grid.add_child(_cell("Hole", true))
		for i in range(half * 9, half * 9 + 9):
			var b := _button(str(i + 1), 22)
			b.custom_minimum_size = Vector2(58, 44)
			var n := i + 1
			b.pressed.connect(func(): hole_chosen.emit(n))
			_card_grid.add_child(b)
		_card_grid.add_child(_cell("Par", true))
		for i in range(half * 9, half * 9 + 9):
			var par := int(Holes.get_hole(i + 1)["par"])
			_card_grid.add_child(_cell(str(par), false))
		_card_grid.add_child(_cell("Score", true))
		for i in range(half * 9, half * 9 + 9):
			var sc: int = scores[i] if i < scores.size() else 0
			var par := int(Holes.get_hole(i + 1)["par"])
			var cell := _cell(str(sc) if sc > 0 else "–", false)
			if sc > 0:
				total += sc
				total_par += par
				cell.add_theme_color_override("font_color", Color(0.15, 0.6, 0.3) if sc < par else (INK if sc == par else Color(0.85, 0.35, 0.25)))
			_card_grid.add_child(cell)
	var diff := total - total_par
	_card_total.text = "Total %d  (%s)" % [total, "E" if diff == 0 else "%+d" % diff] if total > 0 else "Total par %d" % Holes.total_par()
	_card_again.visible = final
	_card.visible = true


func hide_scorecard() -> void:
	_card.visible = false


func show_loading(text: String) -> void:
	_loading_label.text = text
	_loading.visible = true


func hide_loading() -> void:
	_loading.visible = false


## Persistent helper text above the power meter ("" hides it).
func set_hint(text: String) -> void:
	_hint.text = text


func set_total(total: int, to_par: int) -> void:
	_total.text = "" if total == 0 else "Round: %d (%s)" % [total, "E" if to_par == 0 else "%+d" % to_par]


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


func _cell(text: String, header: bool) -> Label:
	var l := _label(text, 22 if header else 24, INK if header else Color(0.2, 0.3, 0.35))
	l.custom_minimum_size = Vector2(76 if header else 58, 40)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	return l


func _button(text: String, size: int) -> Button:
	var b := Button.new()
	b.text = text
	b.focus_mode = Control.FOCUS_NONE
	b.add_theme_font_size_override("font_size", size)
	b.add_theme_color_override("font_color", INK)
	b.add_theme_color_override("font_pressed_color", INK)
	b.add_theme_color_override("font_hover_color", INK)
	b.add_theme_stylebox_override("normal", _style(Color(1, 1, 1, 0.85), 22))
	b.add_theme_stylebox_override("pressed", _style(Color(0.8, 0.94, 0.94, 0.95), 22))
	b.add_theme_stylebox_override("hover", _style(Color(0.95, 0.98, 0.98, 0.95), 22))
	return b


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
