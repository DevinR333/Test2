// Stone-block foundation walls under the course.
shader_type spatial;
render_mode diffuse_burley, specular_schlick_ggx;
#include "res://shaders/noise.gdshaderinc"

uniform vec3 stone_a : source_color = vec3(0.56, 0.58, 0.60);
uniform vec3 stone_b : source_color = vec3(0.68, 0.66, 0.62);
uniform vec3 mortar : source_color = vec3(0.30, 0.30, 0.30);
uniform float row_height = 0.13;
uniform float brick_length = 0.3;

varying vec3 world_pos;
varying vec3 world_normal;

void vertex() {
	world_pos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
	world_normal = normalize((MODEL_MATRIX * vec4(NORMAL, 0.0)).xyz);
}

void fragment() {
	NORMAL = facet_normal(VERTEX);
	vec2 t = normalize(vec2(-world_normal.z, world_normal.x) + vec2(1e-4));
	float u = dot(world_pos.xz, t);
	float row = floor(world_pos.y / row_height);
	float bu = u / brick_length + row * 0.5;
	vec2 cell = vec2(floor(bu), row);
	vec2 f = vec2(fract(bu), fract(world_pos.y / row_height));
	float edge = min(min(f.x, 1.0 - f.x) * brick_length, min(f.y, 1.0 - f.y) * row_height);
	float m = smoothstep(0.008, 0.016, edge);
	vec3 col = mix(stone_a, stone_b, hash21(cell));
	col *= 0.9 + 0.2 * vnoise(world_pos.xz * 9.0 + world_pos.y * 7.0);
	col = mix(mortar, col, m);
	ALBEDO = col * to_linear(COLOR.rgb);
	ROUGHNESS = 0.9;
	SPECULAR = 0.2;
}
