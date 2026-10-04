// Wet, bloodshot eyes with a pale, clouded iris and pinprick pupil, staring straight ahead (+Z).
shader_type spatial;
const vec3 EYE = vec3(0.031, 0.004, 0.066);
varying vec3 lp;
float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float vnoise(vec3 x) { vec3 i = floor(x); vec3 f = fract(x); f = f * f * (3.0 - 2.0 * f);
	return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x), mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
	           mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x), mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z); }
void vertex() { lp = VERTEX - vec3(sign(VERTEX.x) * EYE.x, EYE.y, EYE.z); }
void fragment() {
	vec3 d = normalize(lp);
	float a = acos(clamp(d.z, -1.0, 1.0));         // angle from the gaze direction
	float ang = atan(d.y, d.x);
	vec3 sclera = vec3(0.42, 0.38, 0.32);
	float vessels = smoothstep(0.82, 0.95, vnoise(vec3(ang * 9.0, a * 30.0, 0.0))) * smoothstep(0.35, 1.2, a);
	sclera = mix(sclera, vec3(0.6, 0.12, 0.1), vessels * 0.8);
	sclera = mix(sclera, vec3(0.62, 0.32, 0.28), smoothstep(0.7, 1.4, a) * 0.6);
	vec3 iris = vec3(0.30, 0.32, 0.32) * (0.8 + 0.3 * vnoise(vec3(ang * 14.0, a * 40.0, 1.0)));
	iris = mix(iris, vec3(0.30, 0.32, 0.33), smoothstep(0.30, 0.42, a));   // dark limbal ring
	vec3 col = mix(iris, sclera, smoothstep(0.42, 0.46, a));
	col = mix(vec3(0.02), col, smoothstep(0.06, 0.085, a));                 // pinprick pupil
	ALBEDO = col;
	ROUGHNESS = 0.03;
	SPECULAR = 0.6;
}
