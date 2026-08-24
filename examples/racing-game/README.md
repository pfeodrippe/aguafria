# Aguafria LLM Racing

Eight Flecs racers run a 120 Hz native simulation while a real, local Granite
350M model makes independent tactical decisions on fixed native workers that
share one immutable model.
The inference engine, quantized kernels, scheduler, simulation, and Vulkan
presentation are written with Aguafria forms and become ordinary optimized Zig
in the standalone build.

The current geometric vertical slice has six item roles: a moving bolt, a
persistent rear trap, a short boost, a one-hit shield, radial disruption, and a
position surge. Bolts and traps use a fixed pool of stable Flecs entities, so
spawning, movement, collision, rendering, and expiry allocate nothing during a
race.

## First run

Run commands from this directory.

```sh
clojure -M -e "(require '[racing-game.model :as model]) (model/fetch!) (shutdown-agents)"
clojure -M -m racing-game.build prepare
clojure -M:desktop
```

The desktop command prints an nREPL port before opening the native window.
Connect Calva or CIDER to it. The JVM is the development host; inference,
simulation, Flecs, and rendering remain native. Development dylibs use Zig
`ReleaseFast` as well, retaining hot publication without turning model kernels
into slow debug code.

Useful forms:

```clojure
(require '[racing-game.core :as game]
         '[racing-game.simulation :as simulation]
         '[racing-game.tournament :as tournament]
         '[racing-game.worker :as worker])

(game/status)
(game/racers)
(game/hazards)
(game/worker-status)
(worker/set-sampling-temperature! 0.35)
(game/decision-log 0)
(game/decision-logs 0 8)
(game/decision-trace 0)
(game/decision-outcome 0)
(simulation/toggle-paused!)
```

Press `P` to pause, `R` to reset the race, and `D` to toggle the native
cognition overlay. Its eight rows show desired speed, average inference
latency, LLM/fallback source, pending work, and planned item use; thin lines on
the track show each actor's lane goal and selected opponent. Exact prompts,
tokens, schema versions, timings, and validation outcomes remain available from
`decision-log` and `decision-logs`.

## Live native edit

Open `src/racing_game/worker.clj`, change the `0.076` base in
`action-target-speed`, and evaluate only that `az/defn` with Calva/CIDER. Do not
reload the namespace. The model, native thread, race, Flecs world, and JVM stay
alive; the next completed decision uses the new speed mapping.

For a windowless proof in a normal nREPL:

```clojure
(game/start-headless!)
(game/step-until-llm! 5000)
(game/stop-headless!)
```

`step-until-llm!` returns the real Flecs racer state, prompt/response telemetry,
worker counters, and native timing for the first newly installed decision.
Observation schema `R2` and action schema `1` are carried through the request,
result, compatibility check, and decision log, so an incompatible hot edit is
rejected instead of being installed silently.
R2's eight positional tokens include the explicitly perceived target and one of
three persona classes in a single packed category; target selection is no longer
invented inside the decoder.
Action selection temperature-samples only the eight legal A–H logits. Every
racer owns a deterministic native RNG stream, and every telemetry event records
the exact post-draw state. This makes model-derived diversity reproducible and
keeps arbitrary text or functions outside the action surface.
`decision-trace` turns those recorded fields into a readable observation,
intent, validation, sampling, and timing map; it explains only structured game
state and never fabricates hidden chain-of-thought. Its outcome is evaluated
over a fixed one-second native horizon and reports exact item consumption,
unshielded hits, progress gain, and rank gain. Per-racer lifetime aggregates
remain complete even after old detailed events leave the bounded ring.

Decision records also carry the model/head fingerprints, adapter training
revision, race epoch, and enqueue/install ticks needed for reproducible native
playback. Capture and replay without invoking the LLM again:

```clojure
(def replay (game/capture-replay))
(game/load-replay! replay)
(simulation/step-many! 1200)
(game/replay-status)
```

Replay mode installs the validated lane, pace, target, and item intent on its
original 120 Hz tick while running the same Flecs physics/combat path. The test
suite proves exact snapshot and per-racer parity across a 1,200-tick capture;
incompatible model/head provenance or schemas are rejected before playback.
The replay test also proves exact causal-outcome parity.

Run accelerated deterministic baseline tournaments directly from the REPL:

```clojure
(def tournament-report
  (tournament/run! {:seeds (range 8) :mode :fallback}))
(:scoreboard tournament-report)
```

The starting grid and pickup stream are permuted by seed. The returned table
contains wins, podiums, points, finish ticks, decision counts, item uses, hits,
and rank/progress gains, with model/head provenance attached. To exercise the
real eight Granite workers instead, call `game/start-headless!` and use
`:mode :live`; that mode deliberately advances at wall-clock 120 Hz rather than
outrunning asynchronous inference.

The logits now come from a racing-trained linear head over all eight per-token
Granite hidden states. The checked-in head is 192 KiB, bound to observation
schema 2/action schema 1, and SHA-256 verified before native startup. Its
deterministic corpus has 144 balanced expert-labelled observations: the current
artifact fits all 120 training examples and scores 22/24 (91.67%) on the held-out
three-per-action split. This is a useful first domain adapter, not a claim that
the larger gameplay/tournament fine-tuning plan is complete.

To reproduce it after changing the schema or teacher:

```sh
clojure -M:train-action-head
```

Native Granite feature extraction is cached below the ignored `build/training`
directory. The release runtime loads only the resulting fixed-layout head; it
contains no trainer, JVM, Python, or scripted post-policy.

## Standalone

```sh
clojure -M:standalone
./build/standalone/racing-game
```

This emits and runs a JVM-free `ReleaseFast` Zig executable. The build folder
contains the pinned GGUF, verified action head, manifest, and compiled shaders;
the build directory and large GGUF are ignored by Git. The small trained head
under `resources/models` is a source artifact.

Repeatable inference probes:

```sh
clojure -J--enable-native-access=ALL-UNNAMED \
  -J-Daguafria.async-compile=false \
  -J-Daguafria.optimize=ReleaseFast \
  -M -m racing-game.worker-performance-probe
clojure -M -m racing-game.build inference-probe
./build/standalone/inference-probe
```

The current measured limitations and next batching/Metal milestones are kept in
[`../../RACING_LLM_IMPLEMENTATION_PLAN.md`](../../RACING_LLM_IMPLEMENTATION_PLAN.md).
