#!/usr/bin/env python3
"""Offline verification of Pitoco's native outputs.

Run from examples/field-lab. This is developer tooling, not a runtime dependency.
Material precision, exported mesh, and native ABI checks use the standard library.
Mechanics/rendering subcommands lazily load their optional numerical dependencies.
"""

import argparse
import importlib
import sys
import csv
from decimal import Decimal, localcontext
import hashlib
import json
from pathlib import Path
import math
import re
import shutil
import ctypes as c


def verify_material(path):
    records = []
    with path.open() as stream, localcontext() as context:
        context.prec = 80
        for row in csv.DictReader(stream):
            number = lambda key: Decimal.from_float(float(row[key]))
            f = [[number(f"f{3 * column + axis}") for axis in range(3)] for column in range(3)]
            cross = lambda a, b: [a[1] * b[2] - a[2] * b[1],
                                  a[2] * b[0] - a[0] * b[2],
                                  a[0] * b[1] - a[1] * b[0]]
            cofactor = [cross(f[1], f[2]), cross(f[2], f[0]), cross(f[0], f[1])]
            invariant = sum(value * value for column in f for value in column)
            jacobian = sum(a * b for a, b in zip(f[0], cofactor[0]))
            mu, lame, alpha = (number(key) for key in ["mu", "lambda", "alpha"])
            rest_shift = 1 - alpha
            volume_shift = jacobian - alpha
            energy = (mu * (invariant - 3) + lame * (volume_shift ** 2 - rest_shift ** 2)
                      - mu * ((invariant + 1) / 4).ln()) / 2
            shear = mu * (1 - 1 / (invariant + 1))
            pressure = lame * volume_shift
            stress = [shear * f[column][axis] + pressure * cofactor[column][axis]
                      for column in range(3) for axis in range(3)]
            native = number("energy")
            error = abs(native - energy)
            records.append({
                "case": row["case"], "poisson": float(row["poisson"]),
                "native_energy_Pa": float(native), "reference_energy_Pa": str(energy),
                "absolute_energy_error_Pa": float(error),
                "relative_energy_error": float(error / abs(energy)) if energy else None,
                "maximum_stress_error_Pa": float(max(abs(number(f"p{i}") - value)
                                                       for i, value in enumerate(stress))),
            })
    if not records:
        raise ValueError("No native material responses to verify_material")
    for record in records:
        bound = max(1e-23, abs(float(record["reference_energy_Pa"])) * 1e-9)
        record["energy_error_bound_Pa"] = bound
        record["passed"] = (record["absolute_energy_error_Pa"] <= bound
                            and record["maximum_stress_error_Pa"] <= 1e-8)
    return {"source": str(path), "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "decimal_digits": 80, "passed": all(record["passed"] for record in records),
            "cases": records}


def material_main(argv=None):
    parser = argparse.ArgumentParser(description='Evaluate the published material formula at 80 decimal digits from binary64 inputs.')
    parser.add_argument("csv", type=Path)
    parser.add_argument("--check", action="store_true", help="Fail on precision errors instead of only reporting them")
    arguments = parser.parse_args(argv)
    result = verify_material(arguments.csv)
    print(json.dumps(result, indent=2))
    if arguments.check and not result["passed"]:
        raise SystemExit(1)


def rows(path):
    with path.open(newline="") as stream:
        yield from csv.DictReader(stream)


def vector(row, keys):
    result = [float(row[key]) for key in keys]
    assert all(math.isfinite(value) for value in result), row
    return result


