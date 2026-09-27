class_name Obstacles
extends RefCounted
## Creates the obstacles listed in a hole's data at the right floor height.


static func build(parent: Node3D, list: Array, field: CourseField) -> void:
	for o: Dictionary in list:
		var at: Vector2 = o["at"]
		var node: Node3D
		match o["type"]:
			"windmill":
				node = Windmill.new()
			"spinner":
				var sp := Spinner.new()
				sp.length = o.get("length", 1.1)
				sp.speed = o.get("speed", 1.4)
				node = sp
			"slider":
				var sl := SlidingBlock.new()
				var ax: Vector2 = o.get("axis", Vector2(1, 0))
				sl.axis = Vector3(ax.x, 0, ax.y).normalized()
				sl.travel = o.get("travel", 0.4)
				sl.speed = o.get("speed", 1.4)
				sl.phase = o.get("phase", 0.0)
				node = sl
			"bumper":
				node = bumper(o.get("radius", 0.12))
			_:
				push_warning("Unknown obstacle type %s" % o["type"])
				continue
		node.position = Vector3(at.x, field.sample(at).y, at.y)
		node.rotation.y = o.get("yaw", node.rotation.y)
		parent.add_child(node)


## Bouncy tiki drum post.
static func bumper(radius: float) -> StaticBody3D:
	var body := StaticBody3D.new()
	body.name = "Bumper"
	var pm := PhysicsMaterial.new()
	pm.bounce = 0.85
	pm.friction = 0.1
	body.physics_material_override = pm
	var st := MeshKit.begin()
	var teal := Color(0.18, 0.72, 0.72)
	var cream := Color(0.98, 0.93, 0.8)
	MeshKit.prism(st, Vector3.ZERO, radius, radius, 0.06, 10, teal)
	MeshKit.prism(st, Vector3(0, 0.06, 0), radius * 1.04, radius * 1.04, 0.04, 10, cream)
	MeshKit.prism(st, Vector3(0, 0.10, 0), radius, radius * 0.92, 0.07, 10, teal)
	MeshKit.prism(st, Vector3(0, 0.17, 0), radius * 0.92, radius * 0.3, 0.05, 10, Color(1.0, 0.72, 0.25))
	var mi := MeshInstance3D.new()
	mi.mesh = MeshKit.finish(st)
	mi.material_override = MeshKit.facet_material(0.5)
	body.add_child(mi)
	var cs := CollisionShape3D.new()
	var cyl := CylinderShape3D.new()
	cyl.radius = radius
	cyl.height = 0.22
	cs.shape = cyl
	cs.position.y = 0.11
	body.add_child(cs)
	return body
