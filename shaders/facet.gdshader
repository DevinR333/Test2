// Flat-shaded, vertex coloured material used by most props.
// Normals come from screen-space derivatives so every triangle reads as a
// crisp facet regardless of how the mesh was indexed.
shader_type spatial;
render_mode diffuse_burley, specular_schlick_ggx;
#include "res://shaders/noise.gdshaderinc"

uniform vec4 tint : source_color = vec4(1.0);
uniform bool use_vertex_color = true;
uniform float roughness : hint_range(0.0, 1.0) = 0.9;
uniform float specular_amount : hint_range(0.0, 1.0) = 0.3;
// Vertex colour alpha drives gentle wind sway (0 = rigid).
uniform float sway_strength = 0.0;

void vertex() {
	if (sway_strength > 0.0) {
		vec3 wp = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
		float w = COLOR.a * sway_strength;
		VERTEX.x += sin(TIME * 1.6 + wp.x * 0.7 + wp.z * 0.4) * w;
		VERTEX.z += cos(TIME * 1.3 + wp.z * 0.6) * w * 0.6;
	}
}

void fragment() {
	NORMAL = facet_normal(VERTEX);
	vec3 base = tint.rgb;
	if (use_vertex_color) {
		base *= to_linear(COLOR.rgb);
	}
	ALBEDO = base;
	ROUGHNESS = roughness;
	SPECULAR = specular_amount;
}
