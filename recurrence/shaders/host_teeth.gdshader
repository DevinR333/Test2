// Small, stained teeth.
shader_type spatial;
varying vec3 tp;
void vertex() { tp = VERTEX; }
void fragment() {
	float gum = smoothstep(0.004, 0.0, abs(abs(tp.y + 0.062) - 0.012));
	vec3 c = mix(vec3(0.62, 0.56, 0.42), vec3(0.32, 0.24, 0.18), smoothstep(0.012, 0.024, abs(tp.x)));
	ALBEDO = mix(c, vec3(0.35, 0.2, 0.22), gum * 0.6);
	ROUGHNESS = 0.25;
	SPECULAR = 0.6;
}
