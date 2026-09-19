"""Independent rendering references for tools/verify.py.

Lighting integration uses NumPy quadrature. Visibility uses a float64 exhaustive
oracle and generated GPU diagnostics; checking captures additionally needs Pillow.
CPU formula agreement alone is not a rendered-image or GPU validation.
"""

import argparse
import hashlib
import json
from pathlib import Path
import numpy as np


def geometry(no_v, no_l, roughness):
    k = (roughness + 1.0) ** 2 / 8.0
    return (no_v / (no_v * (1.0 - k) + k)) * (no_l / (no_l * (1.0 - k) + k))


def reference(roughness, no_v, f0, resolution=512):
    abscissae, quadrature_weights = np.polynomial.legendre.leggauss(resolution)
    cosine = (abscissae + 1.0) * 0.5
    quadrature_weights *= 0.5
    # Reflection symmetry permits phi in [0, pi]. Gauss nodes cluster at
    # the opposite-view direction, resolving its narrow grazing-angle lobe.
    phi = (abscissae + 1.0) * np.pi * 0.5
    x = np.sqrt(1.0 - cosine[:, None] ** 2) * np.cos(phi)
    y = np.sqrt(1.0 - cosine[:, None] ** 2) * np.sin(phi)
    z = np.broadcast_to(cosine[:, None], x.shape)
    light = np.stack((x, y, z), axis=-1)
    view = np.array([np.sqrt(1.0 - no_v**2), 0.0, no_v])
    half = light + view
    half /= np.linalg.norm(half, axis=-1, keepdims=True)
    no_h = half[..., 2]
    vo_h = half @ view
    alpha2 = roughness**4
    distribution = alpha2 / (np.pi * (no_h**2 * (alpha2 - 1.0) + 1.0) ** 2)
    fresnel = f0 + (1.0 - f0) * (1.0 - vo_h) ** 5
    integrand = distribution * geometry(no_v, z, roughness) * fresnel / (4.0 * no_v)
    return float(
        np.sum(np.sum(integrand * quadrature_weights[None, :], axis=1) * quadrature_weights)
        * 2.0
        * np.pi
    )


def estimator(roughness, no_v, f0, samples, azimuth=0.0):
    index = np.arange(samples, dtype=np.uint32)
    bits = index.copy()
    reverse = np.zeros_like(bits)
    for _ in range(32):
        reverse = (reverse << 1) | (bits & 1)
        bits >>= 1
    u = reverse.astype(np.float64) / 2.0**32
    phi = 2.0 * np.pi * (index.astype(np.float64) + 0.5) / samples
    cosine = np.sqrt((1.0 - u) / (1.0 + (roughness**4 - 1.0) * u))
    sine = np.sqrt(np.maximum(0.0, 1.0 - cosine**2))
    half = np.stack((np.cos(phi) * sine, np.sin(phi) * sine, cosine), axis=-1)
    view = np.array(
        [np.sqrt(1.0 - no_v**2) * np.cos(azimuth), np.sqrt(1.0 - no_v**2) * np.sin(azimuth), no_v]
    )
    vo_h = half @ view
    light = 2.0 * vo_h[:, None] * half - view
    no_l = np.maximum(light[:, 2], 0.0)
    fresnel = f0 + (1.0 - f0) * (1.0 - np.maximum(vo_h, 0.0)) ** 5
    weights = geometry(no_v, no_l, roughness) * fresnel * np.maximum(vo_h, 0.0) / (no_v * cosine)
    return float(np.mean(np.where((no_l > 0.0) & (vo_h > 0.0), weights, 0.0)))


