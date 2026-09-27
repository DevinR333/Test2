class_name HoleLagoonLane
extends RefCounted
## Hole 1 layout. -Z is "forward" from the tee.
##
## Raised tee -> downhill ramp -> windmill tunnel -> dogleg right around a
## lily pond -> round green.

const NUMBER := 1
const TITLE := "Lagoon Lane"
const PAR := 3

const TEE := Vector3(0.0, 0.6, 0.2)
const CUP := Vector2(6.9, -8.6)
const WINDMILL := Vector3(0.0, 0.0, -6.0)
const POND := Vector2(3.3, -6.2)
const POND_RADIUS := 2.0
const ISLAND_CENTRE := Vector2(3.2, -4.8)
const ISLAND_RADIUS := 11.5

const PALMS := [
	Vector2(-1.9, -0.6), Vector2(1.9, 1.3), Vector2(-2.3, -8.2), Vector2(5.3, -5.0),
	Vector2(9.4, -6.6), Vector2(8.9, -11.6), Vector2(2.4, -12.2), Vector2(-1.6, 2.6),
]


static func shape() -> LaneShape:
	var s := LaneShape.new()
	s.add_path([
		Vector3(0.0, 0.6, 0.35),
		Vector3(0.0, 0.6, -2.4),
		Vector3(0.0, 0.0, -4.6),
		Vector3(0.0, 0.0, -6.9),
		Vector3(0.0, 0.0, -7.6),
		Vector3(1.3, 0.0, -9.4),
		Vector3(3.8, 0.0, -10.0),
		Vector3(6.4, 0.0, -9.0),
	], [1.3, 1.3, 1.3, 1.3, 1.4, 1.5, 1.4, 1.5])
	s.add_pad(Vector3(0.0, 0.6, 0.35), 0.9)
	s.add_pad(Vector3(6.6, 0.0, -8.8), 1.55)
	return s
