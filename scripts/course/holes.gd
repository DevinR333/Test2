class_name Holes
extends RefCounted
## The 18 holes of the Lagoon Links course, as data.
##
## Coordinates are metres. Path control points are Vector3(x, floor height, z);
## -Z is roughly "away from the tee". The first path point is the tee.
##
## Keys:
##   title, par
##   paths      Array of [points: Array[Vector3], widths: Array[float]]
##   pads       Array of [centre: Vector3, radius]      (round greens / wide areas)
##   cup        Vector2 (x, z)
##   ponds      Array of [centre: Vector2, radius]      (optional)
##   obstacles  Array of Dictionaries with "type" and "at" (Vector2) + options
##   decor      Array of Dictionaries with "type" and "at" (Vector2)
##   golden     true = golden-hour lighting (back nine)

const COUNT := 18


static func get_hole(n: int) -> Dictionary:
	var h: Dictionary
	match n:
		1: h = _hole_1()
		2: h = _hole_2()
		3: h = _hole_3()
		4: h = _hole_4()
		5: h = _hole_5()
		6: h = _hole_6()
		7: h = _hole_7()
		8: h = _hole_8()
		9: h = _hole_9()
		10: h = _hole_10()
		11: h = _hole_11()
		12: h = _hole_12()
		13: h = _hole_13()
		14: h = _hole_14()
		15: h = _hole_15()
		16: h = _hole_16()
		17: h = _hole_17()
		_: h = _hole_18()
	h["number"] = n
	h["golden"] = n > 9
	for key in ["pads", "ponds", "obstacles", "decor"]:
		if not h.has(key):
			h[key] = []
	return h


static func total_par() -> int:
	var p := 0
	for n in range(1, COUNT + 1):
		p += int(get_hole(n)["par"])
	return p


static func _w(n: int, width := 1.3) -> Array:
	var a: Array = []
	for i in n:
		a.append(width)
	return a


# --- Front nine: daytime ------------------------------------------------------

static func _hole_1() -> Dictionary:
	return {
		"title": "Lagoon Lane", "par": 3,
		"paths": [[[Vector3(0, 0.6, 0.35), Vector3(0, 0.6, -2.4), Vector3(0, 0, -4.6), Vector3(0, 0, -6.9),
			Vector3(0, 0, -7.6), Vector3(1.3, 0, -9.4), Vector3(3.8, 0, -10.0), Vector3(6.4, 0, -9.0)],
			[1.3, 1.3, 1.3, 1.3, 1.4, 1.5, 1.4, 1.5]]],
		"pads": [[Vector3(6.6, 0, -8.8), 1.55]],
		"cup": Vector2(6.9, -8.6),
		"ponds": [[Vector2(3.3, -6.2), 2.0]],
		"obstacles": [{"type": "windmill", "at": Vector2(0, -6.0), "yaw": 0.0}],
	}


static func _hole_2() -> Dictionary:
	return {
		"title": "Palm Bend", "par": 2,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -2.0), Vector3(1.5, 0, -3.8), Vector3(1.5, 0, -5.5),
			Vector3(0.3, 0, -7.2), Vector3(0.3, 0, -8.4)], _w(6)]],
		"pads": [[Vector3(0.3, 0, -8.6), 1.2]],
		"cup": Vector2(0.3, -8.8),
		"obstacles": [{"type": "bumper", "at": Vector2(0.8, -2.9)}, {"type": "bumper", "at": Vector2(0.9, -6.4)}],
	}