def visible_estimator(roughness, no_v, f0, samples):
    alpha = roughness**2
    view = np.array([np.sqrt(1.0 - no_v**2), 0.0, no_v])
    stretched = view * np.array([alpha, alpha, 1.0])
    stretched /= np.linalg.norm(stretched)
    tangent = np.cross([0.0, 0.0, 1.0], stretched)
    tangent = (
        tangent / np.linalg.norm(tangent)
        if np.linalg.norm(tangent) > 0.0
        else np.array([1.0, 0.0, 0.0])
    )
    bitangent = np.cross(stretched, tangent)
    indices = np.arange(samples, dtype=np.uint32)
    bits = indices.copy()
    reverse = np.zeros_like(bits)
    for _ in range(32):
        reverse = (reverse << 1) | (bits & 1)
        bits >>= 1
    phi = 2.0 * np.pi * reverse.astype(np.float64) / 2.0**32
    radius = np.sqrt((indices.astype(np.float64) + 0.5) / samples)
    disk_x = radius * np.cos(phi)
    disk_y = radius * np.sin(phi)
    blend = 0.5 * (1.0 + stretched[2])
    disk_y = (1.0 - blend) * np.sqrt(np.maximum(0.0, 1.0 - disk_x**2)) + blend * disk_y
    projected = disk_x[:, None] * tangent + disk_y[:, None] * bitangent
    projected += np.sqrt(np.maximum(0.0, 1.0 - disk_x**2 - disk_y**2))[:, None] * stretched
    half = projected * np.array([alpha, alpha, 1.0])
    half[:, 2] = np.maximum(0.0, half[:, 2])
    half /= np.linalg.norm(half, axis=1, keepdims=True)
    vo_h = half @ view
    light = 2.0 * vo_h[:, None] * half - view
    no_l = np.maximum(0.0, light[:, 2])
    k = (roughness + 1.0) ** 2 / 8.0
    view_weight = (no_v + np.sqrt(alpha**2 + (1.0 - alpha**2) * no_v**2)) / (
        2.0 * (no_v * (1.0 - k) + k)
    )
    fresnel = f0 + (1.0 - f0) * (1.0 - np.clip(vo_h, 0.0, 1.0)) ** 5
    weights = fresnel * view_weight * no_l / (no_l * (1.0 - k) + k)
    return float(np.mean(np.where(no_l > 0.0, weights, 0.0)))


def verify_lighting():
    rows = []
    sample_counts = [64, 128, 256, 4096]
    for roughness in [0.26, 0.49, 0.8]:
        for no_v in [0.001, 0.01, 0.05, 0.15, 0.5, 1.0]:
            for f0 in [0.055, 1.0]:
                target = reference(roughness, no_v, f0)
                coarse = reference(roughness, no_v, f0, 256)
                visible_values = {
                    str(n): visible_estimator(roughness, no_v, f0, n) for n in sample_counts
                }
                orientation_errors = {
                    str(n): max(
                        abs(estimator(roughness, no_v, f0, n, angle) - target) / target
                        for angle in np.arange(8) * np.pi / 4.0
                    )
                    for n in sample_counts
                }
                rows.append(
                    {
                        "roughness": roughness,
                        "no_v": no_v,
                        "f0": f0,
                        "reference": target,
                        "reference_refinement_relative_error": abs(target - coarse) / target,
                        "ndf_max_relative_error_over_8_azimuths": orientation_errors,
                        "visible_estimates": visible_values,
                        "visible_relative_errors": {
                            n: abs(value - target) / target for n, value in visible_values.items()
                        },
                    }
                )
    summary = {
        "case_count": len(rows),
        "max_reference_refinement_relative_error": max(
            row["reference_refinement_relative_error"] for row in rows
        ),
        "ndf_max_relative_errors": {
            str(n): max(row["ndf_max_relative_error_over_8_azimuths"][str(n)] for row in rows)
            for n in sample_counts
        },
        "visible_max_relative_errors": {
            str(n): max(row["visible_relative_errors"][str(n)] for row in rows)
            for n in sample_counts
        },
        "maximum_reference_reflectance": max(row["reference"] for row in rows),
    }
    # Bounded acceptance for this grid and current material roughness range.
    # Single-scattering GGX loses energy; unity is an upper bound, not a target.
    passed = (
        summary["max_reference_refinement_relative_error"] < 1e-6
        and summary["visible_max_relative_errors"]["128"] < 0.01
        and all(0.0 < row["reference"] <= 1.0 for row in rows)
        and all(
            np.isfinite(value) and 0.0 <= value <= 1.0
            for row in rows
            for value in row["visible_estimates"].values()
        )
    )
    shader = Path(__file__).resolve().parents[1] / "resources/shaders/mesh.frag"
    return {
        "scope": "CPU estimator versus independent constant-environment quadrature; not GPU execution or arbitrary environment validation",
        "method_source": "https://jcgt.org/published/0007/04/01/paper.pdf",
        "shader_sha256": hashlib.sha256(shader.read_bytes()).hexdigest(),
        "checker_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "passed": bool(passed),
        "summary": summary,
        "cases": rows,
    }


