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
// Packet published only after all displayed bodies were serialized successfully.
layout(std430, set = 0, binding = 0) readonly buffer DisplayGeometry {
  uint geometryWords[];
};

bool hasDisplayGeometry() {
  return geometryWords.length() >= 16 && geometryWords[0] == 0x5049544fu
      && geometryWords[1] > 0u && geometryWords[1] <= 3u
      && geometryWords[7] <= uint(geometryWords.length());
}

vec3 geometryPoint(uint address) {
  return uintBitsToFloat(uvec3(geometryWords[address], geometryWords[address + 1u],
                               geometryWords[address + 2u]));
}

bool rayBox(vec3 origin, vec3 direction, float maximum, vec3 lower, vec3 upper) {
  float near = 0.0;
  float far = maximum;
  for (int axis = 0; axis < 3; ++axis) {
    // Explicitly handle both signed zeros; 0 * infinity would produce NaN.
    if (direction[axis] == 0.0) {
      if (origin[axis] < lower[axis] || origin[axis] > upper[axis]) return false;
    } else {
      float a = (lower[axis] - origin[axis]) / direction[axis];
      float b = (upper[axis] - origin[axis]) / direction[axis];
      float entry = min(a, b);
      float exit = max(a, b);
      // PBRT's conservative slab interval expansion, also for negative endpoints.
      near = max(near, entry - abs(entry) * 3.57628e-7);
      far = min(far, exit + abs(exit) * 3.57628e-7);
      if (near > far) return false;
    }
  }
  return true;
}

float edgeProduct(vec2 a, vec2 b) {
  // Compensated products in a symmetric expression: reversing a shared edge
  // negates its result. No optional shaderFloat64 device feature is required.
  precise float p = a.x * b.y;
  precise float q = a.y * b.x;
  precise float correction = fma(a.x, b.y, -p) - fma(a.y, b.x, -q);
  precise float difference = p - q;
  return difference + correction;
}

bool rayTriangle(vec3 origin, vec3 direction, float maximum, vec3 a, vec3 b, vec3 c) {
  // Ray-aligned permutation and shear, PBRT 4e section 6.5. Closed edges ensure
  // adjacent triangles agree at their boundary; either winding occludes light.
  int z = abs(direction.x) > abs(direction.y) ? 0 : 1;
  if (abs(direction.z) > abs(direction[z])) z = 2;
  int x = (z + 1) % 3;
  int y = (x + 1) % 3;
  a -= origin;
  b -= origin;
  c -= origin;
  vec2 shear = -vec2(direction[x], direction[y]) / direction[z];
  precise vec2 pa = vec2(a[x], a[y]) + shear * a[z];
  precise vec2 pb = vec2(b[x], b[y]) + shear * b[z];
  precise vec2 pc = vec2(c[x], c[y]) + shear * c[z];
  float e0 = edgeProduct(pb, pc);
  float e1 = edgeProduct(pc, pa);
  float e2 = edgeProduct(pa, pb);
  if (min(e0, min(e1, e2)) < 0.0 && max(e0, max(e1, e2)) > 0.0) return false;
  float determinant = e0 + e1 + e2;
  if (determinant == 0.0) return false;
  float scaled = (e0 * a[z] + e1 * b[z] + e2 * c[z]) / direction[z];
  return determinant > 0.0 ? scaled > 0.0 && scaled < maximum * determinant
                           : scaled < 0.0 && scaled > maximum * determinant;
}

