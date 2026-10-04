// Wet, yellowed lace curtain: floral lace pattern cut out with alpha scissor, translucent when backlit.
shader_type spatial;
render_mode cull_disabled, diffuse_burley;

uniform vec3 lace_color : source_color = vec3(0.74, 0.68, 0.52);
uniform vec3 stain_color : source_color = vec3(0.32, 0.24, 0.14);

varying vec3 obj_pos;

float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float vnoise(vec3 x) {
	vec3 i = floor(x); vec3 f = fract(x); f = f * f * (3.0 - 2.0 * f);
	return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x), mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
	           mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x), mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}
float fbm(vec3 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 4; i++) { s += a * vnoise(p); p *= 2.07; a *= 0.5; } return s; }

void vertex() { obj_pos = VERTEX; }

void fragment() {
	vec3 p = obj_pos;
	// triplanar-ish 2D coordinates around the drape
	vec2 uv = vec2(atan(p.z - 0.02, p.x - 0.05) * 0.35, p.y) * 28.0;
	vec2 cell = fract(uv) - 0.5;
	vec2 id = floor(uv);
	// rosette: ring + petals, joined by a mesh net
	float r = length(cell);
	float a = atan(cell.y, cell.x);
	float petals = abs(r - (0.28 + 0.06 * cos(a * 6.0)));
	float ring = abs(r - 0.12);
	float net = min(abs(fract(uv.x + uv.y) - 0.5), abs(fract(uv.x - uv.y) - 0.5));
	float thread = min(min(petals, ring) - 0.035, net - 0.06);
	// torn holes and rot
	float tear = fbm(p * 7.0);
	float keep = step(thread, 0.0) * step(tear, 0.66);
	float solid_band = smoothstep(0.62, 0.6, fbm(p * 3.0 + 2.0)); // dense, soaked regions where it's opaque
	ALPHA = max(keep, solid_band * step(tear, 0.7));
	ALPHA_SCISSOR_THRESHOLD = 0.5;
	float stain = smoothstep(0.45, 0.8, fbm(p * 4.0 + 5.0));
	vec3 col = mix(lace_color, stain_color, stain * 0.8);
	col *= 0.75 + 0.4 * fbm(p * 20.0);
	// damp near the head: darker and glossier where it clings to the face
	float wet = smoothstep(1.75, 2.05, p.y);
	ALBEDO = col * mix(1.0, 0.6, wet);
	ROUGHNESS = mix(0.75, 0.25, wet);
	BACKLIGHT = vec3(0.55, 0.48, 0.32);
}
