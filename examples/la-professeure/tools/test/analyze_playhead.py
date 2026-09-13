"""Measure the blue, full-height playhead in a known video ROI (physical pixels).

Opt-in QA, not a renderer test substitute. Requires ffmpeg/ffprobe and numpy.
Example: python analyze_playhead.py window.mp4 484 730 1284 108
Keep the ROI inside ONE track row; waveform bars must not span its full height.
"""
import argparse
import json
import subprocess

import numpy as np


def scan(video, x, y, width, height):
    info = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=width,height:frame=best_effort_timestamp_time",
        "-of", "json", video,
    ]))
    stream = info["streams"][0]
    assert x >= 0 and y >= 0 and width > 0 and height > 0
    assert x + width <= stream["width"] and y + height <= stream["height"]
    times = [float(frame["best_effort_timestamp_time"]) for frame in info["frames"]]
    process = subprocess.Popen([
        "ffmpeg", "-v", "error", "-i", video, "-an",
        "-vf", f"crop={width}:{height}:{x}:{y}", "-fps_mode", "passthrough",
        "-pix_fmt", "rgb24", "-f", "rawvideo", "-",
    ], stdout=subprocess.PIPE)
    observations = []
    size = width * height * 3
    try:
        while True:
            data = process.stdout.read(size)
            if not data:
                break
            assert len(data) == size, "Truncated decoded frame"
            pixels = np.frombuffer(data, dtype=np.uint8).reshape(height, width, 3).astype(np.int16)
            red, green, blue = pixels[:, :, 0], pixels[:, :, 1], pixels[:, :, 2]
            # Tolerant to H.264 chroma conversion, strict against gray grid/fill.
            blue_line = (red < 100) & (green > 65) & (green < 145) & (blue > 170)
            blue_line &= (blue - red > 90) & (blue - green > 55)
            columns = np.flatnonzero(blue_line.sum(axis=0) >= height * 0.94)
            groups = np.split(columns, np.flatnonzero(np.diff(columns) > 1) + 1)
            runs = [[int(group[0] + x), len(group)] for group in groups if len(group)]
            observations.append(runs)
    finally:
        process.stdout.close()
        code = process.wait()
    assert code == 0, "Video decoder failed"
    assert len(times) == len(observations), "Decoded frames and timestamps differ"
    return times, observations


def summarize(times, observations, left_edge):
    assert len(times) == len(observations)
    assert all(b > a for a, b in zip(times, times[1:])), "Nonmonotonic video timestamps"
    # Exclude the deliberate Stop-to-zero transition from motion jitter checks.
    moving = [i for i, runs in enumerate(observations)
              if len(runs) == 1 and runs[0][0] > left_edge + 4]
    assert len(moving) >= 10, "No sustained visible playhead motion found"
    first, last = moving[0], moving[-1]
    span = observations[first:last + 1]
    positions = [runs[0][0] for runs in span if len(runs) == 1]
    assert len(set(positions)) >= 5, "Stationary line cannot establish playback motion"
    steps = np.diff(positions)
    return {
        "video_frames": len(observations),
        "motion_frames": len(span),
        "motion_seconds": [times[first], times[last]],
        "position_pixels": [positions[0], positions[-1]],
        "missing_line_frames": sum(not runs for runs in span),
        "multiple_line_frames": sum(len(runs) > 1 for runs in span),
        "backward_steps": int((steps < 0).sum()),
        "line_width_pixels": sorted({runs[0][1] for runs in span if len(runs) == 1}),
        "largest_forward_step_pixels": int(steps.max()),
        "distinct_positions": len(set(positions)),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video")
    for name in ("x", "y", "width", "height"):
        parser.add_argument(name, type=int)
    args = parser.parse_args()
    times, observations = scan(args.video, args.x, args.y, args.width, args.height)
    print(json.dumps(summarize(times, observations, args.x), indent=2))
