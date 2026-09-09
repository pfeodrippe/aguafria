"""Synthetic controls for the motion diagnostic; no game or recording needed."""
import unittest
import numpy as np
from analyze_motion import sampling_phase, spectrum, rotate_vectors, body_motion


class SamplingPhaseTest(unittest.TestCase):
    def setUp(self):
        self.time = np.arange(1, 500) / 34.0
        self.ticks = np.floor(self.time * 120)

    def test_fixed_step_staircase(self):
        relative_position = self.ticks / 120 - self.time
        report = sampling_phase(self.ticks, self.time, relative_position, 120)
        self.assertGreater(report["explained_variance"], .999999)

    def test_interpolated_constant_speed_has_no_jitter(self):
        report = sampling_phase(self.ticks, self.time, np.zeros(499), 120)
        self.assertEqual(report["screen_delta_rms"], 0)
        self.assertIsNone(report["correlation"])

    def test_pause_and_reset_are_excluded(self):
        ticks = self.ticks.copy()
        ticks[100:] -= ticks[100]
        ticks[200:205] = ticks[199]
        report = sampling_phase(ticks, self.time, np.zeros(499), 120)
        self.assertGreaterEqual(report["excluded_intervals"], 6)

    def test_slow_motion_uses_wall_time_rate(self):
        ticks = np.floor(self.time * 30)
        relative_position = ticks / 30 - self.time
        report = sampling_phase(ticks, self.time, relative_position, 30)
        self.assertGreater(report["explained_variance"], .999999)

    def test_stationary_signal_has_no_spectral_peak(self):
        report = spectrum(np.zeros(100), 1 / 34)
        self.assertIsNone(report["peak_frequency_hz"])
        self.assertEqual(report["velocity_rms"], 0)

    def test_quaternion_rotation_round_trip_and_sign(self):
        q = np.array([[0, 0, np.sqrt(.5), np.sqrt(.5)]])
        v = np.array([[1., 0., 2.]])
        rotated = rotate_vectors(q, v)
        np.testing.assert_allclose(rotated, [[0, 1, 2]], atol=1e-12)
        np.testing.assert_allclose(rotate_vectors(-q, v), rotated, atol=1e-12)
        np.testing.assert_allclose(rotate_vectors(q, rotated, True), v, atol=1e-12)

    def test_invalid_quaternion_is_rejected(self):
        with self.assertRaises(ValueError):
            rotate_vectors(np.zeros((1, 4)), np.ones((1, 3)))

    def test_rigid_wheel_offsets_and_fast_spin_survive_camera_motion(self):
        fields = ["world", "racer", "tick", "time", "zoom"]
        fields += [f"raw_{part}_{f}" for part in range(5)
                   for f in ["x", "y", "z", "vx", "vy", "vz", "qx", "qy", "qz", "qw", "wx", "wy", "wz"]]
        rows = np.zeros(100, dtype=[(f, float) for f in fields])
        rows["time"] = np.arange(100) / 34
        rows["tick"] = np.arange(100) * 4
        rows["world"] = 1
        rows["world"][50:] = 2  # Do not combine native world generations.
        rows["zoom"] = np.linspace(10, 50, 100)
        angle = np.linspace(0, 3, 100)
        q = np.column_stack([np.zeros(100), np.zeros(100), np.sin(angle/2), np.cos(angle/2)])
        for part in range(5):
            for i, field in enumerate(["qx", "qy", "qz", "qw"]):
                rows[f"raw_{part}_{field}"] = q[:, i]
            offset = rotate_vectors(q, np.tile([part, part * .5, 0], (100, 1)))
            omega = rotate_vectors(q, np.tile([0, 180, 0], (100, 1)))
            for i, field in enumerate(["x", "y", "z"]):
                rows[f"raw_{part}_{field}"] = offset[:, i]
                rows[f"raw_{part}_w{field}"] = omega[:, i]
        result = body_motion(rows)
        self.assertEqual(len(result["segments"]), 2)
        for segment in result["segments"]:
            for wheel in segment["wheels"]:
                np.testing.assert_allclose(wheel["chassis_relative_center_range_m"], 0, atol=1e-12)
                np.testing.assert_allclose(wheel["spin_rad_s_range"], [180, 180], atol=1e-12)


if __name__ == "__main__":
    unittest.main()
