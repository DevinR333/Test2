// Skin of the Host: ash-grey, waxy, mottled, with dark veins and wet sheen in the creases.
shader_type spatial;
render_mode diffuse_burley, specular_schlick_ggx;

uniform vec3 base_color : source_color = vec3(0.56, 0.54, 0.50);
uniform vec3 bruise_color : source_color = vec3(0.30, 0.26, 0.30);
uniform vec3 vein_color : source_color = vec3(0.16, 0.17, 0.22);
uniform vec3 sss_tint : source_color = vec3(0.55, 0.22, 0.18);

varying vec3 obj_pos;
varying vec3 obj_nrm;

float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float vnoise(vec3 x) {
	vec3 i = floor(x); vec3 f = fract(x); f = f * f * (3.0 - 2.0 * f);
	return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x), mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
	           mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x), mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}
float fbm(vec3 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 5; i++) { s += a * vnoise(p); p *= 2.03; a *= 0.5; } return s; }

void vertex() { obj_pos = VERTEX; obj_nrm = NORMAL; }

void fragment() {
	vec3 p = obj_pos;
	float mott = fbm(p * 9.0);
	float blotch = smoothstep(0.55, 0.75, fbm(p * 3.0 + 7.0));
	// branching veins: thin ridges of a warped noise
	float v = fbm(p * 14.0 + fbm(p * 4.0) * 2.0);
	float vein = 1.0 - smoothstep(0.0, 0.035, abs(v - 0.5));
	vein *= smoothstep(0.3, 0.7, fbm(p * 2.5 + 3.0));
	vec3 col = base_color * (0.78 + 0.35 * mott);
	col = mix(col, bruise_color, blotch * 0.6);
	col = mix(col, vein_color, vein * 0.65);
	// grime darkening toward the extremities (hands, feet) and in cavities
	float cavity = clamp(1.0 - fbm(p * 30.0) * 1.4, 0.0, 1.0);
	col *= mix(1.0, 0.7, cavity * 0.5);
	col *= mix(0.55, 1.0, smoothstep(0.1, 0.6, p.y));
	ALBEDO = col;
	// wet & waxy: low roughness patches
	ROUGHNESS = mix(0.28, 0.6, fbm(p * 6.0 + 11.0));
	SPECULAR = 0.55;
	SSS_STRENGTH = 0.35;
	BACKLIGHT = sss_tint * 0.25;
	// micro-bump from the noise
	float e = 0.003;
	float h0 = fbm(p * 60.0);
	vec3 g = vec3(fbm((p + vec3(e,0,0)) * 60.0) - h0, fbm((p + vec3(0,e,0)) * 60.0) - h0, fbm((p + vec3(0,0,e)) * 60.0) - h0) / e;
	NORMAL = normalize(NORMAL - (VIEW_MATRIX * vec4(g, 0.0)).xyz * 0.0025);
}
