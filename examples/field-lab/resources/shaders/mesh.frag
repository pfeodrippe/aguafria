#version 450
layout(location = 0) noperspective in vec2 screen;
layout(location = 1) in vec3 surfacePosition;
layout(location = 2) in vec3 surfaceNormal;
layout(location = 3) in vec3 surfaceTint;
layout(location = 4) in vec3 surfaceRest;
layout(location = 5) flat in int surface;
layout(push_constant) uniform SceneFrame {
  vec4 spheres[3];
  vec4 rotations[3];
  vec4 camera;
  vec4 viewport;
}
scene;

layout(location = 0) out vec4 color;
const float PI = 3.14159265359;
float sphere(vec3 ro, vec3 rd, int index) {
  vec3 ball = scene.spheres[index].xyz;
  float size = scene.spheres[index].w;
  vec3 oc = ro - ball;
  float b = dot(oc, rd), c = dot(oc, oc) - size * size, d = b * b - c;
  if (d < 0)
    return -1;
  float t = -b - sqrt(d);
  return t > 0.0001 ? t : -b + sqrt(d);
}
float nearestSphere(vec3 ro, vec3 rd, out int index) {
  float nearest = 1e10;
  index = -1;
  for (int i = 0; i < int(scene.viewport.z); ++i) {
    float distance = sphere(ro, rd, i);
    if (distance > 0.0001 && distance < nearest) {
      nearest = distance;
      index = i;
    }
  }
  return nearest;
}