def disk_samples(samples):
    indices = np.arange(samples, dtype=np.uint32)
    bits = indices.copy()
    reverse = np.zeros_like(bits)
    for _ in range(32):
        reverse = (reverse << 1) | (bits & 1)
        bits >>= 1
    phi = 2.0 * np.pi * reverse.astype(float) / 2.0**32
    radial = np.sqrt((indices.astype(float) + 0.5) / samples)
    return np.stack((radial * np.sin(phi), radial * np.cos(phi)), axis=-1), np.full(
        samples, 1.0 / samples
    )


def disk_reference(resolution):
    nodes, weights = np.polynomial.legendre.leggauss(resolution)
    u = (nodes + 1.0) * 0.5
    phi = (np.arange(resolution * 4) + 0.5) * 2.0 * np.pi / (resolution * 4)
    radial = np.sqrt(u[:, None])
    points = np.stack((radial * np.cos(phi), radial * np.sin(phi)), axis=-1).reshape(-1, 2)
    weights = np.broadcast_to(weights[:, None] * 0.5 / len(phi), (resolution, len(phi))).ravel()
    return points, weights


def brdf_response(light, normal, view, roughness):
    no_v = float(view @ normal)
    no_l = np.maximum(light @ normal, 0.0)
    half = light + view
    half /= np.linalg.norm(half, axis=-1, keepdims=True)
    no_h = np.maximum(half @ normal, 0.0)
    vo_h = np.clip(half @ view, 0.0, 1.0)
    alpha2 = roughness**4
    distribution = alpha2 / (np.pi * (no_h * no_h * (alpha2 - 1.0) + 1.0) ** 2)
    nv = max(no_v, 0.001)
    k = (roughness + 1.0) ** 2 / 8.0
    geometry = (nv / (nv * (1.0 - k) + k)) * (no_l / (no_l * (1.0 - k) + k))
    albedo, metalness = 0.5, 0.18
    f0 = 0.04 * (1.0 - metalness) + albedo * metalness
    fresnel = f0 + (1.0 - f0) * (1.0 - vo_h) ** 5
    response = (
        (1.0 - fresnel) * (1.0 - metalness) * albedo / np.pi
        + distribution * geometry * fresnel / np.maximum(4.0 * nv * no_l, 0.001)
    ) * no_l
    return response


