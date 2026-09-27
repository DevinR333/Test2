// Stylised lagoon water: depth-tinted turquoise, faceted ripples and a
// cartoon foam line where the water meets land.
shader_type spatial;
render_mode blend_mix, depth_draw_always, cull_back, diffuse_burley, specular_schlick_ggx;
#include "res://shaders/noise.gdshaderinc"

uniform vec3 shallow_color : source_color = vec3(0.28, 0.86, 0.80);
uniform vec3 deep_color : source_color = vec3(0.05, 0.38, 0.62);
uniform vec3 foam_color : source_color = vec3(0.97, 1.0, 1.0);
uniform float depth_range = 2.0;
uniform float foam_range = 0.35;
uniform float wave_height = 0.035;
uniform sampler2D depth_tex : hint_depth_texture, filter_linear_mipmap;

varying vec3 world_pos;

float waves(vec2 p, float t) {
	return sin(p.x * 1.3 + t * 1.1) * 0.5 + sin(p.y * 1.7 - t * 0.9) * 0.3 + sin((p.x + p.y) * 2.3 + t * 1.7) * 0.2;
}

void vertex() {
	world_pos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
	VERTEX.y += waves(world_pos.xz, TIME) * wave_height;
	world_pos.y = (MODEL_MATRIX * vec4(VERTEX, 1.0)).y;
}

void fragment() {
	NORMAL = facet_normal(VERTEX);
	float depth = textureLod(depth_tex, SCREEN_UV, 0.0).r;
	vec4 view = INV_PROJECTION_MATRIX * vec4(SCREEN_UV * 2.0 - 1.0, depth, 1.0);
	view.xyz /= view.w;
	float thickness = max(-view.z - (-VERTEX.z), 0.0);

	float t = smoothstep(0.0, depth_range, thickness);
	vec3 col = mix(shallow_color, deep_color, t);

	float n = vnoise(world_pos.xz * 2.5 + TIME * vec2(0.20, 0.13));
	float edge = 1.0 - smoothstep(0.0, foam_range, thickness);
	float foam = step(0.62, edge + (n - 0.5) * 0.35);
	// A second, pulsing foam ring a little further out.
	float ring_pos = foam_range * (1.25 + 0.35 * sin(TIME * 1.3));
	float ring = 1.0 - smoothstep(0.0, 0.05, abs(thickness - ring_pos));
	foam = max(foam, step(0.55, ring * n + ring * 0.3) * (1.0 - t));

	ALBEDO = mix(col, foam_color, foam);
	ALPHA = mix(mix(0.7, 0.96, t), 1.0, foam);
	ROUGHNESS = mix(0.06, 0.8, foam);
	SPECULAR = 0.6;
}