static func _hole_3() -> Dictionary:
	return {
		"title": "Coral Steps", "par": 3,
		"paths": [[[Vector3(0, 0.9, 0.3), Vector3(0, 0.9, -1.8), Vector3(0, 0.6, -2.8), Vector3(0, 0.6, -4.0),
			Vector3(0, 0.3, -5.0), Vector3(0, 0.3, -6.2), Vector3(0, 0, -7.2), Vector3(0, 0, -8.5)], _w(8)]],
		"pads": [[Vector3(0, 0, -8.8), 1.3]],
		"cup": Vector2(0.35, -9.0),
		"obstacles": [
			{"type": "slider", "at": Vector2(0, -3.4), "axis": Vector2(1, 0), "travel": 0.42, "speed": 1.3},
			{"type": "slider", "at": Vector2(0, -5.6), "axis": Vector2(1, 0), "travel": 0.42, "speed": 1.7, "phase": 1.5},
		],
	}


static func _hole_4() -> Dictionary:
	var main := [[Vector3(0, 0, 0.3), Vector3(0, 0, -2.0), Vector3(0, 0, -2.6)], [1.3, 1.3, 1.3]]
	var left := [[Vector3(0, 0, -2.0), Vector3(-1.4, 0, -3.6), Vector3(-1.4, 0, -5.4), Vector3(0, 0, -7.0), Vector3(0, 0, -8.4)], _w(5, 1.1)]
	var right := [[Vector3(0, 0, -2.0), Vector3(1.4, 0, -3.6), Vector3(1.4, 0, -5.4), Vector3(0, 0, -7.0)], _w(4, 1.1)]
	return {
		"title": "Twin Trails", "par": 3,
		"paths": [main, left, right],
		"pads": [[Vector3(0, 0, -8.8), 1.3]],
		"cup": Vector2(0, -9.1),
		"obstacles": [{"type": "bumper", "at": Vector2(0, -2.7), "radius": 0.16},
			{"type": "spinner", "at": Vector2(1.4, -4.5), "length": 1.0, "speed": 1.6}],
		"decor": [{"type": "rock", "at": Vector2(0, -4.5), "size": 0.55}],
	}


static func _hole_5() -> Dictionary:
	return {
		"title": "Volcano Rise", "par": 3,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -2.5), Vector3(0, 0.5, -4.5), Vector3(0, 0.5, -5.6),
			Vector3(0.8, 0.5, -7.2)], _w(5)]],
		"pads": [[Vector3(0.8, 0.5, -7.7), 1.4]],
		"cup": Vector2(0.8, -8.1),
		"obstacles": [{"type": "bumper", "at": Vector2(0.25, -7.5)}, {"type": "bumper", "at": Vector2(1.35, -7.5)}],
		"decor": [{"type": "volcano", "at": Vector2(4.2, -6.5)}],
	}


static func _hole_6() -> Dictionary:
	return {
		"title": "Windmill Isle", "par": 3,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -2.0), Vector3(1.5, 0, -3.5), Vector3(2.4, 0, -3.5),
			Vector3(3.6, 0, -3.5), Vector3(4.5, 0, -3.5), Vector3(6.0, 0, -2.0), Vector3(6.0, 0, -0.6)], _w(8)]],
		"pads": [[Vector3(6.0, 0, -0.3), 1.3]],
		"cup": Vector2(6.2, -0.1),
		"ponds": [[Vector2(3.0, -0.3), 1.4]],
		"obstacles": [{"type": "windmill", "at": Vector2(3.0, -3.5), "yaw": -PI / 2.0}],
	}


static func _hole_7() -> Dictionary:
	return {
		"title": "Tiki Pinball", "par": 2,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -7.0)], [2.0, 2.0]]],
		"pads": [[Vector3(0, 0, -7.3), 1.3]],
		"cup": Vector2(0, -7.6),
		"obstacles": [
			{"type": "bumper", "at": Vector2(-0.5, -2.5)}, {"type": "bumper", "at": Vector2(0.5, -2.5)},
			{"type": "bumper", "at": Vector2(0, -3.6)},
			{"type": "bumper", "at": Vector2(-0.6, -4.7)}, {"type": "bumper", "at": Vector2(0.6, -4.7)},
			{"type": "bumper", "at": Vector2(0, -5.8)},
		],
	}


