class_name Hud
extends CanvasLayer
## On-screen UI: info card, minimap, context action buttons, virtual joystick,
## power meter, banners, the scorecard (doubles as fast travel) and loading card.

signal primary_pressed
signal secondary_pressed
signal banner_tapped
signal hole_chosen(number: int)
signal play_again

const INK := Color(0.12, 0.25, 0.3)

var joystick: JoystickView
var minimap: Minimap

var _title: Label
var _sub: Label
var _total: Label
var _primary: Button
var _secondary: Button
var _banner: PanelContainer
var _banner_title: Label
var _banner_sub: Label
var _power_back: Panel
var _power_fill: ColorRect
var _toast: Label
var _toast_time := 0.0
var _hint: Label
var _card: PanelContainer
var _card_grid: GridContainer
var _card_total: Label
var _card_again: Button
var _loading: PanelContainer
var _loading_label: Label
var _last_scores: Array = []


func _init() -> void:
	var root := Control.new()
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(root)

	joystick = JoystickView.new()
	root.add_child(joystick)

	var card := PanelContainer.new()
	card.add_theme_stylebox_override("panel", _style(Color(1, 1, 1, 0.88), 22))
	card.position = Vector2(24, 20)
	card.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(card)
	var box := VBoxContainer.new()
	card.add_child(box)
	_title = _label("", 26, INK)
	box.add_child(_title)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 24)
	box.add_child(row)
	_sub = _label("", 30, Color(0.1, 0.55, 0.5))
	row.add_child(_sub)
	_total = _label("", 22, INK)
	_total.size_flags_vertical = Control.SIZE_SHRINK_END
	row.add_child(_total)

	minimap = Minimap.new()
	_anchor(minimap, Vector2(1, 0), Vector2(-234, 20), Vector2(Minimap.SIZE, Minimap.SIZE))
	root.add_child(minimap)
	var card_btn := _button("Card", 24)
	_anchor(card_btn, Vector2(1, 0), Vector2(-190, 244), Vector2(120, 56))
	card_btn.pressed.connect(func():
		if _card.visible:
			hide_scorecard()
		else:
			show_scorecard(_last_scores, false))
	root.add_child(card_btn)

	_primary = _button("", 34)
	_primary.add_theme_stylebox_override("normal", _style(Color(1.0, 0.55, 0.35, 0.95), 30))
	_primary.add_theme_stylebox_override("pressed", _style(Color(0.9, 0.45, 0.28, 0.98), 30))
	_primary.add_theme_stylebox_override("hover", _style(Color(1.0, 0.6, 0.4, 0.98), 30))
	_primary.add_theme_color_override("font_color", Color.WHITE)
	_primary.add_theme_color_override("font_pressed_color", Color.WHITE)
	_primary.add_theme_color_override("font_hover_color", Color.WHITE)
	_anchor(_primary, Vector2(1, 1), Vector2(-330, -120), Vector2(300, 90))
	_primary.pressed.connect(func(): primary_pressed.emit())
	root.add_child(_primary)
	_secondary = _button("", 24)
	_anchor(_secondary, Vector2(1, 1), Vector2(-330, -190), Vector2(300, 56))
	_secondary.pressed.connect(func(): secondary_pressed.emit())
	root.add_child(_secondary)

	_power_back = Panel.new()
	_power_back.add_theme_stylebox_override("panel", _style(Color(0, 0, 0, 0.35), 14))
	_anchor(_power_back, Vector2(0.5, 1), Vector2(-210, -70), Vector2(420, 28))
	_power_back.visible = false
	root.add_child(_power_back)
	_power_fill = ColorRect.new()
	_power_fill.position = Vector2(4, 4)
	_power_fill.size = Vector2(0, 20)
	_power_back.add_child(_power_fill)

	_toast = _outlined("", 44)
	_anchor(_toast, Vector2(0.5, 0), Vector2(-300, 110), Vector2(600, 60))
	root.add_child(_toast)
	_hint = _outlined("", 28)
	_hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_anchor(_hint, Vector2(0.5, 1), Vector2(-360, -200), Vector2(720, 90))
	root.add_child(_hint)

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

	_build_scorecard(root)

	_loading = PanelContainer.new()
	_loading.add_theme_stylebox_override("panel", _style(Color(0.1, 0.42, 0.5, 1.0), 0))
	_loading.set_anchors_preset(Control.PRESET_FULL_RECT)
	_loading.visible = false
	root.add_child(_loading)
	_loading_label = _label("", 64, Color.WHITE)
	_loading_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_loading_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	_loading.add_child(_loading_label)
	set_actions("", "")


# --- info & actions -----------------------------------------------------------

func set_info(title: String, sub: String) -> void:
	_title.text = title
	_sub.text = sub


func set_total(total: int, to_par: int) -> void:
	_total.text = "" if total == 0 else "Round: %d (%s)" % [total, "E" if to_par == 0 else "%+d" % to_par]


## Context buttons; "" hides a button.
func set_actions(primary: String, secondary: String) -> void:
	_primary.text = primary
	_primary.visible = primary != ""
	_secondary.text = secondary
	_secondary.visible = secondary != ""


func set_power(power: float, show: bool) -> void:
	_power_back.visible = show
	_power_fill.size.x = 412.0 * clampf(power, 0.0, 1.0)
	var c := Color(0.35, 0.95, 0.4).lerp(Color(1.0, 0.85, 0.2), clampf(power * 2.0, 0.0, 1.0))
	_power_fill.color = c.lerp(Color(1.0, 0.3, 0.25), clampf(power * 2.0 - 1.0, 0.0, 1.0))


func toast(text: String) -> void:
	_toast.text = text
	_toast.modulate.a = 1.0
	_toast_time = 1.8


func set_hint(text: String) -> void:
	_hint.text = text


func show_banner(title: String, subtitle: String) -> void:
	_banner_title.text = title
	_banner_sub.text = subtitle
	_banner.visible = true


func hide_banner() -> void:
	_banner.visible = false


func banner_visible() -> bool:
	return _banner.visible


func show_loading(text: String) -> void:
	_loading_label.text = text
	_loading.visible = true


func hide_loading() -> void:
	_loading.visible = false


func _process(delta: float) -> void:
	if _toast_time > 0.0:
		_toast_time -= delta
		_toast.modulate.a = clampf(_toast_time / 0.5, 0.0, 1.0)


func _on_banner_input(event: InputEvent) -> void:
	if (event is InputEventScreenTouch and not event.pressed) or (event is InputEventMouseButton and not event.pressed):
		banner_tapped.emit()


# --- scorecard ----------------------------------------------------------------

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
	var hint := _label("Tap a hole number to travel to its tee", 20, Color(0.35, 0.45, 0.5))
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
	_card_again = _button("New round", 26)
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
			_card_grid.add_child(_cell(str(Holes.get_hole(i + 1)["par"]), false))
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


func scorecard_visible() -> bool:
	return _card.visible


# --- helpers ------------------------------------------------------------------

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
	b.add_theme_stylebox_override("normal", _style(Color(1, 1, 1, 0.88), 22))
	b.add_theme_stylebox_override("pressed", _style(Color(0.8, 0.94, 0.94, 0.95), 22))
	b.add_theme_stylebox_override("hover", _style(Color(0.95, 0.98, 0.98, 0.95), 22))
	return b


func _label(text: String, size: int, color: Color) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	return l


func _outlined(text: String, size: int) -> Label:
	var l := _label(text, size, Color.WHITE)
	l.add_theme_constant_override("outline_size", 12)
	l.add_theme_color_override("font_outline_color", INK)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
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
