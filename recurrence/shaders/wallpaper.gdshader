// Faded, water-damaged striped wallpaper above a dark wainscot.
shader_type spatial;
varying vec3 wp;
float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float vnoise(vec3 x) { vec3 i = floor(x); vec3 f = fract(x); f = f * f * (3.0 - 2.0 * f);
	return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x), mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
	           mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x), mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z); }
float fbm(vec3 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 5; i++) { s += a * vnoise(p); p *= 2.03; a *= 0.5; } return s; }
void vertex() { wp = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz; }
void fragment() {
	float u = wp.z + wp.x;
	float stripe = smoothstep(0.42, 0.45, abs(fract(u * 6.0) - 0.5));
	vec3 paper = mix(vec3(0.42, 0.40, 0.30), vec3(0.30, 0.31, 0.24), stripe);
	float damp = smoothstep(0.5, 0.75, fbm(wp * 1.6) + (1.0 - wp.y / 2.6) * 0.15);
	paper = mix(paper, vec3(0.20, 0.17, 0.11), damp * 0.7);
	float mould = smoothstep(0.62, 0.8, fbm(wp * 6.0 + 3.0)) * damp;
	paper = mix(paper, vec3(0.08, 0.09, 0.06), mould);
	vec3 wainscot = vec3(0.13, 0.09, 0.06) * (0.8 + 0.4 * fbm(wp * vec3(2.0, 30.0, 2.0)));
	ALBEDO = wp.y < 0.95 ? wainscot : paper * (0.85 + 0.3 * fbm(wp * 25.0));
	ROUGHNESS = wp.y < 0.95 ? 0.45 : 0.9;
}