def disk_response(
    points,
    weights,
    distance,
    radius,
    tilt,
    no_v,
    roughness,
    azimuth=0.0,
    diffuse_only=False,
    backside=False,
):
    # Emitter in z=distance, facing the origin; world points match the GLSL basis.
    delta = np.column_stack((points * radius, np.full(len(points), distance)))
    distance2 = np.sum(delta * delta, axis=-1)
    light = delta / np.sqrt(distance2[:, None])
    normal = np.array(
        [np.sin(tilt) * np.cos(azimuth), np.sin(tilt) * np.sin(azimuth), np.cos(tilt)]
    )
    tangent = np.array(
        [np.cos(tilt) * np.cos(azimuth), np.cos(tilt) * np.sin(azimuth), -np.sin(tilt)]
    )
    view = normal * no_v + tangent * np.sqrt(1.0 - no_v * no_v)
    no_l = np.maximum(light @ normal, 0.0)
    emitter_cosine = np.maximum(light[:, 2] * (-1.0 if backside else 1.0), 0.0)
    if diffuse_only:
        response = no_l  # Irradiance, without the Lambertian 1/pi.
    else:
        response = brdf_response(light, normal, view, roughness)
    return float(np.sum(response * emitter_cosine / distance2 * weights) * np.pi * radius * radius)


def mis_response(count, tilt, no_v, roughness, azimuth, power=False, distance=4.0, radius=2.0):
    normal = np.array(
        [np.sin(tilt) * np.cos(azimuth), np.sin(tilt) * np.sin(azimuth), np.cos(tilt)]
    )
    view_tangent = np.array(
        [np.cos(tilt) * np.cos(azimuth), np.cos(tilt) * np.sin(azimuth), -np.sin(tilt)]
    )
    view = normal * no_v + view_tangent * np.sqrt(1.0 - no_v * no_v)
    axis = np.array([0.0, 0.0, 1.0]) if abs(normal[2]) < 0.999 else np.array([1.0, 0.0, 0.0])
    tangent = np.cross(axis, normal)
    tangent /= np.linalg.norm(tangent)
    bitangent = np.cross(normal, tangent)
    local_view = np.array([view @ tangent, view @ bitangent, view @ normal])
    alpha = roughness**2
    stretched = local_view * np.array([alpha, alpha, 1.0])
    stretched /= np.linalg.norm(stretched)
    length = np.linalg.norm(stretched[:2])
    disk_tangent = (
        np.array([-stretched[1], stretched[0], 0.0]) / length
        if length > 0.0
        else np.array([1.0, 0.0, 0.0])
    )
    disk_bitangent = np.cross(stretched, disk_tangent)
    points, weights = disk_samples(count)
    disk_x, disk_y = points[:, 1], points[:, 0]
    blend = 0.5 * (1.0 + stretched[2])
    disk_y = (1.0 - blend) * np.sqrt(np.maximum(0.0, 1.0 - disk_x**2)) + blend * disk_y
    projected = disk_x[:, None] * disk_tangent + disk_y[:, None] * disk_bitangent
    projected += np.sqrt(np.maximum(0.0, 1.0 - disk_x**2 - disk_y**2))[:, None] * stretched
    half = projected * np.array([alpha, alpha, 1.0])
    half[:, 2] = np.maximum(half[:, 2], 0.0)
    half /= np.linalg.norm(half, axis=1, keepdims=True)
    half = half[:, 0, None] * tangent + half[:, 1, None] * bitangent + half[:, 2, None] * normal
    reflected = 2.0 * (half @ view)[:, None] * half - view
    delta = np.column_stack((points * radius, np.full(count, distance)))
    distances = np.linalg.norm(delta, axis=1)
    light = delta / distances[:, None]

    def contribution(directions, distances, hit, area_sample):
        cosine = directions[:, 2]
        no_l = directions @ normal
        hit = hit & (cosine > 0.0) & (no_l > 0.0)
        if not np.any(hit):
            return 0.0
        directions = directions[hit]
        area_pdf = distances[hit] ** 2 / (radius**2 * np.pi * cosine[hit])
        half = directions + view
        half /= np.linalg.norm(half, axis=1, keepdims=True)
        no_h = np.maximum(half @ normal, 0.0)
        distribution = alpha**2 / (np.pi * (no_h**2 * (alpha**2 - 1.0) + 1.0) ** 2)
        reflection_pdf = distribution / (
            2.0 * (no_v + np.sqrt(alpha**2 + (1.0 - alpha**2) * no_v**2))
        )
        weight = 1.0 / (area_pdf + reflection_pdf)
        if power:
            weight = (area_pdf if area_sample else reflection_pdf) / (
                area_pdf**2 + reflection_pdf**2
            )
        return float(np.sum(brdf_response(directions, normal, view, roughness) * weight) / count)

    total = contribution(light, distances, np.ones(count, dtype=bool), True)
    distances = np.divide(
        distance, reflected[:, 2], out=np.zeros(count), where=reflected[:, 2] != 0.0
    )
    offset = reflected * distances[:, None] - np.array([0.0, 0.0, distance])
    hit = (distances > 0.0) & (np.sum(offset * offset, axis=1) <= radius**2)
    return total + contribution(reflected, distances, hit, False)


