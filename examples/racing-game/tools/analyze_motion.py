"""Analyze native frame-thread CSV, not jittery nREPL polling.

Usage: python analyze_motion.py capture.csv [--output report.json]
Requires numpy. Unwrap angles, resample to the median frame interval, then
measure Hann-windowed angular-velocity FFT power. High-frequency power is a
diagnostic, not proof of a defect: collisions and real steering also produce it.
"""
import argparse
import json
from pathlib import Path
import numpy as np


def spectrum(values, dt):
    velocity = np.gradient(values, dt)
    centered = velocity - velocity.mean()
    power = abs(np.fft.rfft(centered * np.hanning(len(centered)))) ** 2
    frequency = np.fft.rfftfreq(len(centered), dt)
    total = float(power[1:].sum())
    high = (frequency >= 4) & (frequency <= min(30, .5 / dt))
    index = 1 + int(np.argmax(power[1:]))
    return {"velocity_rms": float(np.sqrt(np.mean(velocity**2))),
            "acceleration_rms": float(np.sqrt(np.mean(np.gradient(velocity, dt)**2))),
            "peak_frequency_hz": float(frequency[index]) if total > 1e-30 else None,
            "4_to_30_hz_power_fraction": float(power[high].sum() / max(total, 1e-30))}


def sampling_phase(ticks, times, screen, tick_hz):
    """Test render/fixed-step phase coupling; correlation is not physical causation.

    Pauses, resets and capped catch-up frames are excluded. Use the effective
    wall-time tick rate (e.g. 30 for 120Hz simulation at 4x slow motion).
    """
    tick_delta = np.diff(np.asarray(ticks, dtype=float))
    gaps = np.diff(times)
    phase = tick_delta - tick_hz * gaps
    delta = np.diff(screen)
    valid = (tick_delta >= 0) & (np.abs(phase) <= 1.01)
    # One zero-tick frame is normal when rendering faster than physics. Only
    # prolonged stationary tick runs are pauses/stalls, not sampling phase.
    edges = np.flatnonzero(np.diff(np.r_[False, tick_delta == 0, False]))
    for start, stop in edges.reshape(-1, 2):
        if gaps[start:stop].sum() >= 2.0 / tick_hz:
            valid[start:stop] = False
    x, y = phase[valid], delta[valid]
    report = {"tick_hz": tick_hz, "samples": int(valid.sum()),
              "excluded_intervals": int((~valid).sum()),
              "screen_delta_rms": float(np.sqrt(np.mean(y*y))) if len(y) else None}
    if len(x) < 10 or np.std(x) < 1e-9 or np.std(y) < 1e-12:
        return dict(report, correlation=None, explained_variance=None)
    correlation = float(np.corrcoef(x, y)[0, 1])
    return dict(report, correlation=correlation, explained_variance=correlation**2)


def rotate_vectors(quaternions, vectors, inverse=False):
    """Unit-quaternion rotation, xyzw order; q and -q are equivalent."""
    q = np.asarray(quaternions, dtype=float)
    norm = np.linalg.norm(q, axis=-1, keepdims=True)
    if np.any(norm < 1e-8) or not np.all(np.isfinite(q)):
        raise ValueError("Invalid captured body quaternion")
    q = q / norm
    xyz = q[..., :3] * (-1 if inverse else 1)
    uv = 2 * np.cross(xyz, vectors)
    return vectors + q[..., 3:] * uv + np.cross(xyz, uv)


def body_motion(a):
    """Physical measurements per uninterrupted world/driver segment.

    Wheel spin is measured from angular velocity, NOT low-FPS orientation
    differences: a fast wheel can rotate several times between video frames.
    Suspension travel includes legitimate loads/contacts; it is not a jitter
    verdict. These render-rate samples cannot resolve the 3840Hz solver.
    """
    if "raw_0_qw" not in a.dtype.names:
        return None
    boundaries = np.flatnonzero((np.diff(a["world"]) != 0) |
                               (np.diff(a["racer"]) != 0) |
                               (np.diff(a["tick"]) < 0)) + 1
    reports = []
    for rows in np.split(a, boundaries):
        if len(rows) < 10:
            continue
        def vector(prefix, fields):
            return np.column_stack([rows[prefix + f] for f in fields])
        t = rows["time"] - rows["time"][0]
        dt = float(np.median(np.diff(t)))
        uniform = np.arange(0, t[-1], dt)
        q = vector("raw_0_", ("qx", "qy", "qz", "qw"))
        position = vector("raw_0_", ("x", "y", "z"))
        up = rotate_vectors(q, np.broadcast_to([0, 0, 1], (len(rows), 3)))
        wheels = []
        for part in range(1, 5):
            prefix = f"raw_{part}_"
            relative = rotate_vectors(q, vector(prefix, ("x", "y", "z")) - position, True)
            wq = vector(prefix, ("qx", "qy", "qz", "qw"))
            angular_velocity = vector(prefix, ("wx", "wy", "wz"))
            axle = rotate_vectors(wq, np.broadcast_to([0, 1, 0], (len(rows), 3)))
            axle_in_chassis = rotate_vectors(q, axle, True)
            spin = np.sum(angular_velocity * axle, axis=1)
            wheels.append({"part": part,
                           "chassis_relative_center_range_m": np.ptp(relative, axis=0).tolist(),
                           "chassis_relative_center_mean_m": relative.mean(axis=0).tolist(),
                           "axle_vertical_component_range": [float(axle_in_chassis[:, 2].min()),
                                                              float(axle_in_chassis[:, 2].max())],
                           "spin_rad_s_range": [float(spin.min()), float(spin.max())]})
        reports.append({"world": int(rows["world"][0]), "racer": int(rows["racer"][0]),
                        "frames": len(rows), "start_time": float(rows["time"][0]),
                        "duration_seconds": float(t[-1]),
                        "zoom_range": [float(rows["zoom"].min()), float(rows["zoom"].max())],
                        "chassis_up_z_range": [float(up[:, 2].min()), float(up[:, 2].max())],
                        "chassis_vertical_velocity_m_s_range": [float(rows["raw_0_vz"].min()),
                                                               float(rows["raw_0_vz"].max())],
                        "chassis_height": spectrum(np.interp(uniform, t, rows["raw_0_z"]), dt),
                        "wheels": wheels})
    return {"sampling_warning": "Render-rate snapshots; wheel spin uses measured angular velocity. "
                                "Contact/suspension motion is not automatically a defect.",
            "segments": reports}