static func _hole_8() -> Dictionary:
	return {
		"title": "Snake Pass", "par": 3,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -1.5), Vector3(1.6, 0, -3.0), Vector3(-0.4, 0, -4.8),
			Vector3(1.6, 0, -6.6), Vector3(0.4, 0, -8.2)], _w(6, 1.1)]],
		"pads": [[Vector3(0.4, 0, -8.5), 1.2]],
		"cup": Vector2(0.4, -8.8),
	}


static func _hole_9() -> Dictionary:
	return {
		"title": "Sunken Treasure", "par": 3,
		"paths": [[[Vector3(0, 0.6, 0.3), Vector3(0, 0.6, -1.5), Vector3(0, 0, -3.5), Vector3(0, 0, -4.5),
			Vector3(0, 0.3, -6.5), Vector3(0, 0.3, -7.5)], _w(6)]],
		"pads": [[Vector3(0, 0.3, -7.9), 1.3]],
		"cup": Vector2(0, -8.25),
		"obstacles": [{"type": "slider", "at": Vector2(0, -4.0), "axis": Vector2(1, 0), "travel": 0.42, "speed": 1.2}],
		"decor": [{"type": "chest", "at": Vector2(1.6, -4.0)}],
	}


# --- Back nine: golden hour ---------------------------------------------------

static func _hole_10() -> Dictionary:
	return {
		"title": "Sunset Strip", "par": 2,
		"paths": [[[Vector3(0, 0.4, 0.3), Vector3(0, 0.4, -1.5), Vector3(0, 0, -4.0), Vector3(0, 0, -6.5)], _w(4)]],
		"pads": [[Vector3(0, 0, -6.8), 1.2]],
		"cup": Vector2(0.3, -7.0),
		"obstacles": [{"type": "spinner", "at": Vector2(0, -4.9), "length": 1.15, "speed": 1.4}],
	}


static func _hole_11() -> Dictionary:
	return {
		"title": "Hook Harbor", "par": 3,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -4.0), Vector3(-1.0, 0, -5.8), Vector3(-3.0, 0, -6.2),
			Vector3(-4.6, 0, -5.0), Vector3(-4.8, 0, -3.0)], _w(6)]],
		"pads": [[Vector3(-4.8, 0, -2.7), 1.3]],
		"cup": Vector2(-4.8, -2.4),
		"ponds": [[Vector2(-2.3, -2.9), 0.9]],
		"obstacles": [{"type": "bumper", "at": Vector2(-2.0, -6.0)}],
		"decor": [{"type": "lighthouse", "at": Vector2(2.4, -6.0)}],
	}


static func _hole_12() -> Dictionary:
	return {
		"title": "Log Hump", "par": 3,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -1.8), Vector3(0, 0.5, -3.3), Vector3(0, 0.5, -4.3),
			Vector3(0, 0, -5.8), Vector3(0, 0, -7.0), Vector3(1.2, 0, -8.4)], _w(7)]],
		"pads": [[Vector3(1.3, 0, -8.6), 1.3]],
		"cup": Vector2(1.5, -8.8),
		"decor": [{"type": "log_tunnel", "at": Vector2(0, -3.8), "yaw": 0.0}],
	}


static func _hole_13() -> Dictionary:
	return {
		"title": "Lighthouse Loop", "par": 4,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -3.0), Vector3(1.5, 0, -5.0), Vector3(3.5, 0, -5.2),
			Vector3(5.0, 0, -3.8), Vector3(5.0, 0, -1.8), Vector3(3.8, 0, -0.5)], _w(7)]],
		"pads": [[Vector3(3.6, 0, -0.3), 1.2]],
		"cup": Vector2(3.5, -0.05),
		"obstacles": [{"type": "slider", "at": Vector2(5.0, -2.8), "axis": Vector2(1, 0), "travel": 0.42, "speed": 1.5}],
		"decor": [{"type": "lighthouse", "at": Vector2(2.5, -2.4)}],
	}