def verify_area_lights():
    samples = {n: disk_samples(n) for n in [64, 128, 256, 1024]}
    references = {n: disk_reference(n) for n in [128, 256]}
    rows = []
    for distance, radius in [(4.0, 2.0), (13.0, 4.0), (np.sqrt(34.0), 2.0)]:
        for roughness in [0.26, 0.49, 0.8]:
            for tilt in np.deg2rad([0.0, 60.0, 85.0, 100.0]):
                for no_v in [0.15, 0.5, 1.0]:
                    target = disk_response(
                        *references[256], distance, radius, tilt, no_v, roughness
                    )
                    coarse = disk_response(
                        *references[128], distance, radius, tilt, no_v, roughness
                    )
                    errors = {
                        str(n): max(
                            abs(
                                disk_response(
                                    *samples[n], distance, radius, tilt, no_v, roughness, angle
                                )
                                - target
                            )
                            for angle in np.arange(8) * np.pi / 4.0
                        )
                        for n in samples
                    }
                    mis_errors = {
                        str(n): max(
                            abs(
                                mis_response(
                                    n,
                                    tilt,
                                    no_v,
                                    roughness,
                                    angle,
                                    distance=distance,
                                    radius=radius,
                                )
                                - target
                            )
                            / target
                            for angle in np.arange(8) * np.pi / 4.0
                        )
                        for n in [32, 64, 128, 512]
                    }
                    power_errors = {
                        str(n): max(
                            abs(
                                mis_response(
                                    n,
                                    tilt,
                                    no_v,
                                    roughness,
                                    angle,
                                    power=True,
                                    distance=distance,
                                    radius=radius,
                                )
                                - target
                            )
                            / target
                            for angle in np.arange(8) * np.pi / 4.0
                        )
                        for n in [32, 64, 128, 512]
                    }
                    rows.append(
                        {
                            "distance": distance,
                            "radius": radius,
                            "power_relative_errors": power_errors,
                            "mis_relative_errors": mis_errors,
                            "roughness": roughness,
                            "tilt_degrees": float(np.rad2deg(tilt)),
                            "no_v": no_v,
                            "reference": target,
                            "reference_refinement_absolute_error": abs(target - coarse),
                            "absolute_errors": errors,
                            "relative_errors": {n: err / target for n, err in errors.items()},
                        }
                    )
    analytic = []
    for distance in [0.25, 1.0, 4.0, 16.0]:
        exact = np.pi * 2.0**2 / (distance**2 + 2.0**2)
        estimate = disk_response(*samples[128], distance, 2.0, 0.0, 1.0, 0.26, diffuse_only=True)
        reference = disk_response(
            *references[128], distance, 2.0, 0.0, 1.0, 0.26, diffuse_only=True
        )
        analytic.append(
            {"distance": distance, "exact": exact, "sampled": estimate, "reference": reference}
        )
    return {
        "cases": rows,
        "analytic": analytic,
        "summary": {
            "case_count": len(rows),
            "max_reference_refinement_absolute_error": max(
                r["reference_refinement_absolute_error"] for r in rows
            ),
            "max_relative_error": {
                str(n): max(r["relative_errors"][str(n)] for r in rows) for n in samples
            },
            "power_max_relative_error": {
                str(n): max(r["power_relative_errors"][str(n)] for r in rows)
                for n in [32, 64, 128, 512]
            },
            "mis_max_relative_error": {
                str(n): max(r["mis_relative_errors"][str(n)] for r in rows)
                for n in [32, 64, 128, 512]
            },
            "max_absolute_error": {
                str(n): max(r["absolute_errors"][str(n)] for r in rows) for n in samples
            },
        },
    }


