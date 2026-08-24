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
         '[racing-game.performance :as performance]
         '[racing-game.simulation :as simulation]
         '[racing-game.track :as track]
         '[racing-game.tournament :as tournament]
         '[racing-game.worker :as worker])

(game/status)
(game/racers)
(game/hazards)
(game/observation 0) ; exact bounded semantic state currently visible to racer 0
(game/observations)
(game/worker-status)
(simulation/cadence-summary) ; load-sensitive ordinary cadence; fixed step stays 120 Hz
(worker/set-sampling-temperature! 0.35)
(game/decision-log 0)
(game/decision-logs 0 8)
(game/decision-log 0 {:include-raw? true}) ; encoded prompt/tokens only on demand
(game/decision-trace 0)
(game/decision-trace 0 0 {:include-raw? true})
(game/configure-racer! 0 {:item :bolt :progress 0.20})
(game/configure-intent! 0 {:item-action :use :target 1})
(simulation/step!)
(require '[racing-game.log :as race-log])
(race-log/write!) ; compact human .txt + semantic .edn, 1 completed decision per racer
(race-log/write! {:per-racer-limit 8}) ; bound a focused export
(race-log/write! {:include-raw? true}) ; opt in to raw prompts/tokens/provenance
(require '[racing-game.monitor :as monitor])
(monitor/status) ; decoded eight-racer ImGui state from the same native telemetry
(monitor/set-raw-protocol-visible! true) ; explicit opt-in; false clears raw bytes again
(game/decision-outcome 0)
(simulation/toggle-paused!)
(simulation/human-control-snapshot)
(simulation/set-human-controlled! true) ; optional racer-0 reference driver
(simulation/set-human-input! 1.0 1.0 0.0 false) ; steer, throttle, brake, item
(track/project 0.25 -0.35) ; nearest progress, signed lane, squared distance
(performance/run-live-window! {:ticks 1200 :seed 0}) ; measured 10-second native run
(require '[racing-game.dataset :as dataset])
(dataset/expert-evaluation) ; transparent teacher vs human acceptable actions
(dataset/model-evaluation!) ; real native Granite/head vs the same golden cases
(dataset/model-rollout-evaluation! {:limit 8}) ; native one-second outcome regret
```

The desktop race starts behind a deterministic three-second native countdown.
Press `P` to pause, `R` to reset with a fresh countdown, `F1` to toggle the
geometric intent overlay, `F2` to hide/show the Dear ImGui cognition monitor,
and `H` to take over or release racer 0. In reference
driver mode, arrows or WASD steer/throttle/brake, Space uses the held item, and
the first GLFW gamepad uses its left stick, triggers, and A button. The default
remains eight independent AI racers. Racer 0 receives an extra contour only
while human control is active.

The geometric overlay's eight rows show desired speed, average inference
latency, LLM/fallback source, pending work, and planned item use; thin lines on
the track show each actor's lane goal and selected opponent. `decision-log` and
`decision-logs` return semantic records by default. Exact encoded prompts,
tokens, sampler state, and provenance are available only with
`{:include-raw? true}`. The Dear ImGui monitor uses the same bounded telemetry
for actor selection, filters, semantic decision summaries, latency history,
deadlines, validation, and queue state. Its raw-protocol panel is unchecked by
default, and raw bytes remain zero in the monitor snapshot until explicitly
enabled. Set `AGUAFRIA_RACING_MONITOR=0` (or
`-Daguafria.racing.monitor=false`) to start development without it.

The normal text artifact reads as `Saw / Chose / Result after 1 second` in ordinary
race language. If inference is late, the same entry instead says why the safe
fallback ran and reports only the state actually recorded for that fallback;
it does not print fake `unknown` model observations. Compact protocol strings,
character meanings, and token IDs never appear unless raw export is requested.

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
Observation schema `R3` and action schema `1` are carried through the request,
result, compatibility check, and decision log, so an incompatible hot edit is
rejected instead of being installed silently.
R3's eight positional tokens pack rank/lap and item/urgency so the fixed token
budget also includes selected-opponent distance, left/same/right lane relation,
and local hazard/stun/shield status. Target selection and combat perception are
therefore visible model inputs rather than decisions invented inside the
decoder.
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

The same parity gate runs without the JVM:

```sh
clojure -M -m racing-game.build replay-parity-probe
./build/standalone/replay-parity-probe
```

Both development and standalone require the canonical 1,200-tick fixture to
capture 318 intents and produce gameplay-state fingerprint
`0xee80c9bb65981a55` before and after replay. The hash covers every authoritative
racer/brain/hazard field and aggregate combat counter, but deliberately excludes
addresses, padding, worker timings, and replay bookkeeping.

Run accelerated deterministic baseline tournaments directly from the REPL:

```clojure
(def tournament-report
  (tournament/run! {:seeds (range 8) :mode :fallback}))
(:scoreboard tournament-report)
(get tournament-report :persona-evaluation)
(tournament/report-summary tournament-report)
```

The starting grid and pickup stream are permuted by seed. The returned table
contains wins, podiums, points, finish ticks, decision counts, item uses, hits,
rank/progress gains, and a per-persona routine pace distribution, with
model/head provenance attached. Persona competence is reported only from real
Granite decisions; a fallback-only tournament explicitly says it has no model
evidence. Raw prompt bytes and token IDs never enter this report. To exercise
the real eight Granite workers instead, call `game/start-headless!` and use
`:mode :live`; that mode deliberately advances at wall-clock 120 Hz rather than
outrunning asynchronous inference.

For an apples-to-apples behavior comparison, let Aguafria run the transparent
native controller and the real Granite policy over the same deterministic seed.
The helper owns temporary headless workers and refuses to disturb an existing
development race:

```clojure
(def paired (tournament/run-paired! {:seeds [0]}))
(get paired :comparison)
```

The comparison keeps finish time, rank changes, item use, hits, contacts,
invalid actions, and deadline misses separate instead of hiding them behind one
arbitrary score. A negative finish-tick delta favors the LLM; a positive rank
improvement favors the LLM. Add seeds explicitly for a longer bake-off.

The logits now come from a racing-trained linear head over two ordered Granite
states. All eight observation fields are retained: four category embeddings at
a time are bound to their field positions, fused at stable RMS scale, and sent
through all 32 Granite layers. This lowers one decision from eight model steps
to two without adding a direct-policy bypass. The checked-in head is 192 KiB,
bound to observation schema 3/action schema 1, and SHA-256 verified before
native startup. Its deterministic corpus has 384 balanced expert-labelled
observations plus 29 rare tactical/rollout anchors. The current artifact fits
361/365 training examples, scores 44/48 (91.7%) on the untouched held-out
six-per-action split, and passes 18/18 human-authored acceptable-action
situations through the real native Granite graph. This is a useful first domain
adapter, not a claim that the larger gameplay/tournament fine-tuning plan is
complete.

On the measured M3 Max, fresh warm eight-racer runs now take about 135–149 ms
and sustain about 53.5–59.1 complete decisions/s, versus about 513 ms and 15.6
decisions/s before field fusion. Individual decisions take about 119–122 ms,
enough for the current eight-racer 3-Hz ordinary cadence. Native telemetry keeps
the ABI field name `tokens_per_second` for compatibility, but its value now
means real Granite model steps per second; the human log labels it accordingly.
A 400-decision sustained run measured 120.98-ms decision p95, 0.72-ms queue
p95, and 144.75-ms eight-racer batch p95 with all 400 actions accepted. A
separate paced ten-second simulation measurement held 119.95 Hz with zero
deadline misses; the native Flecs step was 0.069 ms p95 and never exceeded the
8.33-ms frame budget.

To reproduce it after changing the schema or teacher:

```sh
clojure -M:train-action-head
clojure -M:generate-decision-corpus
```

The renderer-free corpus command captures 5,000 deterministic observations
through the real native Flecs simulation and public perception API. It refuses
to write if any racer, rank, lap, persona, item, lane relation, tactical status,
urgency state, or A-H teacher action is missing. Seven explicitly labeled
native item anchors cover inventory states that normal fallback play may consume
between sampling ticks. The current corpus is written under ignored
`build/training/` with SHA-256
`adfdef39e18185518ee7d3ae7c92e92497911b9cd27aba3df4c193d7cdf3817d`.

`model-rollout-evaluation!` goes beyond classifying frozen observations. It
recreates each selected corpus seed/tick, runs the chosen and acceptable A-H
actions through the real Flecs simulation for one second, and reports progress,
rank, hits, contacts, and item use. Its documented regret utility values a rank
at 5% lap progress, an unshielded hit at 3%, and item spending at -0.5%; every
raw outcome dimension remains present so the utility cannot hide an undesirable
tradeoff. Revision 6 accepts all 8/8 diverse diagnostic rows; average regret is
0.01434 and maximum regret is 0.08218 because an acceptable action need not be
the highest-utility acceptable action in that one-second horizon. This is a
small deterministic policy diagnostic, not a substitute for the planned
multi-seed tournament bake-off.

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
the build directory and large GGUF are ignored by Git. Exact upstream license
texts and `THIRD_PARTY_NOTICES.md` are packaged under `build/standalone/licenses`.
The small trained head under `resources/models` is a source artifact.

The standalone demonstrator opens the human-readable cognition monitor by
default. Select any racer in its table to follow the latest `Saw / Chose /
Result` explanation. The expanded legend explains the geometric HUD: left rows
are racers 0-7 and their speed/latency/pending/item-use state; right boxes are
1st-8th place with lap-progress width. Press `F2` to hide/show the monitor.
Encoded prompts and token IDs remain absent unless `Show raw protocol` is
explicitly checked.

Validate the complete offline model bundle without opening a window or starting
a JVM:

```sh
clojure -M -m racing-game.build asset-probe
cd build/standalone
./asset-probe
```

The probe checks GGUF structure, exact byte count, model SHA-256, action-head
schema, and action-head SHA-256. Missing or incompatible assets produce a short
error naming the expected path and remediation instead of an assertion trace.
The runtime uses a read-only private mapping for the GGUF on supported hosts and
retains a verified owned-read fallback; model unload always releases the exact
storage kind it acquired.

Repeatable inference probes:

```sh
clojure -J--enable-native-access=ALL-UNNAMED \
  -J-Daguafria.async-compile=false \
  -J-Daguafria.optimize=ReleaseFast \
  -M -m racing-game.worker-performance-probe sustained 50
clojure -M -m racing-game.build inference-probe
cd build/standalone
./inference-probe
```

The current measured limitations and next batching/Metal milestones are kept in
[`../../RACING_LLM_IMPLEMENTATION_PLAN.md`](../../RACING_LLM_IMPLEMENTATION_PLAN.md).