def subtract(a, b):
    return [x - y for x, y in zip(a, b)]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_mesh(directory, expected):
    settings = json.loads((directory / "experiment.json").read_text())
    assert settings["body_count"] == expected.bodies
    count, dt = expected.bodies, settings["dt_s"]
    node_counts = expected.nodes * count if len(expected.nodes) == 1 else expected.nodes
    cell_counts = expected.cells * count if len(expected.cells) == 1 else expected.cells
    assert len(node_counts) == len(cell_counts) == count
    scripted = settings.get("format") == "pitoco/solid-scene-v1"
    if scripted:
        bodies = settings["bodies"]
        assert [body["body"] for body in bodies] == list(range(count))
        source = settings["scene_source"]
        assert re.fullmatch(r"scenes/[0-9a-f]{64}\.edn", source)
        assert sha256(directory / source) == Path(source).stem
        body_masses = [body["mass_kg"] for body in bodies]
        gravity_y = [body["gravity_m_s2"][1] for body in bodies]
        floors = [body["floor"] for body in bodies]
    else:
        body_masses = [settings["mass_kg"]] * count
        gravity_y = [-settings["gravity_m_s2"]] * count
        floors = [True] * count
    assert all(math.isfinite(mass) and mass > 0 for mass in body_masses)
    positions = [[] for _ in range(count)]
    for row in rows(directory / "reference.csv"):
        body, node = int(row["body"]), int(row["node"])
        assert 0 <= body < count and node == len(positions[body])
        positions[body].append(vector(row, ["x_m", "y_m", "z_m"]))
    assert list(map(len, positions)) == node_counts

    weights = [[0.0] * len(body) for body in positions]
    cells = [0] * count
    elements = [[] for _ in range(count)]
    for row in rows(directory / "cells.csv"):
        body = int(row["body"])
        assert 0 <= body < count and int(row["cell"]) == cells[body]
        indices = [int(row[key]) for key in ["a", "b", "c", "d"]]
        assert len(set(indices)) == 4 and all(0 <= i < node_counts[body] for i in indices)
        a, b, c, d = [positions[body][i] for i in indices]
        determinant = dot(subtract(b, a), cross(subtract(c, a), subtract(d, a)))
        volume = abs(determinant) / 6.0
        assert math.isfinite(volume) and volume > 0.0
        elements[body].append((indices, determinant))
        for node in indices:
            weights[body][node] += volume / 4.0
        cells[body] += 1
    assert cells == cell_counts
    volumes = [sum(body) for body in weights]
    masses = [[weight * body_masses[body] / volumes[body] for weight in values]
              for body, values in enumerate(weights)]
    assert all(all(value > 0.0 for value in body) for body in masses)

    trajectory = {}
    energies = [0.0] * expected.frames
    rigid_only = ["wx_rad_s", "wy_rad_s", "wz_rad_s", "qx", "qy", "qz", "qw", "normal_impulse_N_s"]
    for row in rows(directory / "trajectory.csv"):
        body, time = int(row["body"]), float(row["time_s"])
        tick = round(time / dt)
        assert 0 <= tick < expected.frames and 0 <= body < count and abs(time - tick * dt) < 1e-12
        assert (body, tick) not in trajectory
        center = vector(row, ["x_m", "y_m", "z_m"])
        velocity = vector(row, ["vx_m_s", "vy_m_s", "vz_m_s"])
        energy = float(row["energy_J"])
        assert math.isfinite(energy) and all(math.isnan(float(row[key])) for key in rigid_only)
        trajectory[body, tick] = center, velocity
        energies[tick] += energy
    assert len(trajectory) == expected.frames * count

    current = None
    accumulated = [0.0] * 6
    next_node = 0
    groups = set()
    record_count = 0
    minimum_y = math.inf
    maximum_center_error = maximum_velocity_error = 0.0
    frame_positions = []
    minimum_jacobian = math.inf

    def finish():
        nonlocal maximum_center_error, maximum_velocity_error, minimum_jacobian
        assert current not in groups and next_node == node_counts[current[0]]
        center, velocity = trajectory[current]
        measured = [value / body_masses[current[0]] for value in accumulated]
        maximum_center_error = max(maximum_center_error, max(abs(x - y) for x, y in zip(center, measured[:3])))
        maximum_velocity_error = max(maximum_velocity_error, max(abs(x - y) for x, y in zip(velocity, measured[3:])))
        for indices, reference_determinant in elements[current[0]]:
            a, b, c, d = [frame_positions[index] for index in indices]
            jacobian = dot(subtract(b, a), cross(subtract(c, a), subtract(d, a))) / reference_determinant
            assert math.isfinite(jacobian) and jacobian > 0.0, (current, indices, jacobian)
            minimum_jacobian = min(minimum_jacobian, jacobian)
        groups.add(current)

    for row in rows(directory / "particles.csv"):
        body, node, time = int(row["body"]), int(row["particle"]), float(row["time_s"])
        tick = round(time / dt)
        key = body, tick
        assert key in trajectory and abs(time - tick * dt) < 1e-12
        if key != current:
            if current is not None:
                finish()
            current, accumulated, next_node = key, [0.0] * 6, 0
            frame_positions = []
        assert node == next_node and node < node_counts[body]
        values = vector(row, ["x_m", "y_m", "z_m", "vx_m_s", "vy_m_s", "vz_m_s"])
        frame_positions.append(values[:3])
        for axis, value in enumerate(values):
            accumulated[axis] += masses[body][node] * value
        minimum_y = min(minimum_y, values[1])
        if floors[body]:
            assert values[1] >= -1e-12
        next_node += 1
        record_count += 1
    assert current is not None
    finish()
    assert len(groups) == count * expected.frames
    assert record_count == expected.frames * sum(node_counts)
    assert maximum_center_error < 1e-10 and maximum_velocity_error < 1e-10

    impulse = float(expected.ground_impulse_file.read_text()) if expected.ground_impulse_file else None
    time = (expected.frames - 1) * dt
    momentum_change = sum(body_masses[body] * (trajectory[body, expected.frames - 1][1][1]
                                               - trajectory[body, 0][1][1]) for body in range(count))
    residual = None
    if impulse is not None:
        assert math.isfinite(impulse)
        residual = momentum_change - (sum(mass * gravity for mass, gravity in zip(body_masses, gravity_y)) * time + impulse)
        assert abs(residual) < 1e-8
    return {"bodies": count, "frames": expected.frames, "particle_rows": record_count,
            "nodes": sum(map(len, positions)), "cells": sum(cells), "reference_volumes_m3": volumes,
            "minimum_y_m": minimum_y, "maximum_center_error_m": maximum_center_error,
            "minimum_sampled_element_jacobian": minimum_jacobian,
            "maximum_velocity_error_m_s": maximum_velocity_error,
            "initial_energy_J": energies[0], "final_energy_J": energies[-1],
            "maximum_energy_J": max(energies),
            "energy_loss_fraction": 1.0 - energies[-1] / energies[0] if energies[0] else None,
            "body_masses_kg": body_masses, "scene_source": settings.get("scene_source"),
            "vertical_momentum_change_Ns": momentum_change, "ground_impulse_Ns": impulse,
            "vertical_momentum_balance_checked": impulse is not None,
            "vertical_momentum_balance_error_Ns": residual}