def verify_area_quality():
    result = verify_area_lights()
    summary = result["summary"]
    backside = disk_response(
        *disk_samples(128), 4.0, 2.0, 0.0, 1.0, 0.26, diffuse_only=True, backside=True
    )
    analytic_error = max(
        abs(row["reference"] - row["exact"]) / row["exact"] for row in result["analytic"]
    )
    formula_passed = (
        analytic_error < 1e-10
        and backside == 0.0
        and all(row["reference"] > 0.0 for row in result["cases"])
    )
    # A strict integration-quality target, not a claim that the preview passes.
    quality_passed = summary["mis_max_relative_error"]["128"] < 0.01
    summary.update(
        {
            "analytic_max_relative_error": analytic_error,
            "backside_irradiance": backside,
            "formula_checks_passed": bool(formula_passed),
            "one_percent_sampling_target_passed": bool(quality_passed),
        }
    )
    shader = Path(__file__).resolve().parents[1] / "resources/shaders/mesh.frag"
    result.update(
        {
            "scope": "CPU finite disk emitter integration; equal-count balance MIS, 128 samples per technique; no occlusion or GPU execution",
            "method_source": "https://pbr-book.org/4ed/Monte_Carlo_Integration/Improving_Efficiency",
            "shader_sha256": hashlib.sha256(shader.read_bytes()).hexdigest(),
            "checker_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "passed": bool(formula_passed and quality_passed),
        }
    )
    return result


def lighting_main(argv=None):
    parser = argparse.ArgumentParser(description="Check Pitoco's rough specular environment integration (NumPy required).")
    parser.add_argument("--output", type=Path, help="Write the complete measured cases to JSON")
    parser.add_argument(
        "--check", action="store_true", help="Fail if the bounded numerical checks fail"
    )
    parser.add_argument(
        "--area-lights",
        action="store_true",
        help="Check finite disk lights, including the strict 1% sampling target",
    )
    arguments = parser.parse_args(argv)
    result = verify_area_quality() if arguments.area_lights else verify_lighting()
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"passed": result["passed"], **result["summary"]}, indent=2))
    if arguments.check and not result["passed"]:
        raise SystemExit(1)


def triangle_hit(origin, direction, maximum, triangle):
    # Independent Moller-Trumbore algebra; no hierarchy or ray-aligned shear.
    a, b, c = np.asarray(triangle, dtype=np.float64)
    e1, e2 = b - a, c - a
    h = np.cross(direction, e2)
    determinant = np.dot(e1, h)
    if determinant == 0:
        return False
    offset = origin - a
    u = np.dot(offset, h) / determinant
    q = np.cross(offset, e1)
    v = np.dot(direction, q) / determinant
    t = np.dot(e2, q) / determinant
    return bool(u >= 0 and v >= 0 and u + v <= 1 and 0 < t < maximum)


def box_hit(origin, direction, maximum, lower, upper):
    near, far = 0.0, maximum
    for axis in range(3):
        if direction[axis] == 0:
            if not lower[axis] <= origin[axis] <= upper[axis]:
                return False
        else:
            a, b = (lower[axis] - origin[axis]) / direction[axis], (
                upper[axis] - origin[axis]
            ) / direction[axis]
            near, far = max(near, min(a, b)), min(far, max(a, b))
    return bool(near <= far)


