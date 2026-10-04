// Old varnished floorboards, scuffed down the middle of the hall.
shader_type spatial;
varying vec3 wp;
float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float vnoise(vec3 x) { vec3 i = floor(x); vec3 f = fract(x); f = f * f * (3.0 - 2.0 * f);
	return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x), mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
	           mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x), mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z); }
float fbm(vec3 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 5; i++) { s += a * vnoise(p); p *= 2.03; a *= 0.5; } return s; }
void vertex() { wp = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz; }
void fragment() {
	float board = floor(wp.x / 0.12);
	float seam = smoothstep(0.0, 0.01, abs(fract(wp.x / 0.12) - 0.5) * 0.12 - 0.054);
	float grain = fbm(vec3(wp.x * 40.0, board, wp.z * 2.0 + board * 3.1));
	vec3 col = vec3(0.20, 0.12, 0.07) * (0.7 + 0.6 * grain) * (0.85 + 0.3 * hash(vec3(board)));
	float wear = smoothstep(0.45, 0.0, abs(wp.x)) * fbm(wp * 3.0);
	col = mix(col, vec3(0.28, 0.22, 0.17), wear * 0.5);
	ALBEDO = col * mix(0.25, 1.0, seam);
	ROUGHNESS = mix(0.25, 0.7, wear + grain * 0.3);
}
