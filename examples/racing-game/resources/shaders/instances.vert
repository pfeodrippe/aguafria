#version 450

// Authored mesh uploaded once, shared by all cars. No CPU-expanded vertices.
layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;
layout(location = 2) in vec3 color;
layout(location = 3) in float tint_weight;
layout(location = 4) in vec4 rotation;
layout(location = 5) in vec4 translation_scale;
layout(location = 6) in vec4 pivot_shadow;
layout(location = 7) in vec4 tint_mode;

layout(push_constant) uniform Camera {
    vec4 position_zoom;
    vec4 angles; // cos(yaw), sin(yaw), cos(pitch), sin(pitch)
    vec4 fit;
} camera;

layout(location = 0) out vec3 vertex_color;
layout(location = 1) out vec3 world_normal;
layout(location = 2) out vec3 world_position;
layout(location = 3) out float roughness;
layout(location = 4) out vec3 view_direction;

vec3 rotate(vec3 v) {
    vec3 t = 2.0 * cross(rotation.xyz, v);
    return v + rotation.w * t + cross(rotation.xyz, t);
}

void main() {
    world_position = (rotate(position - pivot_shadow.xyz) + translation_scale.xyz)
                     * translation_scale.w;
    world_normal = rotate(normal);
    vertex_color = color * mix(vec3(1.0), tint_mode.rgb, tint_weight);
    roughness = dot(color, vec3(1.0)) < 0.30 ? 0.90 : 0.34;
    if (tint_mode.w > 0.5) {
        // Independent wheel/chassis poses also drive their planar shadows.
        if (world_normal.z <= 0.5) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            view_direction = vec3(0.0, 0.0, 1.0);
            return;
        }
        float height = max(0.0, world_position.z - pivot_shadow.w);
        world_position.xy += height * vec2(0.34642, 0.46189);
        world_position.z = pivot_shadow.w + 0.00006;
        vertex_color = vec3(0.035, 0.045, 0.060);
        roughness = -1.0;
    }
    vec3 p = world_position - camera.position_zoom.xyz;
    float rx = p.x * camera.angles.x - p.y * camera.angles.y;
    float ry = p.x * camera.angles.y + p.y * camera.angles.x;
    gl_Position = vec4(rx * camera.position_zoom.w * camera.fit.x,
        0.10 + (-ry * camera.angles.w - p.z * camera.angles.z)
               * camera.position_zoom.w * camera.fit.y,
        0.5 + 0.22 * (ry * camera.angles.z - p.z * camera.angles.w), 1.0);
    view_direction = vec3(-camera.angles.y * camera.angles.z,
                          -camera.angles.x * camera.angles.z, camera.angles.w);
}
