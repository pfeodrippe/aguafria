#version 450
layout(location = 0) in vec3 position;
layout(location = 1) in vec3 tint;
layout(location = 2) in vec3 normal;
layout(location = 3) in vec3 world;
layout(location = 4) in float depth;
layout(location = 5) in vec3 rest;
layout(location = 0) noperspective out vec2 screen;
layout(location = 1) out vec3 surfacePosition;
layout(location = 2) out vec3 surfaceNormal;
layout(location = 3) out vec3 surfaceTint;
layout(location = 4) out vec3 surfaceRest;
layout(location = 5) flat out int surface;
void main() {
  float w = depth > 0 ? depth : 1;
  gl_Position = vec4(position * w, w);
  screen = position.xy;
  surfacePosition = world;
  surfaceNormal = normal;
  surfaceTint = tint;
  surfaceRest = rest;
  surface = depth > 0 ? (tint.r < 0 ? 2 : 1) : 0;
}
