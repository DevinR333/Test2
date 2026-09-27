// Faceted leaves that sway in the breeze and glow a little when backlit.
// Vertex colour alpha = how much a vertex sways.
shader_type spatial;
render_mode cull_disabled, diffuse_burley, specular_schlick_ggx;
#include "res://shaders/noise.gdshaderinc"

uniform float sway = 0.06;
uniform vec3 backlight_color : source_color = vec3(0.35, 0.45, 0.12);

void vertex() {
	vec3 origin = (MODEL_MATRIX * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
	float phase = hash21(origin.xz) * 6.28;
	float w = COLOR.a * sway;
	VERTEX.x += sin(TIME * 1.7 + phase) * w;
	VERTEX.z += cos(TIME * 1.3 + phase * 1.3) * w * 0.7;
	VERTEX.y += sin(TIME * 2.1 + phase) * w * 0.3;
}

void fragment() {
	NORMAL = facet_normal(VERTEX);
	vec3 origin = (MODEL_MATRIX * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
	ALBEDO = to_linear(COLOR.rgb) * (0.92 + 0.16 * hash21(origin.xz + 3.1));
	BACKLIGHT = backlight_color;
	ROUGHNESS = 0.8;
	SPECULAR = 0.2;
}
