// Waving pin flag; vertices further from the pole move more.
shader_type spatial;
render_mode cull_disabled, diffuse_burley;
#include "res://shaders/noise.gdshaderinc"

uniform vec3 flag_color : source_color = vec3(1.0, 0.36, 0.25);
uniform vec3 stripe_color : source_color = vec3(1.0, 0.92, 0.55);

void vertex() {
	float k = UV.x;
	VERTEX.z += sin(TIME * 5.0 - UV.x * 5.0) * 0.045 * k;
	VERTEX.y += sin(TIME * 3.0 - UV.x * 3.0) * 0.01 * k;
}

void fragment() {
	NORMAL = facet_normal(VERTEX);
	if (!FRONT_FACING) { NORMAL = -NORMAL; }
	float stripe = step(0.4, UV.y) * step(UV.y, 0.6);
	ALBEDO = mix(flag_color, stripe_color, stripe);
	ROUGHNESS = 0.9;
}
