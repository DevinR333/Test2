extends Node3D
## Character mockup: the Host in a dim bedroom, washed in cold teal light, one warm lamp behind.
## Run with `-- --shots` to save docs/screenshots/host_face_*.png and quit.

const HEAD_POS := Vector3(0.04, 2.19, 0.14)

var cam: Camera3D

func _ready() -> void:
	_environment()
	_room()
	_host()
	cam = Camera3D.new()
	add_child(cam)
	if "--shots" in OS.get_cmdline_user_args():
		_shots()
	else:
		_frame(0)

const SHOTS := [
	# name, camera pos, look-at, fov
	["host_face_closeup", HEAD_POS + Vector3(-0.03, -0.02, 0.46), HEAD_POS + Vector3(0.0, -0.02, 0.0), 30.0],
]

func _frame(i: int) -> void:
	cam.position = SHOTS[i][1]
	cam.look_at(SHOTS[i][2])
	cam.fov = SHOTS[i][3]

func _shots() -> void:
	DirAccess.make_dir_recursive_absolute("res://docs/screenshots")
	for i in SHOTS.size():
		_frame(i)
		for f in 24:
			await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png("res://docs/screenshots/%s%s.png" % [SHOTS[i][0], OS.get_environment("DBG")])
		print("saved ", SHOTS[i][0])
	get_tree().quit()

func _environment() -> void:
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0, 0.01, 0.015)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.2, 0.5, 0.6)
	env.ambient_light_energy = 0.05
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.tonemap_exposure = 1.0
	env.ssao_enabled = true
	env.ssao_radius = 0.15
	env.ssao_intensity = 2.0
	env.glow_enabled = true
	env.glow_intensity = 0.6
	env.glow_hdr_threshold = 0.9
	env.volumetric_fog_enabled = true
	env.volumetric_fog_density = 0.02
	env.volumetric_fog_albedo = Color(0.6, 0.8, 0.85)
	env.adjustment_enabled = true
	env.adjustment_contrast = 1.1
	env.adjustment_saturation = 0.9
	var we := WorldEnvironment.new()
	we.environment = env
	var cam_attr := CameraAttributesPractical.new()
	cam_attr.dof_blur_far_enabled = true
	cam_attr.dof_blur_far_distance = 1.1
	cam_attr.dof_blur_far_transition = 1.5
	cam_attr.dof_blur_amount = 0.12
	we.camera_attributes = cam_attr
	add_child(we)

func _room() -> void:
	var wall := StandardMaterial3D.new()
	wall.albedo_color = Color(0.32, 0.30, 0.26); wall.roughness = 0.9
	for spec in [[Vector3(5, 3, 0.1), Vector3(0, 1.5, -1.6)], [Vector3(0.1, 3, 5), Vector3(-1.8, 1.5, 0.5)],
			[Vector3(5, 0.1, 5), Vector3(0, -0.05, 0.5)], [Vector3(5, 0.1, 5), Vector3(0, 3.0, 0.5)]]:
		var mi := MeshInstance3D.new(); var b := BoxMesh.new(); b.size = spec[0]
		mi.mesh = b; mi.position = spec[1]; mi.material_override = wall; add_child(mi)
	# picture frame on the back wall (just a dark rectangle catching the light)
	var frame := MeshInstance3D.new(); var fb := BoxMesh.new(); fb.size = Vector3(0.7, 0.5, 0.03)
	var fm := StandardMaterial3D.new(); fm.albedo_color = Color(0.12, 0.09, 0.06); fm.roughness = 0.4
	frame.mesh = fb; frame.material_override = fm; frame.position = Vector3(-0.6, 2.25, -1.53); add_child(frame)
	# warm table lamp behind, to the right: the only "normal" light in the room
	var lamp := Node3D.new(); lamp.position = Vector3(1.05, 0.0, -1.1); add_child(lamp)
	var shade := MeshInstance3D.new(); var cyl := CylinderMesh.new()
	cyl.top_radius = 0.13; cyl.bottom_radius = 0.22; cyl.height = 0.3
	var sm := StandardMaterial3D.new(); sm.albedo_color = Color(0.9, 0.6, 0.35)
	sm.emission_enabled = true; sm.emission = Color(1.0, 0.55, 0.25); sm.emission_energy_multiplier = 2.5
	shade.mesh = cyl; shade.material_override = sm; shade.position = Vector3(0, 1.25, 0); lamp.add_child(shade)
	var stand := MeshInstance3D.new(); var sc := CylinderMesh.new(); sc.top_radius = 0.02; sc.bottom_radius = 0.09; sc.height = 1.1
	stand.mesh = sc; stand.material_override = fm; stand.position = Vector3(0, 0.55, 0); lamp.add_child(stand)
	var warm := OmniLight3D.new(); warm.light_color = Color(1.0, 0.6, 0.3); warm.light_energy = 1.2
	warm.omni_range = 2.2; warm.position = Vector3(0, 1.2, 0); warm.shadow_enabled = true; lamp.add_child(warm)
	# cold teal key: low, from the front-left, as if from a window or screen
	var key := SpotLight3D.new()
	key.light_color = Color(0.35, 0.75, 0.9); key.light_energy = 2.2
	key.spot_range = 5.0; key.spot_angle = 30.0; key.shadow_enabled = true
	key.position = HEAD_POS + Vector3(-0.35, -0.35, 0.8)
	add_child(key); key.look_at(HEAD_POS)
	# faint teal fill from the right so the shadows aren't pure black
	var fill := OmniLight3D.new(); fill.light_color = Color(0.2, 0.55, 0.7); fill.light_energy = 0.15
	fill.omni_range = 3.0; fill.position = HEAD_POS + Vector3(0.9, 0.2, 0.6); add_child(fill)

func _mesh(path: String, shader: String, parent: Node3D) -> void:
	var mi := MeshInstance3D.new()
	mi.mesh = load(path)
	var m := ShaderMaterial.new(); m.shader = load(shader)
	mi.material_override = m
	parent.add_child(mi)

func _host() -> void:
	var host := Node3D.new(); host.name = "Host"; add_child(host)
	_mesh("res://models/host_body.obj", "res://shaders/host_skin.gdshader", host)
	var head := Node3D.new(); head.name = "Head"
	head.position = HEAD_POS
	head.rotation = Vector3(0.05, -0.05, 0.16)   # tilted, chin down, staring at you
	host.add_child(head)
	_mesh("res://models/host_head.obj", "res://shaders/host_face.gdshader", head)
	_mesh("res://models/host_eyes.obj", "res://shaders/host_eye.gdshader", head)
	_mesh("res://models/host_teeth.obj", "res://shaders/host_teeth.gdshader", head)
	_mesh("res://models/host_hair.obj", "res://shaders/host_hair.gdshader", head)
	if OS.get_environment("DBG") == "nohair":
		head.get_child(3).visible = false
	if OS.get_environment("DBG") == "plain":
		var m := StandardMaterial3D.new(); m.albedo_color = Color(0.7, 0.7, 0.7)
		head.get_child(0).material_override = m