def compare_trajectories(coarse, fine):
    """Compare exported centers/velocities across meshes at identical times.

    Exported material/environment fields must match. Full scene/solver equality
    needs the separate source provenance check; this is not a convergence test.
    """
    settings = [json.loads((directory / "experiment.json").read_text())
                for directory in [coarse, fine]]
    assert settings[0]["body_count"] == settings[1]["body_count"]
    assert settings[0]["dt_s"] == settings[1]["dt_s"]
    physics = lambda scene: [{key: value for key, value in body.items() if key != "mass_kg"}
                             for body in scene["bodies"]]
    assert physics(settings[0]) == physics(settings[1]), "Exported material/environment fields differ"

    def trajectory(directory):
        result = {}
        for row in rows(directory / "trajectory.csv"):
            key = int(row["body"]), float(row["time_s"])
            assert key not in result and math.isfinite(key[1])
            result[key] = (vector(row, ["x_m", "y_m", "z_m"]),
                           vector(row, ["vx_m_s", "vy_m_s", "vz_m_s"]))
        return result

    before, after = trajectory(coarse), trajectory(fine)
    assert before and before.keys() == after.keys(), "Trajectory sample identities differ"
    differences = []
    for axis in [0, 1]:
        error, (body, time) = max((math.dist(before[key][axis], after[key][axis]), key)
                                  for key in before)
        differences.append({"maximum": error, "body": body, "time_s": time})
    return {"coarse_scene_source": settings[0]["scene_source"],
            "fine_scene_source": settings[1]["scene_source"],
            "samples": len(before), "center_difference_m": differences[0],
            "center_velocity_difference_m_s": differences[1],
            "coarse_body_masses_kg": [body["mass_kg"] for body in settings[0]["bodies"]],
            "fine_body_masses_kg": [body["mass_kg"] for body in settings[1]["bodies"]]}


