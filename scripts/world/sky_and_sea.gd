class_name SkyAndSea
extends RefCounted
## Lighting, sky, fog, the lagoon water, drifting clouds and distant islands.

const WATER_LEVEL := -0.55


## golden = warm late-afternoon light (used on the back nine).
static func build(parent: Node3D, focus: Vector3, golden := false) -> void:
	var sky_mat := ProceduralSkyMaterial.new()
	var horizon := Color(1.0, 0.76, 0.56) if golden else Color(0.72, 0.87, 0.97)
	sky_mat.sky_top_color = Color(0.3, 0.42, 0.78) if golden else Color(0.25, 0.55, 0.93)
	sky_mat.sky_horizon_color = horizon
	sky_mat.sky_curve = 0.12
	sky_mat.ground_horizon_color = horizon
	sky_mat.ground_bottom_color = Color(0.16, 0.45, 0.62)
	sky_mat.sun_angle_max = 25.0
	sky_mat.sun_curve = 0.1
	var sky := Sky.new()
	sky.sky_material = sky_mat

	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 1.0
	env.reflected_light_source = Environment.REFLECTION_SOURCE_SKY
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.tonemap_exposure = 1.05
	env.tonemap_white = 6.0
	env.glow_enabled = true
	env.glow_intensity = 0.35
	env.glow_bloom = 0.04
	env.glow_hdr_threshold = 1.1
	env.fog_enabled = true
	env.fog_light_color = horizon
	env.fog_density = 0.006
	env.fog_sky_affect = 0.0
	env.fog_aerial_perspective = 0.3
	env.adjustment_enabled = true
	env.adjustment_saturation = 1.15
	env.adjustment_contrast = 1.04
	var we := WorldEnvironment.new()
	we.environment = env
	parent.add_child(we)

	var sun := DirectionalLight3D.new()
	sun.name = "Sun"
	sun.light_color = Color(1.0, 0.78, 0.55) if golden else Color(1.0, 0.95, 0.85)
	sun.light_energy = 1.45 if golden else 1.35
	sun.shadow_enabled = true
	sun.shadow_bias = 0.02
	sun.shadow_normal_bias = 0.8
	sun.shadow_blur = 1.5
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_PARALLEL_2_SPLITS
	sun.directional_shadow_max_distance = 30.0
	sun.rotation = Vector3(deg_to_rad(-28.0 if golden else -52.0), deg_to_rad(-35.0), 0.0)
	parent.add_child(sun)

	# Faceted water: a subdivided plane whose vertices bob in the shader.
	var plane := PlaneMesh.new()
	plane.size = Vector2(90, 90)
	plane.subdivide_width = 90
	plane.subdivide_depth = 90
	var water := MeshInstance3D.new()
	water.name = "Water"
	water.mesh = plane
	water.material_override = MeshKit.shader_material("water")
	water.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	water.position = Vector3(focus.x, WATER_LEVEL, focus.z)
	parent.add_child(water)
	# Cheap flat ocean out to the horizon.
	var far_plane := PlaneMesh.new()
	far_plane.size = Vector2(900, 900)
	var ocean := MeshInstance3D.new()
	ocean.mesh = far_plane
	var ocean_mat := StandardMaterial3D.new()
	ocean_mat.albedo_color = Color(0.07, 0.42, 0.64)
	ocean_mat.roughness = 0.15
	ocean.material_override = ocean_mat
	ocean.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	ocean.position = Vector3(focus.x, WATER_LEVEL - 0.08, focus.z)
	parent.add_child(ocean)

	_clouds(parent, focus)
	_far_islands(parent, focus)


static func _clouds(parent: Node3D, focus: Vector3) -> void:
	var rng := RandomNumberGenerator.new()
	rng.seed = 7
	var mat := MeshKit.facet_material(1.0)
	var clouds := Node3D.new()
	clouds.name = "Clouds"
	clouds.set_script(load("res://scripts/world/drift.gd"))
	clouds.position = focus
	parent.add_child(clouds)
	for c in 11:
		var ang := rng.randf() * TAU
		var dist := rng.randf_range(35.0, 75.0)
		var cloud := Node3D.new()
		cloud.position = Vector3(cos(ang) * dist, rng.randf_range(11.0, 20.0), sin(ang) * dist)
		clouds.add_child(cloud)
		var puffs := rng.randi_range(3, 5)
		for p in puffs:
			var r := rng.randf_range(2.0, 3.6) * (1.2 if p == 1 else 1.0)
			var mi := MeshInstance3D.new()
			mi.mesh = MeshKit.blob(Vector3(r, r * 0.7, r), Color(1, 1, 1), Color(0.78, 0.84, 0.93), rng.randi(), 0.12, 4, 7)
			mi.material_override = mat
			mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
			mi.position = Vector3((p - puffs * 0.5) * 2.6, rng.randf_range(-0.3, 0.8), rng.randf_range(-1.0, 1.0))
			cloud.add_child(mi)


static func _far_islands(parent: Node3D, focus: Vector3) -> void:
	var rng := RandomNumberGenerator.new()
	rng.seed = 21
	var mat := MeshKit.facet_material(0.95)
	var spots := [Vector2(-38, -55), Vector2(55, -40), Vector2(-60, 20), Vector2(30, 60)]
	for s: Vector2 in spots:
		var base := focus + Vector3(s.x, WATER_LEVEL, s.y)
		var size := rng.randf_range(6.0, 11.0)
		var hill := MeshInstance3D.new()
		hill.mesh = MeshKit.blob(Vector3(size, size * 0.45, size * 0.8), Color(0.36, 0.66, 0.28), Color(0.93, 0.84, 0.6), rng.randi(), 0.18, 5, 9)
		hill.material_override = mat
		hill.position = base
		hill.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		parent.add_child(hill)
		for p in rng.randi_range(2, 4):
			var palm := Props.palm(rng.randi(), rng.randf_range(3.0, 4.5))
			palm.position = base + Vector3(rng.randf_range(-0.4, 0.4) * size, size * 0.3, rng.randf_range(-0.3, 0.3) * size)
			palm.scale = Vector3.ONE * 1.6
			parent.add_child(palm)
