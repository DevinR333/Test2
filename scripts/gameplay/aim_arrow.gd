class_name AimArrow
extends MeshInstance3D
## Flat arrow on the turf showing shot direction and power (green -> red).

var _mat := StandardMaterial3D.new()


func _init() -> void:
	var st := MeshKit.begin()
	var w := 0.05
	var col := Color.WHITE
	# Unit-length arrow pointing down local -Z; scaled on Z by power.
	MeshKit.face(st, Vector3(-w, 0, 0), Vector3(w, 0, 0), Vector3(w, 0, -0.8), col, Vector3.UP)
	MeshKit.face(st, Vector3(-w, 0, 0), Vector3(w, 0, -0.8), Vector3(-w, 0, -0.8), col, Vector3.UP)
	MeshKit.face(st, Vector3(-w * 2.4, 0, -0.8), Vector3(w * 2.4, 0, -0.8), Vector3(0, 0, -1.0), col, Vector3.UP)
	mesh = MeshKit.finish(st)
	_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_mat.no_depth_test = true
	_mat.render_priority = 10
	material_override = _mat
	cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	visible = false


func show_aim(origin: Vector3, direction: Vector3, power: float) -> void:
	visible = power > 0.01
	var flat := Vector3(direction.x, 0, direction.z).normalized()
	var length := lerpf(0.25, 1.6, power)
	global_transform = Transform3D(Basis.looking_at(flat, Vector3.UP).scaled(Vector3(1, 1, length)), origin + Vector3.UP * 0.01)
	var c := Color(0.35, 0.95, 0.4).lerp(Color(1.0, 0.85, 0.2), clampf(power * 2.0, 0.0, 1.0))
	c = c.lerp(Color(1.0, 0.3, 0.25), clampf(power * 2.0 - 1.0, 0.0, 1.0))
	c.a = 0.85
	_mat.albedo_color = c