def analyze(path, tick_hz=120.0):
    a = np.genfromtxt(path, delimiter=",", names=True)
    if a.size < 10:
        raise ValueError("Need at least ten frames")
    t = a["time"] - a["time"][0]
    gaps = np.diff(t)
    if not np.all(gaps > 0):
        raise ValueError("Frame timestamps must be strictly increasing")
    dt = float(np.median(gaps))
    uniform = np.arange(0, t[-1], dt)
    report = {"frames": int(a.size), "duration_seconds": float(t[-1]),
              "median_frame_ms": dt * 1000,
              "p99_frame_ms": float(np.quantile(gaps, .99)*1000),
              "max_frame_ms": float(gaps.max()*1000),
              "gaps_over_three_frames": int(np.sum(gaps > 3*dt)),
              "speed_range_kmh": [float(a["speed"].min()*3600), float(a["speed"].max()*3600)]}
    for field in ("heading", "camera_yaw", "screen_x", "screen_y"):
        values = a[field]
        if field in ("heading", "camera_yaw"):
            values = np.unwrap(values)
        resampled = np.interp(uniform, t, values)
        report[field] = spectrum(resampled, dt)
        report[field]["max_frame_delta"] = float(abs(np.diff(values)).max())
        report[field]["unchanged_frame_fraction"] = float(np.mean(abs(np.diff(values)) < 1e-7))
    # Identify discrete car-heading jumps independently of camera filtering.
    angle_delta = np.diff(np.unwrap(a["heading"]))
    jumps = np.argsort(abs(angle_delta))[-8:][::-1]
    report["largest_car_heading_steps"] = [
        {"seconds": float(t[i+1]), "degrees": float(np.degrees(angle_delta[i])),
         "progress": float(a["progress"][i+1]), "tick_delta": int(a["tick"][i+1]-a["tick"][i])}
        for i in jumps]
    report["fixed_step_phase"] = {
        field: sampling_phase(a["tick"], t, a[field], tick_hz)
        for field in ("screen_x", "screen_y")}
    if "world" in a.dtype.names:
        report["mixed_capture_warning"] = bool(np.any(np.diff(a["world"]) != 0) or
                                                np.any(np.diff(a["racer"]) != 0) or
                                                np.ptp(a["zoom"]) > .01)
        report["body_motion"] = body_motion(a)
    return report


def compare_headings(path):
    """Compare the same progress/lane/timestamps evaluated by two route versions."""
    a = np.genfromtxt(path, delimiter=",", names=True)
    t = a["time"] - a["time"][0]
    if len(t) < 10 or not np.all(np.diff(t) > 0):
        raise ValueError("Need ten or more strictly increasing frame timestamps")
    dt = float(np.median(np.diff(t)))
    uniform = np.arange(0, t[-1], dt)
    report = {}
    for field in ("old_heading", "new_heading"):
        values = np.unwrap(a[field])
        report[field] = spectrum(np.interp(uniform, t, values), dt)
        report[field]["maximum_frame_step_degrees"] = float(np.degrees(abs(np.diff(values))).max())
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--matched-headings", action="store_true",
                        help="CSV columns time,old_heading,new_heading (identical driven trace)")
    parser.add_argument("--tick-hz", type=float, default=120.0,
                        help="Effective simulation ticks per wall second; default 120")
    args = parser.parse_args()
    if not np.isfinite(args.tick_hz) or args.tick_hz <= 0:
        parser.error("--tick-hz must be positive")
    report = json.dumps(compare_headings(args.csv) if args.matched_headings
                        else analyze(args.csv, args.tick_hz), indent=2)
    if args.output:
        args.output.write_text(report + "\n")
    print(report)