def read_packet(path):
    words = np.fromfile(path, dtype="<u4")
    assert words[0] == 0x5049544F and 0 < words[1] <= 3 and words[7] == len(words)
    bodies = []
    for offset in words[4 : 4 + words[1]]:
        offset = int(offset)
        nodes, points, faces = map(int, words[offset : offset + 3])
        start = offset + 4 + 8 * nodes
        positions = (
            words[start : start + points * 4]
            .view("<f4")
            .reshape(-1, 4)[:, :3]
            .astype(float)
        )
        start += 4 * points
        indices = words[start : start + faces * 4].reshape(-1, 4)[:, :3]
        bodies.append(positions[indices])
    return bodies


def make_cases(packet):
    rng = np.random.default_rng(41377)
    cases = []

    def add(kind, origin, direction, maximum, a, b, c, expected=None):
        origin, direction, a, b, c = [
            np.asarray(v, dtype=np.float32).astype(float)
            for v in [origin, direction, a, b, c]
        ]
        maximum = float(np.float32(maximum))
        if expected is None:
            expected = (
                triangle_hit(origin, direction, maximum, [a, b, c])
                if kind == 0
                else box_hit(origin, direction, maximum, a, b)
            )
        cases.append(
            dict(
                kind=kind,
                origin=origin.tolist(),
                direction=direction.tolist(),
                maximum=maximum,
                a=a.tolist(),
                b=b.tolist(),
                c=c.tolist(),
                expected=bool(expected),
            )
        )

    # Closed shared edges/vertices, both windings, finite segments, signed zeros.
    for x, y in [(0, 0), (0.5, 0.5), (1, 0), (0, 1), (0.2, 0.3), (1.01, 0.2)]:
        for winding in [False, True]:
            a, b, c = [0, 0, 0], [1, 0, 0], [0, 1, 0]
            for maximum in [0.5, 1.0, 2.0]:
                add(
                    0,
                    [x, y, 1],
                    [-0.0, 0, -1],
                    maximum,
                    a,
                    c if winding else b,
                    b if winding else c,
                )
    for scale in [1e-5, 1, 1e5]:
        for _ in range(12):
            triangle = rng.normal(size=(3, 3)) * scale
            target = triangle.mean(axis=0)
            origin = target + rng.normal(size=3) * scale
            direction = target - origin
            direction /= np.linalg.norm(direction)
            add(0, origin, direction, np.linalg.norm(target - origin) * 2, *triangle)
    for _ in range(20):
        triangle = rng.normal(size=(3, 3))
        direction = rng.normal(size=3)
        direction /= np.linalg.norm(direction)
        add(0, rng.normal(size=3), direction, 10, *triangle)
    for origin in [
        [0, 0, 2],
        [1, 1, 2],
        [1.01, 0, 2],
        [0, 0, 0],
        [-1, -1, 2],
        [0, 2, 0],
    ]:
        for direction in [[0, -0.0, -1], [-0.0, -1, 0], [1, 0, 0]]:
            add(1, origin, direction, 10, [-1, -1, -1], [1, 1, 1], [0, 0, 0])
    if packet:
        bodies = read_packet(packet)
        for body, triangles in enumerate(bodies):
            lower, upper = triangles.min(axis=(0, 1)), triangles.max(axis=(0, 1))
            center = (lower + upper) * 0.5
            for i in range(40):
                origin = rng.uniform(lower - 1, upper + 1)
                target = center if i < 20 else rng.uniform(lower - 1, upper + 1)
                delta = target - origin
                direction = (
                    (delta / np.linalg.norm(delta)).astype(np.float32).astype(float)
                )
                origin = origin.astype(np.float32).astype(float)
                maximum = float(
                    np.float32(np.linalg.norm(delta) * (2 if i < 10 else 1))
                )
                expected = any(
                    triangle_hit(origin, direction, maximum, t) for t in triangles
                )
                add(
                    body + 2,
                    origin,
                    direction,
                    maximum,
                    [0] * 3,
                    [0] * 3,
                    [0] * 3,
                    expected,
                )
    assert len(cases) <= 256
    return cases


