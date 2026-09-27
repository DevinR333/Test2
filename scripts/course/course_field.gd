class_name CourseField
extends RefCounted
## A LaneShape baked onto a regular grid: signed distance to the lane edge
## (negative inside) and floor height at every grid vertex. Mesh generation
## marches over this grid; terrain and prop placement sample it bilinearly.

const FAR := 50.0

var origin: Vector2
var cell: float
var nx: int
var nz: int
var dist := PackedFloat32Array()
var height := PackedFloat32Array()


func _init(shape: LaneShape, cell_size: float, margin: float) -> void:
	cell = cell_size
	var r := shape.bounds().grow(margin + cell_size)
	origin = r.position
	nx = ceili(r.size.x / cell) + 1
	nz = ceili(r.size.y / cell) + 1
	dist.resize(nx * nz)
	dist.fill(FAR)
	height.resize(nx * nz)
	height.fill(0.0)

	# Splat each path segment into the cells it can influence.
	for s in shape.segments:
		var a: Vector3 = s[0]
		var b: Vector3 = s[1]
		var reach: float = maxf(s[2], s[3]) * 0.5 + margin
		var i0 := maxi(0, floori((minf(a.x, b.x) - reach - origin.x) / cell))
		var i1 := mini(nx - 1, ceili((maxf(a.x, b.x) + reach - origin.x) / cell))
		var j0 := maxi(0, floori((minf(a.z, b.z) - reach - origin.y) / cell))
		var j1 := mini(nz - 1, ceili((maxf(a.z, b.z) + reach - origin.y) / cell))
		for j in range(j0, j1 + 1):
			var row := j * nx
			for i in range(i0, i1 + 1):
				var sample := LaneShape.segment_sample(s, origin + Vector2(i, j) * cell)
				if sample.x < dist[row + i]:
					dist[row + i] = sample.x
					height[row + i] = sample.y

	# Pads are few, so evaluate them everywhere and blend them in smoothly.
	for pad in shape.pads:
		var c: Vector3 = pad[0]
		var rad: float = pad[1]
		var c2 := Vector2(c.x, c.z)
		for j in nz:
			for i in nx:
				var k := j * nx + i
				var pd := (origin + Vector2(i, j) * cell).distance_to(c2) - rad
				if pd > margin and dist[k] >= FAR:
					continue
				var merged := Vector2(pd, c.y)
				if dist[k] < FAR:
					merged = shape.blend_union(Vector2(dist[k], height[k]), merged)
				dist[k] = merged.x
				height[k] = merged.y


func pos(i: int, j: int) -> Vector2:
	return origin + Vector2(i, j) * cell


## Bilinear sample. Returns Vector2(signed distance, floor height).
func sample(p: Vector2) -> Vector2:
	var fx := (p.x - origin.x) / cell
	var fz := (p.y - origin.y) / cell
	if fx < 0.0 or fz < 0.0 or fx >= nx - 1 or fz >= nz - 1:
		return Vector2(FAR, 0.0)
	var i := int(fx)
	var j := int(fz)
	var tx := fx - i
	var tz := fz - j
	var k := j * nx + i
	var d := lerpf(lerpf(dist[k], dist[k + 1], tx), lerpf(dist[k + nx], dist[k + nx + 1], tx), tz)
	var h := lerpf(lerpf(height[k], height[k + 1], tx), lerpf(height[k + nx], height[k + nx + 1], tx), tz)
	return Vector2(d, h)
