// Sandstone rail caps: warm, faceted, lightly mottled.
shader_type spatial;
render_mode diffuse_burley, specular_schlick_ggx;
#include "res://shaders/noise.gdshaderinc"

uniform vec3 base_color : source_color = vec3(0.95, 0.85, 0.66);
uniform vec3 dark_color : source_color = vec3(0.80, 0.66, 0.48);

varying vec3 world_pos;

void vertex() {
	world_pos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
}

void fragment() {
	NORMAL = facet_normal(VERTEX);
	float n = vnoise(world_pos.xz * 4.0) * 0.6 + vnoise(world_pos.xz * 17.0) * 0.4;
	// Block seams every ~0.6 m along the rail read as separate stones.
	float seam = smoothstep(0.02, 0.0, abs(fract((world_pos.x + world_pos.z) * 1.6) - 0.5) - 0.48);
	vec3 col = mix(dark_color, base_color, smoothstep(0.25, 0.75, n));
	col *= 1.0 - seam * 0.25;
	ALBEDO = col * to_linear(COLOR.rgb);
	ROUGHNESS = 0.85;
	SPECULAR = 0.25;
}