def mesh_main(argv=None):
    parser = argparse.ArgumentParser(description='Stream-check a tetrahedral FEM export against its reference-mesh mass measure.')
    parser.add_argument("directory", type=Path)
    parser.add_argument("--bodies", type=int, required=True)
    per_body = lambda value: [int(item) for item in value.split(",")]
    parser.add_argument("--nodes", type=per_body, required=True, help="Nodes per body, or comma-separated counts")
    parser.add_argument("--cells", type=per_body, required=True, help="Tetrahedra per body, or comma-separated counts")
    parser.add_argument("--frames", type=int, required=True)
    momentum = parser.add_mutually_exclusive_group(required=True)
    momentum.add_argument("--ground-impulse-file", type=Path)
    momentum.add_argument("--no-ground-impulse-measurement", action="store_true",
                          help="Explicitly mark momentum balance unverified when the backend has no impulse measurement")
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--compare-to", type=Path,
                        help="Compare trajectories with a previously verified authored-scene export")
    arguments = parser.parse_args(argv)
    assert min([arguments.bodies, arguments.frames] + arguments.nodes + arguments.cells) > 0
    files = ["trajectory.csv", "particles.csv", "reference.csv", "cells.csv", "experiment.json"]
    settings = json.loads((arguments.directory / "experiment.json").read_text())
    if settings.get("format") == "pitoco/solid-scene-v1":
        source = settings["scene_source"]
        assert re.fullmatch(r"scenes/[0-9a-f]{64}\.edn", source)
        files.append(source)
    def signatures():
        return {name: ((arguments.directory / name).stat().st_size,
                       (arguments.directory / name).stat().st_mtime_ns) for name in files}
    before = signatures()
    result = verify_mesh(arguments.directory, arguments)
    if arguments.compare_to is not None:
        # Require the archived inputs to still match their verification manifest.
        manifest = json.loads((arguments.compare_to / "sha256.json").read_text())
        for name, signature in manifest.items():
            assert sha256(arguments.compare_to / name) == signature, "Baseline evidence changed"
        result["trajectory_comparison"] = compare_trajectories(arguments.compare_to, arguments.directory)
    assert signatures() == before, "Export changed during verification"
    arguments.evidence.mkdir(parents=True, exist_ok=True)
    manifest = {}
    for name in files:
        source, target = arguments.directory / name, arguments.evidence / name
        manifest[name] = sha256(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        assert sha256(target) == manifest[name], "Export changed during evidence copy"
    assert signatures() == before, "Export changed during evidence copy"
    (arguments.evidence / "verification.json").write_text(json.dumps(result, indent=2) + "\n")
    (arguments.evidence / "sha256.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(result, indent=2))


def verify_native_abi(path):
    library = c.CDLL(str(path.resolve()))
    double_pointer = c.POINTER(c.c_double)
    index_pointer = c.POINTER(c.c_uint32)
    handle = c.c_void_p
    signatures = {
        "create": (handle, [c.c_uint32, double_pointer, double_pointer, c.c_uint32, index_pointer,
                            c.c_uint32, index_pointer, c.POINTER(c.c_uint8)]),
        "destroy": (None, [handle]),
        "error": (c.c_char_p, []),
        "begin_step": (c.c_uint32, [handle, double_pointer, double_pointer, c.c_double, c.c_double, c.c_double]),
        "friction_origin": (c.c_uint32, [handle, double_pointer]),
        "evaluate": (c.c_uint32, [handle, double_pointer, c.c_double, c.c_double, c.c_uint32,
                                  double_pointer, double_pointer, double_pointer]),
        "matrix_begin": (c.c_uint32, [handle, double_pointer, c.c_double]),
        "add_projected_element": (c.c_uint32, [handle, index_pointer, double_pointer]),
        "solve": (c.c_uint32, [handle, double_pointer, double_pointer]),
        "safe_step": (c.c_double, [handle, double_pointer, double_pointer]),
        "trial": (c.c_uint32, [handle, double_pointer, double_pointer, c.c_double, double_pointer]),
    }
    prefix = "pitoco_aguafria_variational_" if hasattr(library, "pitoco_aguafria_variational_create") else "pitoco_variational_"
    functions = {}
    for name, (result, arguments) in signatures.items():
        function = getattr(library, prefix + name)
        function.restype = result
        function.argtypes = arguments
        functions[name] = function

    def doubles(values):
        return (c.c_double * len(values))(*values)

    def check(status):
        if status:
            raise AssertionError(functions["error"]().decode())

    gap = 5e-5
    rest = [0, gap, 0, 0.1, gap, 0, 0, gap, 0.1, 0, gap + 0.1, 0]
    faces = (c.c_uint32 * 12)(2, 1, 3, 0, 3, 1, 0, 2, 3, 0, 1, 2)
    cells = (c.c_uint32 * 4)(0, 2, 1, 3)
    floor = (c.c_uint8 * 4)(1, 1, 1, 1)
    context = functions["create"](4, doubles(rest), doubles(rest), 4, faces, 1, cells, floor)
    assert context, functions["error"]()
    try:
        check(functions["begin_step"](context, doubles(rest), doubles([0.5] * 4), 0.001, 1e-4, 1000))
        point = [value + (1e-5 if index % 3 == 0 else 0) for index, value in enumerate(rest)]

        def evaluate(values, derivatives=1):
            gradient = doubles([0] * 12)
            barrier, friction = c.c_double(), c.c_double()
            check(functions["evaluate"](context, doubles(values), 1e-4, 1000, derivatives,
                                         gradient, c.byref(barrier), c.byref(friction)))
            return barrier.value + friction.value, list(gradient), barrier.value, friction.value

        energy, gradient, barrier, friction = evaluate(point, 2)
        assert energy > barrier > 0 and friction > 0
        normal_force = -sum(gradient[1::3])
        tangential_force = abs(sum(gradient[0::3]))
        assert normal_force > 0
        assert math.isclose(tangential_force, 0.5 * normal_force, rel_tol=1e-10)
        errors = []
        for index in range(12):
            plus, minus = point.copy(), point.copy()
            plus[index] += 1e-8
            minus[index] -= 1e-8
            numerical = (evaluate(plus, 0)[0] - evaluate(minus, 0)[0]) / 2e-8
            errors.append(abs(numerical - gradient[index]) / max(1, abs(gradient[index])))
        assert max(errors) < 2e-6, errors

        # Newmark's velocity origin is an affine map, not collision geometry.
        # Reverse tangential slip and move that origin below the ground. Contact
        # energy/normal force must stay unchanged at the same physical point.
        origin = [x + (2e-5 if i % 3 == 0 else (-1.0 if i % 3 == 1 else 0.0))
                  for i, x in enumerate(point)]
        check(functions["friction_origin"](context, doubles(origin)))
        _, reversed_gradient, reversed_barrier, _ = evaluate(point)
        assert math.isclose(reversed_barrier, barrier, rel_tol=1e-13)
        assert math.isclose(sum(reversed_gradient[0::3]), -sum(gradient[0::3]), rel_tol=1e-10)
        assert math.isclose(sum(reversed_gradient[1::3]), sum(gradient[1::3]), rel_tol=1e-10)
        check(functions["friction_origin"](context, doubles(rest)))

        # Floor/friction Hessians are PSD here, so finite differences also check
        # the matrix assembled for Newton, independently of the sparse solver.
        evaluate(point, 2)
        scale = 1e-6
        check(functions["matrix_begin"](context, doubles([1] * 4), scale))
        rhs = [math.sin(index + 1) for index in range(12)]
        direction = doubles([0] * 12)
        check(functions["solve"](context, doubles(rhs), direction))
        epsilon = 1e-9
        plus = [x + epsilon * d for x, d in zip(point, direction)]
        minus = [x - epsilon * d for x, d in zip(point, direction)]
        gp, gm = evaluate(plus)[1], evaluate(minus)[1]
        linear_error = max(abs(d + scale * (a - b) / (2 * epsilon) + r)
                           for d, a, b, r in zip(direction, gp, gm, rhs))
        assert linear_error < 1e-5, linear_error

        # Insert a known PSD rank-one block with permuted local node numbers.
        # Its independently assembled matrix-vector product checks layout,
        # local/global indexing and the time-step scale in the sparse adapter.
        evaluate(point, 2)
        check(functions["matrix_begin"](context, doubles([1] * 4), scale))
        local_nodes = [2, 0, 3, 1]
        local_vector = [100 * math.sin(i + 0.5) for i in range(12)]
        block = [a * b for a in local_vector for b in local_vector]
        check(functions["add_projected_element"](
            context, (c.c_uint32 * 4)(*local_nodes), doubles(block)))
        check(functions["solve"](context, doubles(rhs), direction))
        global_vector = [0.0] * 12
        for local, value in enumerate(local_vector):
            global_vector[3 * local_nodes[local // 3] + local % 3] = value
        product = sum(a * b for a, b in zip(global_vector, direction))
        plus = [x + epsilon * d for x, d in zip(point, direction)]
        minus = [x - epsilon * d for x, d in zip(point, direction)]
        gp, gm = evaluate(plus)[1], evaluate(minus)[1]
        projected_error = max(abs(d + scale * ((a - b) / (2 * epsilon) + v * product) + r)
                              for d, a, b, v, r in zip(direction, gp, gm, global_vector, rhs))
        assert projected_error < 1e-5, projected_error

        downward = [(-0.1 if i % 3 == 1 else 0.0) for i in range(12)]
        evaluate(rest, 2)
        fraction = functions["safe_step"](context, doubles(rest), doubles(downward))
        assert 0 < fraction <= 0.8 * gap / 0.1
        output = doubles([0] * 12)
        assert functions["trial"](context, doubles(rest), doubles(downward), 1, output) == 1
        check(functions["trial"](context, doubles(rest), doubles(downward), fraction, output))
        assert min(output[1::3]) > 0

        # A positive final volume is insufficient: these two swaps cross a
        # zero-volume configuration halfway along the linear trajectory.
        target = rest[3:6] + rest[0:3] + rest[9:12] + rest[6:9]
        displacement = [b - a for a, b in zip(rest, target)]
        assert functions["trial"](context, doubles(rest), doubles(displacement), 1, output) == 1

        inverted = rest.copy()
        inverted[3:6], inverted[6:9] = rest[6:9], rest[3:6]
        invalid = functions["create"](4, doubles(rest), doubles(inverted), 4, faces, 1, cells, floor)
        assert not invalid
        return {"library_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "maximum_relative_gradient_error": max(errors), "newton_matrix_residual": linear_error,
                "projected_element_matrix_residual": projected_error,
                "friction_normal_force_ratio": tangential_force / normal_force,
                "affine_friction_origin_checked": True,
                "floor_step_fraction": fraction, "passed": True}
    finally:
        functions["destroy"](context)


def abi_main(argv=None):
    parser = argparse.ArgumentParser(description="Independent finite differences and feasibility checks for Pitoco's native ABI.")
    parser.add_argument("library", type=Path)
    args = parser.parse_args(argv)
    print(json.dumps(verify_native_abi(args.library), indent=2))


# Keep one executable interface; the two modules group the substantial reference
# algorithms by domain. Imports stay lazy so material/export checks need no venv.
COMMANDS = {
    "material": (None, "material_main", "80-digit material energy and stress"),
    "mesh": (None, "mesh_main", "Exported topology, trajectories and conservation"),
    "abi": (None, "abi_main", "Native contact ABI derivatives and feasibility"),
    "tetra": ("mechanics", "tetra_main", "Exact-polynomial element and assembly checks"),
    "mixed": ("mechanics", "mixed_main", "Independent mixed dynamics and rod diagnostics"),
    "rod": ("mechanics", "rod_main", "Independent one-dimensional impact reference"),
    "lighting": ("rendering", "lighting_main", "Environment and area-light integration"),
    "visibility": ("rendering", "visibility_main", "GPU visibility cases and capture checks"),
}


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter,
                                     epilog="Commands:\n" + "\n".join(
                                         f"  {name:12} {entry[2]}" for name, entry in COMMANDS.items())
                                     + "\n\nUse verify.py COMMAND --help for command options.")
    parser.add_argument("command", choices=COMMANDS)
    # Each established parser owns the remaining arguments, including --help.
    # This avoids duplicating or silently changing numerical command options.
    if not argv or argv[0] in {"-h", "--help"}:
        parser.parse_args(argv)
        return
    selected = parser.parse_args(argv[:1]).command
    module, function, _ = COMMANDS[selected]
    target = globals()[function] if module is None else getattr(importlib.import_module(module), function)
    target(argv[1:])


if __name__ == "__main__":
    main()
