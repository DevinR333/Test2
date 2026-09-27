class_name LaneShape
extends RefCounted
## Describes the playable area of a hole as a set of wide "brush strokes"
## (a smoothed centre-line path with per-point widths) plus round pads.
##
## Control points are Vector3: x/z give the position, y gives the floor height,
## so ramps are just control points at different heights.

var segments: Array = []   # [a: Vector3, b: Vector3, width_a: float, width_b: float]
var pads: Array = []       # [centre: Vector3, radius: float]
var pad_blend := 0.6       # fillet radius where pads meet the path


## Adds a smooth path through `ctrl`. Heights and widths ease between control
## points so ramps start and end flat (no bump for the ball to catch on).
func add_path(ctrl: Array, widths: Array, step := 0.2) -> void:
	var pts: Array = []
	var ws: Array = []
	var n := ctrl.size()
	for i in n - 1:
		var p0: Vector3 = ctrl[maxi(i - 1, 0)]
		var p1: Vector3 = ctrl[i]
		var p2: Vector3 = ctrl[i + 1]
		var p3: Vector3 = ctrl[mini(i + 2, n - 1)]
		var count := maxi(1, ceili(Vector2(p1.x, p1.z).distance_to(Vector2(p2.x, p2.z)) / step))
		for s in count:
			var t := float(s) / count
			var flat := p1.cubic_interpolate(p2, p0, p3, t)
			flat.y = lerpf(p1.y, p2.y, smoothstep(0.0, 1.0, t))
			pts.append(flat)
			ws.append(lerpf(widths[i], widths[i + 1], smoothstep(0.0, 1.0, t)))
	pts.append(ctrl[n - 1])
	ws.append(widths[n - 1])
	for i in pts.size() - 1:
		segments.append([pts[i], pts[i + 1], ws[i], ws[i + 1]])


func add_pad(centre: Vector3, radius: float) -> void:
	pads.append([centre, radius])


## Bounding rectangle (x, z) of the playable area.
func bounds() -> Rect2:
	var r := Rect2()
	var first := true
	for s in segments:
		for k in 2:
			var p: Vector3 = s[k]
			var half: float = s[2 + k] * 0.5
			var pr := Rect2(p.x - half, p.z - half, half * 2.0, half * 2.0)
			r = pr if first else r.merge(pr)
			first = false
	for pad in pads:
		var c: Vector3 = pad[0]
		var rad: float = pad[1]
		var pr := Rect2(c.x - rad, c.z - rad, rad * 2.0, rad * 2.0)
		r = pr if first else r.merge(pr)
		first = false
	return r


## Distance from p to segment s. Returns Vector2(signed distance, height).
static func segment_sample(s: Array, p: Vector2) -> Vector2:
	var a: Vector3 = s[0]
	var b: Vector3 = s[1]
	var a2 := Vector2(a.x, a.z)
	var ab := Vector2(b.x, b.z) - a2
	var len2 := ab.length_squared()
	var t := 0.0 if len2 < 1e-8 else clampf((p - a2).dot(ab) / len2, 0.0, 1.0)
	var d := p.distance_to(a2 + ab * t) - lerpf(s[2], s[3], t) * 0.5
	return Vector2(d, lerpf(a.y, b.y, t))


## Polynomial smooth-min of two (distance, height) samples.
func blend_union(x: Vector2, y: Vector2) -> Vector2:
	var k := pad_blend
	var m := clampf(0.5 + 0.5 * (y.x - x.x) / k, 0.0, 1.0)
	return Vector2(lerpf(y.x, x.x, m) - k * m * (1.0 - m), lerpf(y.y, x.y, m))