def generate(args):
    cases = make_cases(args.packet)
    source = Path(args.source).read_text().split("void visibility_main() {")[0]

    def array(name, values):
        entries = ",\n".join(
            "vec4(" + ", ".join(f"{float(v):.10e}" for v in row) + ")" for row in values
        )
        return f"const vec4 {name}[{len(cases)}] = vec4[]({entries});\n"

    source += array("origins", [c["origin"] + [c["maximum"]] for c in cases])
    source += array("directions", [c["direction"] + [c["kind"]] for c in cases])
    for name in ["a", "b", "c"]:
        source += array("test_" + name, [c[name] + [int(c["expected"])] for c in cases])
    source += f"""
void visibility_main() {{
  vec2 logical = (screen * .5 + .5) * scene.viewport.xy;
  ivec2 tile = ivec2(floor((logical - vec2(240, 190)) / vec2(44, 23)));
  int index = tile.x + 16 * tile.y;
  if (any(lessThan(tile, ivec2(0))) || any(greaterThanEqual(tile, ivec2(16))) || index >= {len(cases)}) {{
    color = vec4(.05, .05, .05, 1);
    return;
  }}
  int kind = int(directions[index].w);
  bool expected = test_a[index].w > .5;
  int actual;
  if (kind == 0) actual = rayTriangle(origins[index].xyz, directions[index].xyz, origins[index].w,
      test_a[index].xyz, test_b[index].xyz, test_c[index].xyz) ? 1 : 0;
  else if (kind == 1) actual = rayBox(origins[index].xyz, directions[index].xyz, origins[index].w,
      test_a[index].xyz, test_b[index].xyz) ? 1 : 0;
  else actual = hasDisplayGeometry() ? bodyOcclusion(uint(kind - 2), origins[index].xyz,
      directions[index].xyz, origins[index].w) : -1;
  color = actual < 0 ? vec4(1, 0, 1, 1)
        : ((actual == 1) == expected ? vec4(0, 1, 0, 1) : vec4(1, 0, 0, 1));
}}
"""
    Path(args.output).write_text(source)
    Path(args.cases).write_text(json.dumps(cases, indent=2) + "\n")
    print(
        json.dumps(
            {
                "cases": len(cases),
                "expected_hits": sum(c["expected"] for c in cases),
                "shader": args.output,
            }
        )
    )


def inspect(args):
    from PIL import Image

    cases = json.loads(Path(args.cases).read_text())
    image = np.asarray(Image.open(args.capture).convert("RGB"))
    height, width = image.shape[:2]
    failed = []
    for index, case in enumerate(cases):
        x = round((240 + (index % 16 + 0.5) * 44) / 1280 * width)
        y = round((190 + (index // 16 + 0.5) * 23) / 820 * height)
        rgb = image[y, x].tolist()
        if rgb != [0, 255, 0]:
            failed.append({"index": index, "rgb": rgb, "case": case})
    result = {
        "cases": len(cases),
        "failures": failed,
        "passed": not failed,
        "capture": args.capture,
    }
    print(json.dumps(result, indent=2))
    return not failed


def visibility_main(argv=None):
    parser = argparse.ArgumentParser(description='Generate GPU visibility checks with an independent float64 exhaustive oracle.')
    parser.add_argument("--source", default="resources/shaders/mesh.frag")
    parser.add_argument("--output", default="build/visibility-diagnostic.frag")
    parser.add_argument("--cases", default="build/visibility-diagnostic-cases.json")
    parser.add_argument("--packet")
    parser.add_argument("--capture")
    args = parser.parse_args(argv)
    if args.capture:
        raise SystemExit(0 if inspect(args) else 1)
    generate(args)
