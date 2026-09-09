# Motion QA

Use the existing desktop nREPL; no second game or JVM is needed. Select the
driver chase camera, then evaluate:

```clojure
(racing-game.motion-qa/start!) ; true: capture queued, false: already busy
(racing-game.motion-qa/ready?) ; true after 40 seconds
(racing-game.motion-qa/export! "build/motion.csv")
```

The render thread records actual monotonic frame times, simulation ticks,
vehicle pose, camera pose and projected vehicle position. Export locks the
completed buffer; another client cannot restart capture during the read.

Record the **specific game window** simultaneously. On macOS 15+, compile
the QA-only ScreenCaptureKit recorder and pass the verified CG window ID:

```sh
swiftc -parse-as-library tools/record_window.swift -o /tmp/racing-record-window
/tmp/racing-record-window WINDOW_ID /tmp/unique-race-recording.mov 40
```

It records only that window without changing focus, audio or microphone input,
and refuses to overwrite a file. Requested 60 FPS is a capture ceiling, NOT
proof the renderer achieves 60 FPS; inspect video timestamps and native frame
times. The `screencapture -v -l` combination ignored window selection on this
machine and must not be used as window-specific evidence.
If the Metal window is fully occluded, this machine can return a stale still
and ScreenCaptureKit can fail with ReplayKit error -5822 (first sample buffer).
Bring the game window forward, verify a changing HUD/camera against live data,
and record again; do not accept stale images or claim a failed recording passed.
Inspect sequential frames around the events flagged below.
Check `(racing-game.desktop/desktop-snapshot)` first: a finished race still
renders frames but does not exercise moving wheels. To start a new race, use
`(racing-game.desktop/request-race-reset!)`; it queues the reset on the frame
thread instead of resetting live native state from the nREPL thread.

```sh
python tools/analyze_motion.py build/motion.csv --output build/motion.json
python tools/test_analyze_motion.py
```

Python needs NumPy. Angles are unwrapped and timestamped samples are resampled
at the median frame interval before a Hann-windowed FFT of angular velocity.
Units: heading/yaw in radians, their velocity in rad/s and acceleration in
rad/s². Screen positions are normalized device coordinates, not pixels.
The report includes frame-time outliers and largest individual heading jumps.

Extended captures include `world`, `racer`, zoom, presentation phase and all
five raw/rendered rigid-body poses. `raw_0_*` is the authoritative chassis;
`raw_1_*` through `raw_4_*` are wheel bodies, and `shown_*` are their interpolated
presentation poses. Body position/velocity units are metres and seconds;
quaternions use xyzw and angular velocities use rad/s. Camera coordinates are
kilometres. `body_screen_*` projects actual rendered chassis height, unlike the
older `screen_*` probe which uses camera height.

The analyzer splits physical reports when the world or selected driver changes,
reports wheel centers relative to the chassis, axle tilt and measured spin.
These opt-in render-rate snapshots cannot resolve the 3840Hz solver; do not
infer full suspension dynamics or wheel RPM from aliased video/orientation
differences. Camera changes flag an unmatched capture. Real suspension travel,
banking, steering and collisions are not automatically defects. The capture
lock protects all telemetry buffers; publishing instrumentation does not reset
the race, but take a NEW capture before exporting the new columns.

High-frequency power alone does **not** prove jitter: collisions, deliberate
steering, camera cuts and pit transitions must be distinguished in the video
and telemetry. Captures of different race segments are not controlled A/B
tests. For an identical driven trace evaluated by two route implementations,
write columns `time,old_heading,new_heading` and use `--matched-headings`.
This isolates route-heading changes; it is not a replay of the entire physics
simulation or a claim that all camera modes are fixed.

Wheel meshes are authored and rebuilt in Blender using
`(voxel/rebuild-wheels!)`, `(voxel/export!)`, then `(modeling/save!)` in the
Blender Basilisp REPL. The rebuild preserves previous objects in hidden history
collections. Each wheel uses an axle-centered isotropic voxel grid; exported
`wheel-axes.edn` carries the pivot, rolling radius and tire width. Do not infer
the rotation pivot from the whole mesh's bounds: asymmetric hubs can shift it.
The lightweight `racing-game.vehicle-spec` imports these physical dimensions
without importing the render meshes.

Focused regression command while developing Aguafria locally:

```sh
clojure -M:local-aguafria:dev:test --focus racing-game.physics-test --focus racing-game.render3d-test
```

Artifacts belong in ignored `build/`, not source control. Remaining visual,
pit and physics defects are tracked in `../AGENT_TODO.md`.
