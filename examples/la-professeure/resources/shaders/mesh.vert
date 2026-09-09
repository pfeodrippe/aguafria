#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require
struct Vertex { float x,y,z,r,g,b,nx,ny,nz,wx,wy,wz,roughness,vx,vy,vz; };
layout(buffer_reference, scalar, buffer_reference_align=16) readonly buffer Vertices { Vertex v[]; };
layout(buffer_reference, scalar, buffer_reference_align=4) readonly buffer Pixels { uint p[]; };
layout(push_constant, scalar) uniform Root { Vertices vertices; Pixels pixels; vec2 light; float lighting; float reserved; } root;
layout(location=0) out vec3 color;
layout(location=1) out vec2 uv;
layout(location=2) out vec2 position;
layout(location=3) flat out float textured;
layout(location=4) flat out float lit;
void main() {
  Vertex v=root.vertices.v[gl_VertexIndex];
  gl_Position=vec4(v.x,v.y,0,1);
  color=vec3(v.r,v.g,v.b); uv=vec2(v.wx,v.wy);
  position=vec2(v.x,v.y)*0.5+0.5; textured=v.roughness; lit=v.nz;
}