vec3 environment(vec3 d) {
  vec3 c = mix(vec3(.035, .05, .073), vec3(.32, .40, .48),
               clamp(d.y * .5 + .5, 0, 1));
  c += vec3(2.6, 2.3, 1.9) * pow(max(0, dot(d, normalize(vec3(-3, 12, 4)))), 45);
  c += vec3(.6, .9, 1.3) * pow(max(0, dot(d, normalize(vec3(4, 3, -3)))), 65);
  return c;
}
vec3 inverseRotate(vec3 v, int index) {
  vec4 rotation = scene.rotations[index];
  vec3 q = -rotation.xyz;
  return v + 2 * cross(q, cross(q, v) + rotation.w * v);
}
vec3 brdf(vec3 n, vec3 v, vec3 l, vec3 albedo, float rough, float metal) {
  vec3 h = normalize(v + l);
  float nv = max(dot(n, v), .001), nl = max(dot(n, l), 0),
        nh = max(dot(n, h), 0), vh = max(dot(v, h), 0);
  float a = rough * rough, a2 = a * a, den = nh * nh * (a2 - 1) + 1;
  float D = a2 / (PI * den * den), k = (rough + 1) * (rough + 1) / 8;
  float G = (nv / (nv * (1 - k) + k)) * (nl / (nl * (1 - k) + k));
  vec3 F0 = mix(vec3(.04), albedo, metal), F = F0 + (1 - F0) * pow(1 - vh, 5);
  return ((1 - F) * (1 - metal) * albedo / PI +
          D * G * F / max(4 * nv * nl, .001)) *
         nl;
}
float visibility(vec3 p) {
  float total = 0;
  for (int i = 0; i < 8; i++) {
    float a = float(i) * 2.399963;
    vec3 light = vec3(-3, 12, 4) +
                 vec3(cos(a), 0, sin(a)) * .95 * sqrt((float(i) + .5) / 8);
    vec3 delta = light - p;
    int occluder;
    float hit = nearestSphere(p + vec3(0, .001, 0), normalize(delta), occluder);
    total += hit < 0 || hit > length(delta) ? 1 : 0;
  }
  return total / 8;
}
vec3 floorColor(vec3 p) {
  vec2 coord = p.xz;
  vec2 fw = max(fwidth(coord), vec2(.001));
  vec2 grid = abs(fract(coord - .5) - .5) / fw;
  float line = 1 - min(min(grid.x, grid.y), 1);
  vec2 fine = abs(fract(coord * 5 - .5) - .5) / (fw * 5);
  float minor = 1 - min(min(fine.x, fine.y), 1);
  float fade = exp(-.018 * dot(coord, coord));
  vec3 base =
      mix(vec3(.115, .15, .175), vec3(.25, .33, .36), line * .38 * fade);
  base += minor * .018 * fade;
  float ax = 1 - smoothstep(.012, .025, abs(p.z)),
        az = 1 - smoothstep(.012, .025, abs(p.x));
  base = mix(base, vec3(.40, .12, .09), ax * .55);
  base = mix(base, vec3(.09, .31, .27), az * .55);
  return base;
}
vec3 shadeBall(vec3 p, vec3 n, vec3 v, int index) {
  vec3 local = surface == 1 ? normalize(surfaceRest) : inverseRotate(n, index);
  float seam = min(abs(local.y - .35), min(abs(local.y + .35), abs(local.x)));
  float edge = max(fwidth(seam), .003);
  float stripe = 1 - smoothstep(.022 - edge, .022 + edge, seam);
  vec3 tint = index == 0
                  ? vec3(.78, .27, .045)
                  : (index == 1 ? vec3(.035, .48, .43) : vec3(.37, .14, .68));
  if (surface == 1) tint = surfaceTint;
  vec3 albedo = mix(tint, vec3(.035, .046, .052), stripe);
  float rough = mix(.26, .49, stripe);
  vec3 outc = brdf(n, v, normalize(vec3(-3, 12, 4) - p), albedo, rough, .18) *
              vec3(6.5, 5.6, 4.8);
  outc += brdf(n, v, normalize(vec3(4, 3, -3) - p), albedo, rough, .18) *
          vec3(2, 3, 4);
  vec3 r = reflect(-v, n);
  vec3 env = environment(r);
  if (r.y < -.001) {
    vec3 fp = p + r * (-p.y / r.y);
    env = floorColor(fp) * .5;
  }
  vec3 F = vec3(.055) + (1 - .055) * pow(1 - max(dot(n, v), 0), 5);
  outc += albedo * .22 * (.5 + .5 * n.y) + env * F * .7;
  return outc;
}
void main() {
  vec2 dimensions = scene.viewport.xy;
  vec3 orbit = scene.camera.xyz;
  vec2 pixel = (screen * .5 + .5) * dimensions;
  vec2 viewport = dimensions - vec2(530, 292);
  vec2 uv = (pixel - vec2(225, 66)) / viewport;
  if (any(lessThan(uv, vec2(0))) || any(greaterThan(uv, vec2(1)))) {
    color = vec4(.07, .085, .10, 1);
    return;
  }
  vec2 film = vec2((uv.x * 2 - 1) * viewport.x / viewport.y, 1 - uv.y * 2);
  vec3 target = vec3(0, 1.55, 0);
  for (int i = 0; i < int(scene.viewport.z); ++i)
    target.xz += scene.spheres[i].xz / scene.viewport.z;
  vec3 ro = target + orbit.z * vec3(sin(orbit.x) * cos(orbit.y), sin(orbit.y),
                                    cos(orbit.x) * cos(orbit.y));
  vec3 forward = normalize(target - ro),
       right = normalize(cross(forward, vec3(0, 1, 0))),
       up = cross(right, forward);
  vec3 rd = normalize(forward * 2.1 + right * film.x + up * film.y);
  int index;
  float ts = nearestSphere(ro, rd, index),
        tp = rd.y < -.0001 ? -ro.y / rd.y : 1e10;
  vec3 c;
  if (surface == 1) {
    c = shadeBall(surfacePosition, normalize(surfaceNormal),
                  normalize(ro - surfacePosition), 0);
  } else if (surface == 2) {
    // The CPU projects the actual deformed triangles from the key light.
    vec3 p = surfacePosition;
    c = floorColor(p) * .25;
    c = mix(c, vec3(.07, .105, .14), 1 - exp(-length(p - ro) * .014));
  } else if (scene.viewport.w < .5 && index >= 0 && ts < tp) {
    vec3 p = ro + ts * rd;
    c = shadeBall(p, normalize(p - scene.spheres[index].xyz), -rd, index);
  } else if (tp < 1e9) {
    vec3 p = ro + tp * rd, n = vec3(0, 1, 0), albedo = floorColor(p);
    float vis = scene.viewport.w < .5 ? visibility(p) : 1;
    c = albedo * (.25 + vis * .72);
    // Single analytic reflection of the sphere on the satin ground.
    vec3 rr = reflect(rd, n);
    int reflected;
    float tr = nearestSphere(p + vec3(0, .001, 0), rr, reflected);
    if (scene.viewport.w < .5 && reflected >= 0) {
      vec3 hit = p + rr * tr;
      vec3 reflection = shadeBall(
          hit, normalize(hit - scene.spheres[reflected].xyz), -rr, reflected);
      c = mix(c, reflection, .055 * exp(-tr * .12));
    }
    for (int i = 0; i < int(scene.viewport.z); ++i) {
      vec3 ball = scene.spheres[i].xyz;
      float size = scene.spheres[i].w;
      float ao =
          1 -
          .3 * exp(-dot(p.xz - ball.xz, p.xz - ball.xz) / (size * size * 1.7)) *
              exp(-max(0, ball.y - size) * 2);
      if (scene.viewport.w < .5) c *= ao;
    }
    c = mix(c, vec3(.07, .105, .14), 1 - exp(-tp * .014));
  } else
    c = environment(rd) * .38;
  // Filmic exposure and gamma for the shared UNORM swapchain.
  c *= 1.3;
  c = clamp((c * (2.51 * c + .03)) / (c * (2.43 * c + .59) + .14), 0, 1);
  c = pow(c, vec3(1 / 2.2));
  c *= 1 - .10 * dot(uv - .5, uv - .5);
  color = vec4(c, 1);
}