// Return -1 on a malformed packet or traversal exhaustion, 0 clear, 1 occluded.
// Errors fail dark rather than silently claiming a clear visibility ray.
int bodyOcclusion(uint body, vec3 origin, vec3 direction, float maximum) {
  uint packet = geometryWords[4u + body];
  uint limit = geometryWords[7];
  if (packet < 16u || packet > limit || limit - packet < 4u) return -1;
  uint nodes = geometryWords[packet];
  uint points = geometryWords[packet + 1u];
  uint faces = geometryWords[packet + 2u];
  if (faces == 0u || faces > 80000u || points == 0u || points > 20000u
      || nodes != 2u * faces - 1u) return -1;
  if (4u + 8u * nodes + 4u * points + 4u * faces > limit - packet) return -1;
  uint nodeBase = packet + 4u;
  uint pointBase = nodeBase + 8u * nodes;
  uint faceBase = pointBase + 4u * points;
  uint stack[64];
  uint pending = 1u;
  stack[0] = 0u;
  for (uint visited = 0u; visited < nodes && pending > 0u; ++visited) {
    uint node = stack[--pending];
    if (node >= nodes) return -1;
    uint address = nodeBase + 8u * node;
    if (!rayBox(origin, direction, maximum, geometryPoint(address),
                 geometryPoint(address + 4u))) continue;
    uint left = geometryWords[address + 3u];
    uint right = geometryWords[address + 7u];
    if (left == 0xffffffffu) {
      if (right >= faces) return -1;
      uint triangle = faceBase + 4u * right;
      uvec3 indices = uvec3(geometryWords[triangle], geometryWords[triangle + 1u],
                            geometryWords[triangle + 2u]);
      if (any(greaterThanEqual(indices, uvec3(points)))) return -1;
      if (rayTriangle(origin, direction, maximum, geometryPoint(pointBase + 4u * indices.x),
                        geometryPoint(pointBase + 4u * indices.y),
                        geometryPoint(pointBase + 4u * indices.z))) return 1;
    } else {
      if (left <= node || right <= node || left >= nodes || right >= nodes
          || pending > 62u) return -1;
      stack[pending++] = right;
      stack[pending++] = left;
    }
  }
  return pending == 0u ? 0 : -1;
}

// The primary hit comes from raster interpolation, not PBRT's bounded ray hit.
// This scale-dependent guard is therefore a preview tolerance, not a proven
// intersection error bound. Use the triangle plane normal, never its smooth normal.
vec3 visibilityOrigin(vec3 position, vec3 normal, vec3 direction) {
  float scale = max(1.0, max(abs(position.x), max(abs(position.y), abs(position.z))));
  vec3 offset = normal * (32.0 * 1.1920928955078125e-7 * scale);
  if (dot(normal, direction) < 0.0) offset = -offset;
  return position + offset;
}

bool lightVisible(vec3 position, vec3 normal, vec3 destination) {
  if (!hasDisplayGeometry()) return true;
  vec3 origin = visibilityOrigin(position, normal, destination - position);
  vec3 delta = destination - origin;
  float distance = length(delta);
  if (distance <= 0.0) return false;
  vec3 direction = delta / distance;
  // The ground is part of the visibility scene, including ball self-shadow rays.
  if (direction.y < 0.0 && origin.y > 0.0 && -origin.y / direction.y < distance) return false;
  for (uint body = 0u; body < geometryWords[1]; ++body) {
    if (bodyOcclusion(body, origin, direction, distance) != 0) return false;
  }
  return true;
}

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

// Heitz GGX visible normals. Shared by environment and finite-emitter sampling.
vec3 visibleHalfVector(vec3 normal, vec3 view, float roughness, vec2 samplePoint) {
  vec3 axis = abs(normal.z) < .999 ? vec3(0, 0, 1) : vec3(1, 0, 0);
  vec3 tangent = normalize(cross(axis, normal));
  vec3 bitangent = cross(normal, tangent);
  vec3 localView = vec3(dot(tangent, view), dot(bitangent, view), dot(normal, view));
  float alpha = roughness * roughness;
  vec3 stretchedView = normalize(vec3(alpha * localView.xy, localView.z));
  float projectedLength2 = dot(stretchedView.xy, stretchedView.xy);
  vec3 diskTangent = projectedLength2 > 0.0
      ? vec3(-stretchedView.y, stretchedView.x, 0) / sqrt(projectedLength2)
      : vec3(1, 0, 0);
  vec3 diskBitangent = cross(stretchedView, diskTangent);
  float blend = .5 * (1.0 + stretchedView.z);
  float phi = 2.0 * PI * samplePoint.y;
  vec2 disk = sqrt(samplePoint.x) * vec2(cos(phi), sin(phi));
  disk.y = (1.0 - blend) * sqrt(max(0.0, 1.0 - disk.x * disk.x)) + blend * disk.y;
  vec3 projected = disk.x * diskTangent + disk.y * diskBitangent
                 + sqrt(max(0.0, 1.0 - dot(disk, disk))) * stretchedView;
  vec3 localHalf = normalize(vec3(alpha * projected.xy, max(0.0, projected.z)));
  return tangent * localHalf.x + bitangent * localHalf.y + normal * localHalf.z;
}