static func _hole_14() -> Dictionary:
	return {
		"title": "Stepping Stones", "par": 3,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -2.0), Vector3(0, 0, -4.0), Vector3(0, 0, -6.0), Vector3(0, 0, -8.0)],
			[1.3, 1.3, 0.8, 1.3, 0.9]]],
		"pads": [[Vector3(0, 0, -8.3), 1.2]],
		"cup": Vector2(0, -8.6),
		"ponds": [[Vector2(-2.6, -4.5), 1.6], [Vector2(2.6, -4.5), 1.6]],
		"obstacles": [{"type": "bumper", "at": Vector2(0, -6.0), "radius": 0.14}],
	}


static func _hole_15() -> Dictionary:
	return {
		"title": "Crab Canyon", "par": 3,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -8.0)], [1.2, 1.2]]],
		"pads": [[Vector3(0, 0, -8.3), 1.1]],
		"cup": Vector2(0, -8.6),
		"obstacles": [
			{"type": "slider", "at": Vector2(0, -2.5), "axis": Vector2(1, 0), "travel": 0.38, "speed": 1.2},
			{"type": "slider", "at": Vector2(0, -4.5), "axis": Vector2(1, 0), "travel": 0.38, "speed": 1.7, "phase": 2.0},
			{"type": "slider", "at": Vector2(0, -6.5), "axis": Vector2(1, 0), "travel": 0.38, "speed": 1.4, "phase": 4.0},
		],
	}


static func _hole_16() -> Dictionary:
	return {
		"title": "Spiral Shell", "par": 4,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -2.0), Vector3(-0.6, 0, -4.6), Vector3(-2.6, 0, -5.6),
			Vector3(-4.4, 0, -4.2), Vector3(-4.4, 0, -2.0), Vector3(-3.0, 0, -1.0), Vector3(-2.5, 0, -2.6)], _w(8)]],
		"pads": [[Vector3(-2.5, 0, -3.0), 0.9]],
		"cup": Vector2(-2.5, -3.2),
	}


static func _hole_17() -> Dictionary:
	return {
		"title": "Coconut Chute", "par": 3,
		"paths": [[[Vector3(0, 1.0, 0.3), Vector3(0, 1.0, -1.5), Vector3(0, 0, -5.5), Vector3(0, 0, -6.5),
			Vector3(1.0, 0, -8.0)], _w(5)]],
		"pads": [[Vector3(1.2, 0, -8.4), 1.4]],
		"cup": Vector2(1.4, -8.7),
		"obstacles": [{"type": "bumper", "at": Vector2(0.8, -7.9)}, {"type": "bumper", "at": Vector2(1.9, -8.1)},
			{"type": "bumper", "at": Vector2(1.2, -9.3)}],
	}


static func _hole_18() -> Dictionary:
	return {
		"title": "Grand Finale", "par": 4,
		"paths": [[[Vector3(0, 0, 0.3), Vector3(0, 0, -2.5), Vector3(0, 0.4, -4.0), Vector3(0, 0.4, -5.0),
			Vector3(0, 0.4, -7.5), Vector3(0, 0, -9.5), Vector3(0, 0, -10.3), Vector3(2.0, 0, -11.3),
			Vector3(4.0, 0, -10.8)], _w(9)]],
		"pads": [[Vector3(4.4, 0, -10.8), 1.5]],
		"cup": Vector2(4.8, -10.6),
		"obstacles": [
			{"type": "spinner", "at": Vector2(0, -1.3), "length": 1.1, "speed": 1.3},
			{"type": "windmill", "at": Vector2(0, -6.2), "yaw": 0.0},
			{"type": "bumper", "at": Vector2(4.1, -11.3)},
		],
		"decor": [{"type": "lighthouse", "at": Vector2(-2.6, -9.5)}],
	}
