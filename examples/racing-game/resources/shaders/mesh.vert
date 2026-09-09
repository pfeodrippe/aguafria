#version 450

layout(location = 0) in vec3 in_position;
layout(location = 1) in vec3 in_color;
layout(location = 2) in vec3 in_normal;
layout(location = 3) in vec3 in_world;
layout(location = 4) in float in_roughness;
layout(location = 5) in vec3 in_view_direction;

layout(location = 0) out vec3 vertex_color;
layout(location = 1) out vec3 world_normal;
layout(location = 2) out vec3 world_position;
layout(location = 3) out float roughness;
layout(location = 4) out vec3 view_direction;

void main() {
    gl_Position = vec4(in_position, 1.0);
    vertex_color = in_color;
    world_normal = in_normal;
    world_position = in_world;
    roughness = in_roughness;
    view_direction = in_view_direction;
}