float visibleDirectionPdf(vec3 normal, vec3 view, vec3 light, float roughness) {
  float noV = dot(normal, view);
  if (noV <= 0.0 || dot(normal, light) <= 0.0) return 0.0;
  vec3 halfVector = normalize(view + light);
  float noH = max(dot(normal, halfVector), 0.0);
  float alpha2 = pow(roughness, 4.0);
  float denominator = noH * noH * (alpha2 - 1.0) + 1.0;
  float distribution = alpha2 / (PI * denominator * denominator);
  return distribution / (2.0 * (noV + sqrt(alpha2 + (1.0 - alpha2) * noV * noV)));
}

// Equal-count balance MIS between uniform disk area and GGX visible normals.
// Each contribution is f_r * NoL / (pdf_area + pdf_GGX). PBRT 4e, 2.2.3 / 12.4.
// Each technique tests its own sampled segment against the displayed triangles.
// Bounded viewport profile. The dense 128/256 profile caused Metal GPU hangs
// even in 64-pixel raster tiles. Offline quality must accumulate sample batches
// in floating-point storage; increasing these loops is not a safe quality knob.
const uint AREA_LIGHT_SAMPLES = 16u;

vec3 diskLight(vec3 position, vec3 normal, vec3 geometricNormal, vec3 view, vec3 albedo,
               float roughness, float metalness, vec3 center,
               vec3 emitterNormal, float radius, vec3 emittedRadiance) {
  if (dot(normal, view) <= 0.0) return vec3(0);
  vec3 axis = abs(emitterNormal.z) < .999 ? vec3(0, 0, 1) : vec3(1, 0, 0);
  vec3 tangent = normalize(cross(axis, emitterNormal));
  vec3 bitangent = cross(emitterNormal, tangent);
  float area = PI * radius * radius;
  vec3 radiance = vec3(0);

  for (uint sampleIndex = 0u; sampleIndex < AREA_LIGHT_SAMPLES; ++sampleIndex) {
    vec2 samplePoint = vec2((float(sampleIndex) + .5) / float(AREA_LIGHT_SAMPLES),
                           float(bitfieldReverse(sampleIndex)) * 2.3283064365386963e-10);
    float radial = radius * sqrt(samplePoint.x);
    float phi = 2.0 * PI * samplePoint.y;
    vec3 samplePosition = center + radial * (cos(phi) * tangent + sin(phi) * bitangent);
    vec3 delta = samplePosition - position;
    float distance2 = dot(delta, delta);
    if (distance2 > 0.0) {
      vec3 light = delta / sqrt(distance2);
      float emitterCosine = dot(emitterNormal, -light);
      if (emitterCosine > 0.0 && dot(normal, light) > 0.0
          && lightVisible(position, geometricNormal, samplePosition)) {
        float areaPdf = distance2 / (area * emitterCosine);
        float reflectionPdf = visibleDirectionPdf(normal, view, light, roughness);
        radiance += brdf(normal, view, light, albedo, roughness, metalness)
                  / (areaPdf + reflectionPdf);
      }
    }

    vec3 halfVector = visibleHalfVector(normal, view, roughness, samplePoint);
    vec3 reflected = reflect(-view, halfVector);
    float emitterCosine = dot(emitterNormal, -reflected);
    if (emitterCosine > 0.0 && dot(normal, reflected) > 0.0) {
      float distance = dot(position - center, emitterNormal) / emitterCosine;
      vec3 offset = position + distance * reflected - center;
      if (distance > 0.0 && dot(offset, offset) <= radius * radius
          && lightVisible(position, geometricNormal, center + offset)) {
        float areaPdf = distance * distance / (area * emitterCosine);
        float reflectionPdf = visibleDirectionPdf(normal, view, reflected, roughness);
        radiance += brdf(normal, view, reflected, albedo, roughness, metalness)
                  / (areaPdf + reflectionPdf);
      }
    }
  }
  return radiance * emittedRadiance / float(AREA_LIGHT_SAMPLES);
}

