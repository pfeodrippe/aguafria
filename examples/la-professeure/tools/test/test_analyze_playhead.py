import unittest

from analyze_playhead import summarize


class PlayheadSummaryTest(unittest.TestCase):
    def fixture(self):
        return [i / 60 for i in range(20)], [[[20 + i, 4]] for i in range(20)]

    def test_stable_forward_motion(self):
        times, frames = self.fixture()
        result = summarize(times, frames, 0)
        self.assertEqual(result["backward_steps"], 0)
        self.assertEqual(result["distinct_positions"], 20)
        self.assertEqual(result["line_width_pixels"], [4])

    def test_missing_duplicate_and_reverse_frames_are_reported(self):
        times, frames = self.fixture()
        frames[3] = []
        frames[7].append([50, 4])
        frames[12] = [[25, 4]]
        result = summarize(times, frames, 0)
        self.assertEqual(result["missing_line_frames"], 1)
        self.assertEqual(result["multiple_line_frames"], 1)
        self.assertEqual(result["backward_steps"], 1)

    def test_idle_recording_cannot_pass_as_motion(self):
        for position in (0, 20):
            with self.subTest(position=position), self.assertRaises(AssertionError):
                summarize([i / 60 for i in range(20)], [[[position, 4]]] * 20, 0)


if __name__ == "__main__":
    unittest.main()
