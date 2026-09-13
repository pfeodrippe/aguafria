#!/usr/bin/env python3
"""Stream-check a tetrahedral FEM export against its reference-mesh mass measure.

This is an independent CSV check, not a simulation or a substitute for contact
convergence. Memory grows with the reference mesh and trajectory, not all frames.
"""
import argparse
import csv
import hashlib
import json
import math
import re
import shutil
from pathlib import Path


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


def verify(directory, expected):
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
    for row in rows(directory / "cells.csv"):
        body = int(row["body"])
        assert 0 <= body < count and int(row["cell"]) == cells[body]
        indices = [int(row[key]) for key in ["a", "b", "c", "d"]]
        assert len(set(indices)) == 4 and all(0 <= i < node_counts[body] for i in indices)
        a, b, c, d = [positions[body][i] for i in indices]
        volume = abs(dot(subtract(b, a), cross(subtract(c, a), subtract(d, a)))) / 6.0
        assert math.isfinite(volume) and volume > 0.0
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

    def finish():
        nonlocal maximum_center_error, maximum_velocity_error
        assert current not in groups and next_node == node_counts[current[0]]
        center, velocity = trajectory[current]
        measured = [value / body_masses[current[0]] for value in accumulated]
        maximum_center_error = max(maximum_center_error, max(abs(x - y) for x, y in zip(center, measured[:3])))
        maximum_velocity_error = max(maximum_velocity_error, max(abs(x - y) for x, y in zip(velocity, measured[3:])))
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
        assert node == next_node and node < node_counts[body]
        values = vector(row, ["x_m", "y_m", "z_m", "vx_m_s", "vy_m_s", "vz_m_s"])
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

    impulse = float(expected.ground_impulse_file.read_text())
    time = (expected.frames - 1) * dt
    momentum_change = sum(body_masses[body] * (trajectory[body, expected.frames - 1][1][1]
                                               - trajectory[body, 0][1][1]) for body in range(count))
    residual = momentum_change - (sum(mass * gravity for mass, gravity in zip(body_masses, gravity_y)) * time + impulse)
    assert abs(residual) < 1e-8
    return {"bodies": count, "frames": expected.frames, "particle_rows": record_count,
            "nodes": sum(map(len, positions)), "cells": sum(cells), "reference_volumes_m3": volumes,
            "minimum_y_m": minimum_y, "maximum_center_error_m": maximum_center_error,
            "maximum_velocity_error_m_s": maximum_velocity_error,
            "initial_energy_J": energies[0], "final_energy_J": energies[-1],
            "maximum_energy_J": max(energies),
            "energy_loss_fraction": 1.0 - energies[-1] / energies[0] if energies[0] else None,
            "body_masses_kg": body_masses, "scene_source": settings.get("scene_source"),
            "vertical_momentum_change_Ns": momentum_change, "ground_impulse_Ns": impulse,
            "vertical_momentum_balance_error_Ns": residual}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--bodies", type=int, required=True)
    per_body = lambda value: [int(item) for item in value.split(",")]
    parser.add_argument("--nodes", type=per_body, required=True, help="Nodes per body, or comma-separated counts")
    parser.add_argument("--cells", type=per_body, required=True, help="Tetrahedra per body, or comma-separated counts")
    parser.add_argument("--frames", type=int, required=True)
    parser.add_argument("--ground-impulse-file", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    arguments = parser.parse_args()
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
    result = verify(arguments.directory, arguments)
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


if __name__ == "__main__":
    main()
