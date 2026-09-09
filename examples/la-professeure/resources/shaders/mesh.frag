#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require
layout(buffer_reference, scalar, buffer_reference_align=16) readonly buffer Vertices { float unused[]; };
layout(buffer_reference, scalar, buffer_reference_align=4) readonly buffer Pixels { uint p[]; };
layout(push_constant, scalar) uniform Root { Vertices vertices; Pixels pixels; vec2 light; float lighting; float reserved; } root;
layout(location=0) in vec3 color;
layout(location=1) in vec2 uv;
layout(location=2) in vec2 position;
layout(location=3) flat in float textured;
layout(location=4) flat in float lit;
layout(location=0) out vec4 result;
vec4 pixel(ivec2 p) { p=clamp(p,ivec2(0),ivec2(2047,1535)); return unpackUnorm4x8(root.pixels.p[p.y*2048+p.x]); }
void main() {
  vec4 texel=vec4(1);
  if (textured>0.5) {
    vec2 p=uv-0.5; ivec2 base=ivec2(floor(p)); vec2 f=fract(p);
    texel=mix(mix(pixel(base),pixel(base+ivec2(1,0)),f.x),mix(pixel(base+ivec2(0,1)),pixel(base+ivec2(1,1)),f.x),f.y);
  }
  if (texel.a<0.005) discard;
  vec2 d=(position-root.light)*vec2(1100.0/760.0,1);
  float illumination=mix(1.0,0.80+0.35*exp(-12.0*dot(d,d)),lit*root.lighting);
  result=vec4(color*texel.rgb*illumination,texel.a);
}