vec3 studioLight(vec3 position, vec3 normal, vec3 geometricNormal, vec3 view, vec3 albedo,
                 float roughness, vec3 center, float radius, vec3 referenceIrradiance) {
  // Aim at the world origin. For an on-axis receiver there, E = pi L R^2/(d^2+R^2).
  // This defines the preview light's radiance from an explicit reference irradiance;
  // the radiance remains constant as the shaded point moves.
  float radius2 = radius * radius;
  vec3 emission = referenceIrradiance * (dot(center, center) + radius2) / (PI * radius2);
  if (scene.viewport.w >= 2.0) {
    vec3 delta = center - position;
    float distance2 = dot(delta, delta);
    vec3 light = delta / sqrt(distance2);
    float cosine = max(dot(normalize(-center), -light), 0.0);
    float visible = lightVisible(position, geometricNormal, center) ? 1.0 : 0.0;
    return brdf(normal, view, light, albedo, roughness, .18)
         * emission * (PI * radius2 * cosine / distance2) * visible;
  }
  return diskLight(position, normal, geometricNormal, view, albedo, roughness, .18,
                   center, normalize(-center), radius, emission);
}

const uint GROUND_LIGHT_SAMPLES = 32u;

vec3 groundLight(vec3 position, vec3 center, float radius, vec3 referenceIrradiance) {
  vec3 emitterNormal = normalize(-center);
  vec3 tangent = normalize(cross(vec3(0, 0, 1), emitterNormal));
  vec3 bitangent = cross(emitterNormal, tangent);
  vec3 emission = referenceIrradiance * (dot(center, center) + radius * radius)
                / (PI * radius * radius);
  if (scene.viewport.w >= 2.0) {
    vec3 delta = center - position;
    float distance2 = dot(delta, delta);
    vec3 light = delta / sqrt(distance2);
    float cosine = max(light.y, 0.0) * max(dot(emitterNormal, -light), 0.0);
    float visible = lightVisible(position, vec3(0, 1, 0), center) ? 1.0 : 0.0;
    return emission * (PI * radius * radius * cosine / distance2) * visible;
  }
  float irradiance = 0.0;
  const uint samples = GROUND_LIGHT_SAMPLES;
  for (uint i = 0u; i < samples; ++i) {
    float radial = radius * sqrt((float(i) + .5) / float(samples));
    float phi = 2.0 * PI * float(bitfieldReverse(i)) * 2.3283064365386963e-10;
    vec3 destination = center + radial * (cos(phi) * tangent + sin(phi) * bitangent);
    vec3 delta = destination - position;
    float distance2 = dot(delta, delta);
    vec3 light = delta / sqrt(distance2);
    float cosine = max(light.y, 0.0) * max(dot(emitterNormal, -light), 0.0);
    if (cosine > 0.0 && lightVisible(position, vec3(0, 1, 0), destination))
      irradiance += cosine / distance2;
  }
  return emission * (PI * radius * radius * irradiance / float(samples));
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
// GGX visible-normal sampling, Heitz (2018), sections 3–4 and appendix B.
// PDF(L) = G1(V) * D(H) / (4 * NoV), giving weight F * G / G1(V).
// Keep the existing Schlick BRDF geometry term; the sampling PDF uses exact
// Smith G1, so these two geometry terms must not be cancelled as if identical.
const uint ENVIRONMENT_SAMPLES = 32u;

vec3 incidentEnvironment(vec3 direction) {
  // The viewport grid is a guide, not a physical reflective floor texture.
  // This distant ground hemisphere is a lighting approximation, not ray tracing.
  return direction.y < 0.0 ? vec3(.115, .15, .175) * .5
                           : environment(direction);
}

vec3 environmentSpecular(vec3 normal, vec3 view, float roughness) {
  float noV = dot(normal, view);
  if (noV <= 0.0) return vec3(0);
  float alpha = roughness * roughness;
  float alpha2 = alpha * alpha;
  float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
  // Algebraically cancel NoV to keep G_Schlick(V) / G1_Smith(V) finite
  // at grazing angles without changing the sampled view direction.
  float viewWeight = (noV + sqrt(alpha2 + (1.0 - alpha2) * noV * noV))
                   / (2.0 * (noV * (1.0 - k) + k));
  vec3 radiance = vec3(0);

  for (uint sampleIndex = 0u; sampleIndex < ENVIRONMENT_SAMPLES; ++sampleIndex) {
    vec2 samplePoint = vec2((float(sampleIndex) + .5) / float(ENVIRONMENT_SAMPLES),
                           float(bitfieldReverse(sampleIndex)) * 2.3283064365386963e-10);
    vec3 halfVector = visibleHalfVector(normal, view, roughness, samplePoint);
    float voH = dot(view, halfVector);
    vec3 light = 2.0 * voH * halfVector - view;
    float noL = dot(normal, light);
    if (voH > 0.0 && noL > 0.0) {
      float geometryL = noL / (noL * (1.0 - k) + k);
      float fresnel = .055 + .945 * pow(1.0 - clamp(voH, 0.0, 1.0), 5.0);
      float weight = fresnel * viewWeight * geometryL;
      radiance += incidentEnvironment(light) * weight;
    }
  }
  return radiance / float(ENVIRONMENT_SAMPLES);
}

vec3 shadeBall(vec3 p, vec3 n, vec3 geometricNormal, vec3 v, int index) {
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
  vec3 outc = studioLight(p, n, geometricNormal, v, albedo, rough, vec3(-3, 12, 4), 4.0,
                          vec3(6.5, 5.6, 4.8));
  outc += studioLight(p, n, geometricNormal, v, albedo, rough, vec3(4, 3, -3), 2.0, vec3(2, 3, 4));
  outc += albedo * .22 * (.5 + .5 * n.y);
  outc += scene.viewport.w >= 2.0
      ? environment(reflect(-v, n)) * .04
      : environmentSpecular(n, v, rough) * .7;
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
  vec3 target = vec3(0, scene.camera.w, 0);
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
    vec3 geometricNormal = normalize(cross(dFdx(surfacePosition), dFdy(surfacePosition)));
    c = shadeBall(surfacePosition, normalize(surfaceNormal), geometricNormal,
                  normalize(ro - surfacePosition), 0);
  } else if (surface == 2) {
    if (hasDisplayGeometry()) discard;
    // Retain the old projection only if geometry publication failed.
    vec3 p = surfacePosition;
    c = floorColor(p) * .25;
    c = mix(c, vec3(.07, .105, .14), 1 - exp(-length(p - ro) * .014));
  } else if (mod(scene.viewport.w, 2.0) < .5 && index >= 0 && ts < tp) {
    vec3 p = ro + ts * rd;
    vec3 normal = normalize(p - scene.spheres[index].xyz);
    c = shadeBall(p, normal, normal, -rd, index);
  } else if (tp < 1e9) {
    vec3 p = ro + tp * rd, n = vec3(0, 1, 0), albedo = floorColor(p);
    float vis = mod(scene.viewport.w, 2.0) < .5 ? visibility(p) : 1;
    c = hasDisplayGeometry()
        ? albedo * (.25 + (groundLight(p, vec3(-3, 12, 4), 4.0, vec3(6.5, 5.6, 4.8))
                          + groundLight(p, vec3(4, 3, -3), 2.0, vec3(2, 3, 4))) / PI)
        : albedo * (.25 + vis * .72);
    // Single analytic reflection of the sphere on the satin ground.
    vec3 rr = reflect(rd, n);
    int reflected;
    float tr = nearestSphere(p + vec3(0, .001, 0), rr, reflected);
    if (mod(scene.viewport.w, 2.0) < .5 && reflected >= 0) {
      vec3 hit = p + rr * tr;
      vec3 reflection = shadeBall(
          hit, normalize(hit - scene.spheres[reflected].xyz),
          normalize(hit - scene.spheres[reflected].xyz), -rr, reflected);
      c = mix(c, reflection, .055 * exp(-tr * .12));
    }
    for (int i = 0; i < int(scene.viewport.z); ++i) {
      vec3 ball = scene.spheres[i].xyz;
      float size = scene.spheres[i].w;
      float ao =
          1 -
          .3 * exp(-dot(p.xz - ball.xz, p.xz - ball.xz) / (size * size * 1.7)) *
              exp(-max(0, ball.y - size) * 2);
      if (mod(scene.viewport.w, 2.0) < .5) c *= ao;
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
