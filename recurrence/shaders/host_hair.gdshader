// Wet, dark, clumped hair ribbons.
shader_type spatial;
render_mode cull_disabled;
void fragment() {
	float strand = 0.6 + 0.4 * sin(UV.x * 80.0);
	ALBEDO = vec3(0.035, 0.032, 0.03) * strand;
	ROUGHNESS = 0.18;
	SPECULAR = 0.7;
}
