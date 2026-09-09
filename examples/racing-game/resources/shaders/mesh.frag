#version 450

layout(location = 0) in vec3 vertex_color;
layout(location = 1) in vec3 world_normal;
layout(location = 2) in vec3 world_position;
layout(location = 3) in float roughness;
layout(location = 4) in vec3 view_direction;
layout(location = 0) out vec4 out_color;

const float PI = 3.14159265359;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float surface_noise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(cell), hash21(cell + vec2(1, 0)), f.x),
               mix(hash21(cell + vec2(0, 1)), hash21(cell + vec2(1, 1)), f.x), f.y);
}

vec3 tonemap(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) /
                 (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    // HUD and projected shadow geometry are deliberately unlit.
    if (roughness < 0.0) {
        out_color = vec4(vertex_color, 1.0);
        return;
    }
    vec3 n = normalize(world_normal);
    vec3 v = normalize(view_direction);
    vec3 l = normalize(vec3(-0.30, -0.40, 0.866));
    vec3 h = normalize(l + v);
    float nl = max(dot(n, l), 0.0);
    float nv = max(dot(n, v), 0.001);
    float nh = max(dot(n, h), 0.0);
    float vh = max(dot(v, h), 0.0);
    float a = max(roughness * roughness, 0.025);
    float a2 = a * a;
    float den = nh * nh * (a2 - 1.0) + 1.0;
    float distribution = a2 / max(PI * den * den, 0.0001);
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float visibility = (nv / (nv * (1.0 - k) + k)) *
                       (nl / max(nl * (1.0 - k) + k, 0.001));
    vec3 fresnel = vec3(0.04) + vec3(0.96) * pow(1.0 - vh, 5.0);
    vec3 specular = distribution * visibility * fresnel / max(4.0 * nv * nl, 0.001);
    vec3 albedo = pow(max(vertex_color, vec3(0.0)), vec3(2.2));
    // World-metre detail: broad turf/asphalt variation remains readable at
    // broadcast distance; fine grain fades out instead of shimmering.
    // The current terrain palette identifies turf; voxel paint is unaffected.
    if (roughness > 0.85) {
        vec2 metres = world_position.xy * 1000.0;
        float footprint = max(length(fwidth(metres)), 0.001);
        float coarse = surface_noise(metres * 0.45);
        float grain = surface_noise(metres * 18.0);
        float detail = 1.0 - smoothstep(0.015, 0.12, footprint);
        bool turf = vertex_color.g > vertex_color.r * 1.35 &&
                    vertex_color.g > vertex_color.b * 1.35;
        if (turf) {
            float turf_patch = surface_noise(metres * 0.08);
            albedo *= mix(vec3(0.72, 0.82, 0.65), vec3(1.22, 1.16, 0.90), turf_patch);
            albedo *= 0.85 + 0.30 * coarse + (grain - 0.5) * 0.22 * detail;
        } else {
            albedo *= 0.94 + 0.12 * coarse + (grain - 0.5) * 0.22 * detail;
        }
    }
    vec3 sun = vec3(3.2, 2.95, 2.60);
    vec3 ambient = mix(vec3(0.13, 0.11, 0.08), vec3(0.40, 0.50, 0.68),
                       clamp(n.z * 0.5 + 0.5, 0.0, 1.0));
    vec3 radiance = albedo * ambient + ((1.0 - fresnel) * albedo / PI + specular) * sun * nl;
    // The Vulkan swapchain is UNORM; explicitly encode display gamma here.
    out_color = vec4(pow(tonemap(radiance * 1.25), vec3(1.0 / 2.2)), 1.0);
}
