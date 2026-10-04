extends Node3D
## Creature viewer: stages the Host in a narrow, dim corridor and (with --shots) saves screenshots.

const SHOTS := [
	# name, camera position, look target, fov, flashlight on, bulb energy
	["host_hallway", Vector3(0.05, 1.62, 5.2), Vector3(0.05, 1.45, 0.0), 50.0, true, 1.0],
	["host_closeup", Vector3(0.12, 1.86, 1.05), Vector3(0.16, 1.88, 0.15), 45.0, true, 0.6],
	["host_jumpscare", Vector3(0.20, 1.80, 0.72), Vector3(0.20, 1.95, 0.15), 70.0, true, 0.0],
]

var cam: Camera3D
var flashlight: SpotLight3D
var bulb: OmniLight3D

func _ready() -> void:
	_build_environment()
	_build_corridor()
	_build_host()
	cam = Camera3D.new()
	add_child(cam)
	flashlight = SpotLight3D.new()
	flashlight.light_color = Color(1.0, 0.93, 0.8)
	flashlight.light_energy = 3.0
	flashlight.spot_range = 12.0
	flashlight.spot_angle = 24.0
	flashlight.spot_angle_attenuation = 1.6
	flashlight.shadow_enabled = true
	flashlight.light_volumetric_fog_energy = 1.5
	cam.add_child(flashlight)
	flashlight.position = Vector3(0.18, -0.2, 0.0)
	if "--shots" in OS.get_cmdline_user_args():
		_take_shots()
	else:
		_frame(0)

func _frame(i: int) -> void:
	var s: Array = SHOTS[i]
	cam.position = s[1]
	cam.look_at(s[2])
	cam.fov = s[3]
	flashlight.visible = s[4]
	bulb.light_energy = 2.2 * s[5]
	flashlight.look_at(s[2])

func _take_shots() -> void:
	DirAccess.make_dir_recursive_absolute("res://docs/screenshots")
	for i in SHOTS.size():
		_frame(i)
		for f in 24:  # let volumetric fog and TAA-free AA settle
			await RenderingServer.frame_post_draw
		var img := get_viewport().get_texture().get_image()
		img.save_png("res://docs/screenshots/%s.png" % SHOTS[i][0])
		print("saved ", SHOTS[i][0])
	get_tree().quit()

func _build_environment() -> void:
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0, 0, 0)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.25, 0.3, 0.35)
	env.ambient_light_energy = 0.04
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.tonemap_exposure = 1.1
	env.ssao_enabled = true
	env.ssao_radius = 0.6
	env.ssao_intensity = 2.5
	env.ssil_enabled = true
	env.glow_enabled = true
	env.glow_intensity = 0.5
	env.glow_bloom = 0.05
	env.volumetric_fog_enabled = true
	env.volumetric_fog_density = 0.035
	env.volumetric_fog_albedo = Color(0.8, 0.8, 0.75)
	env.volumetric_fog_length = 14.0
	env.adjustment_enabled = true
	env.adjustment_saturation = 0.7
	env.adjustment_contrast = 1.15
	var we := WorldEnvironment.new()
	we.environment = env
	var attrs := CameraAttributesPractical.new()
	attrs.dof_blur_far_enabled = true
	attrs.dof_blur_far_distance = 6.0
	attrs.dof_blur_far_transition = 4.0
	attrs.dof_blur_amount = 0.06
	we.camera_attributes = attrs
	add_child(we)

func _box(size: Vector3, pos: Vector3, mat: Material) -> void:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	mi.material_override = mat
	mi.position = pos
	add_child(mi)

func _build_corridor() -> void:
	var wall := load("res://shaders/wallpaper.gdshader") as Shader
	var wm := ShaderMaterial.new(); wm.shader = wall
	var floor_m := ShaderMaterial.new(); floor_m.shader = load("res://shaders/floorboards.gdshader")
	var ceil_m := StandardMaterial3D.new(); ceil_m.albedo_color = Color(0.32, 0.30, 0.27); ceil_m.roughness = 0.95
	var trim := StandardMaterial3D.new(); trim.albedo_color = Color(0.16, 0.11, 0.08); trim.roughness = 0.5
	var w := 1.5; var h := 2.6; var z0 := -3.0; var z1 := 7.0
	var L := z1 - z0; var zc := (z0 + z1) * 0.5
	_box(Vector3(w, 0.05, L), Vector3(0, -0.025, zc), floor_m)
	_box(Vector3(w, 0.05, L), Vector3(0, h + 0.025, zc), ceil_m)
	_box(Vector3(0.05, h, L), Vector3(-w * 0.5 - 0.025, h * 0.5, zc), wm)
	_box(Vector3(0.05, h, L), Vector3(w * 0.5 + 0.025, h * 0.5, zc), wm)
	_box(Vector3(w, h, 0.05), Vector3(0, h * 0.5, z0 - 0.025), wm)
	# skirting boards and dado rail
	for sx in [-1.0, 1.0]:
		_box(Vector3(0.03, 0.14, L), Vector3(sx * (w * 0.5 - 0.015), 0.07, zc), trim)
		_box(Vector3(0.03, 0.04, L), Vector3(sx * (w * 0.5 - 0.015), 0.95, zc), trim)
	# a closed door on the right wall, just behind the figure
	_box(Vector3(0.06, 2.05, 0.85), Vector3(w * 0.5 - 0.03, 1.025, -1.4), trim)
	# bare bulb behind the creature, hanging on a cord
	bulb = OmniLight3D.new()
	bulb.light_color = Color(1.0, 0.72, 0.42)
	bulb.omni_range = 5.0
	bulb.omni_attenuation = 1.6
	bulb.shadow_enabled = true
	bulb.light_volumetric_fog_energy = 2.0
	bulb.position = Vector3(0.0, 2.25, -0.9)
	add_child(bulb)
	var glass := StandardMaterial3D.new()
	glass.emission_enabled = true; glass.emission = Color(1.0, 0.7, 0.4); glass.emission_energy_multiplier = 6.0
	var bs := MeshInstance3D.new(); var sm := SphereMesh.new(); sm.radius = 0.035; sm.height = 0.08
	bs.mesh = sm; bs.material_override = glass; bs.position = bulb.position; bs.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(bs)
	_box(Vector3(0.006, 0.33, 0.006), bulb.position + Vector3(0, 0.19, 0), trim)

func _build_host() -> void:
	var skin := ShaderMaterial.new(); skin.shader = load("res://shaders/host_skin.gdshader")
	var veil := ShaderMaterial.new(); veil.shader = load("res://shaders/host_veil.gdshader")
	var host := Node3D.new()
	host.name = "Host"
	add_child(host)
	for part in [["res://models/host_body.obj", skin], ["res://models/host_veil.obj", veil]]:
		var mi := MeshInstance3D.new()
		mi.mesh = load(part[0])
		mi.material_override = part[1]
		host.add_child(mi)
