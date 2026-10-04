// The Host's face: dead grey skin, raw red eye rims, glossy tear tracks, bloodless lips.
// Works in head-local coordinates (see tools/sculpt_face.py).
shader_type spatial;
render_mode diffuse_burley;

uniform vec3 skin : source_color = vec3(0.60, 0.58, 0.55);
uniform vec3 rim_red : source_color = vec3(0.55, 0.16, 0.14);
uniform vec3 lip_col : source_color = vec3(0.36, 0.30, 0.34);
uniform vec3 vein_col : source_color = vec3(0.22, 0.25, 0.32);
const vec3 EYE = vec3(0.031, 0.004, 0.066);

varying vec3 hp;

float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float vnoise(vec3 x) { vec3 i = floor(x); vec3 f = fract(x); f = f * f * (3.0 - 2.0 * f);
	return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x), mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
	           mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x), mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z); }
float fbm(vec3 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 5; i++) { s += a * vnoise(p); p *= 2.03; a *= 0.5; } return s; }

void vertex() { hp = VERTEX; }

void fragment() {
	vec3 p = hp;
	float ax = abs(p.x);
	float front = smoothstep(0.03, 0.07, p.z);
	// base skin: mottled, blotchy, with veins at the temples and neck
	vec3 col = skin * (0.75 + 0.35 * fbm(p * 60.0));
	col = mix(col, vec3(0.42, 0.38, 0.40), smoothstep(0.55, 0.8, fbm(p * 18.0 + 4.0)) * 0.6);
	float v = fbm(p * 90.0 + fbm(p * 25.0) * 2.0);
	float vein = (1.0 - smoothstep(0.0, 0.03, abs(v - 0.5))) * smoothstep(0.45, 0.75, fbm(p * 12.0 + 9.0));
	col = mix(col, vein_col, vein * 0.55);
	// eye rims: raw, swollen red, spreading under the eyes
	vec2 de = vec2(ax - EYE.x, (p.y - EYE.y) * 1.3);
	float rim = smoothstep(0.026, 0.013, length(de + vec2(0.0, 0.004))) * front;
	col = mix(col, rim_red, rim * 0.85);
	// dark hollows under the eyes
	float hollow = smoothstep(0.03, 0.01, length(vec2(ax - EYE.x, p.y - EYE.y + 0.024))) * front;
	col *= 1.0 - hollow * 0.35;
	// lips: bloodless, bluish
	float lips = smoothstep(0.022, 0.008, length(vec2(p.x * 0.55, p.y + 0.062))) * front;
	col = mix(col, lip_col, lips * 0.8);
	// tear tracks: wavering lines from the inner/outer corners of each eye down to the jaw
	float tear = 0.0;
	for (int k = 0; k < 2; k++) {
		float x0 = EYE.x + (k == 0 ? -0.006 : 0.008);
		float wob = 0.003 * sin(p.y * 160.0 + float(k) * 2.0) + 0.002 * sin(p.y * 410.0);
		float drift = (EYE.y - p.y) * (k == 0 ? -0.10 : 0.18);
		float w = 0.0030 + 0.0015 * fbm(p * 200.0);
		float line = 1.0 - smoothstep(w * 0.6, w, abs(ax - x0 - wob - drift));
		line *= smoothstep(EYE.y - 0.008, EYE.y - 0.016, p.y) * smoothstep(-0.12, -0.07, p.y);
		tear = max(tear, line);
	}
	tear = max(tear, rim * 0.8); // eyes swimming
	tear *= front;
	col *= 1.0 - tear * 0.3;
	ALBEDO = col;
	float sheen = 0.55 - 0.15 * fbm(p * 40.0);       // clammy skin
	ROUGHNESS = mix(sheen, 0.03, tear);
	SPECULAR = mix(0.5, 0.9, tear);
	SSS_STRENGTH = 0.3;
	// pores / fine creases
	float e = 0.0006; float h0 = fbm(p * 900.0);
	vec3 g = vec3(fbm((p + vec3(e,0,0)) * 900.0) - h0, fbm((p + vec3(0,e,0)) * 900.0) - h0, fbm((p + vec3(0,0,e)) * 900.0) - h0) / e;
	NORMAL = normalize(NORMAL - (VIEW_MATRIX * vec4(g, 0.0)).xyz * 0.00012 * (1.0 - tear));
}
