// Putting-green turf: mown stripes, fine felt speckle and baked edge AO
// (the AO lives in the vertex colour written by CourseBuilder).
shader_type spatial;
render_mode diffuse_burley, specular_schlick_ggx;
#include "res://shaders/noise.gdshaderinc"

uniform vec3 turf_color : source_color = vec3(0.33, 0.70, 0.25);
uniform vec3 stripe_color : source_color = vec3(0.40, 0.77, 0.30);
uniform float stripe_width = 0.5;
uniform vec2 stripe_dir = vec2(0.6, 0.8);

varying vec3 world_pos;

void vertex() {
	world_pos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
}

void fragment() {
	NORMAL = facet_normal(VERTEX);
	float s = dot(world_pos.xz, normalize(stripe_dir)) / stripe_width;
	float stripe = smoothstep(0.42, 0.58, abs(fract(s) - 0.5) * 2.0);
	vec3 col = mix(turf_color, stripe_color, stripe);
	float speckle = vnoise(world_pos.xz * 60.0) * 0.5 + vnoise(world_pos.xz * 13.0) * 0.5;
	col *= 0.92 + speckle * 0.14;
	ALBEDO = col * to_linear(COLOR.rgb);
	ROUGHNESS = 0.95;
	SPECULAR = 0.15;
}
