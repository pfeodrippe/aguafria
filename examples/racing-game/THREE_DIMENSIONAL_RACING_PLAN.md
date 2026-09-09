# 3D racing desktop upgrade

## Camera and spectator workflow (current priority)

- [ ] Default to the leading group, with close 18x circuit-view zoom (3x was
  visually too distant); follow its
  physical position across the lap seam without altering vehicle scale.
- [ ] Wheel/trackpad and +/- adjustable 1x–80x zoom; 0 follows leaders,
  1–8 follows a driver, F3 switches overview/follow.
- [ ] Persistent north-up minimap at top right with racer colors, leader and
  pit markers. Move standings below it and preserve radio/log access.
- [ ] Verify these controls visually in the actual foreground live window and
  ReleaseFast. An occluded macOS window capture can be stale even while native
  frame/simulation counters advance; do not misdiagnose that as failed reload.

- [x] Replace Blender 4.2.1 with official 5.2.1 ARM64; verify checksum.
- [ ] Create editable low-poly kart and pit-garage meshes in Blender, with a
  reproducible exporter and checked-in runtime geometry (no Blender runtime).
- [ ] Add a native Aguafria 3D renderer: orthographic camera, lighting, Vulkan
  depth, solid road, raised curbs, pit buildings, and individually colored karts.
- [ ] Preserve the authoritative Flecs simulation, collisions, tire wear,
  four AI teams, eight drivers, item effects, replay, and cognition monitor.
- [ ] Validate camera aspect ratio, finite vertices, depth and buffer bounds.
- [ ] Run desktop in nREPL, inspect screenshots and interactions, and demonstrate
  a live visual edit without restarting the race.
- [ ] Build/run ReleaseFast, check the real window, and run native regressions.

The simulation remains planar kart physics; this change introduces genuine 3D
geometry/presentation, not jump physics or a new vertical collision model.
Blender is an authoring tool only. Both runtime modes consume the same exported
meshes and native renderer. Other examples are not modified.

## Model quality and pit-lane follow-up

- [x] Research recent reference → Blender → game workflows and establish a
  kart concept with clear silhouette, tapered bodywork and detailed wheels.
- [ ] Replace blockout meshes with modeled body panels, slick tires, alloy
  spokes, cockpit/driver, engine and supported aerodynamic wing.
- [ ] Export split/smooth corner normals; compare actual Blender render against
  the concept and check readability at gameplay scale.
- [ ] Frame the complete circuit without clipping; separate the four pit bays.
- [ ] Replace the existing pit snap (`progress = box`, `lane = 0.215`) with
  continuous entry, braking, stationary service and controlled exit.
- [ ] Test pit position continuity, stationary service and release; show the
  complete stop in the live game and validate the updated release.
- [ ] Install/use basilisp-blender: a separate loopback Blender nREPL for live
  modeling, saved editable .blend scene and exported native geometry.
- [ ] Add short-lived racer-colored speech balloons for actual driver/team
  radio events; retain complete history and avoid obscuring the racing line.
- [ ] Improve scene composition, pit signage and track surroundings, then
  inspect Blender and native game screenshots at normal gameplay size.
- [ ] Export vehicle, wheels and driver as independent meshes/entities; add
  bounded crash/detachment state and destruction/reset tests.
- [ ] Author voxel-style car and pilot parts in Blender, with flat face normals,
  independent damage groups and a measured native geometry budget. Rendering
  voxels is not a substitute for tested collision and fragmentation behavior.
- [ ] Replace the overlapping canopy placeholders with a motorsport pit complex:
  enclosed garage row behind a working apron, four marked service boxes,
  unobstructed fast lane, pit wall, controlled entry and merge exit. Model the
  buildings in Blender and share the route between physics and rendering.
- [ ] Default live simulation to 1x. Schedule planned AI at track markers,
  preserve urgent reactions and continue valid intents between model replies.
- [ ] Replace abstract kart-scale units with a measured 4,309-metre circuit,
  arc-length progress, metre-sized vehicles/structures and km/h telemetry.
  Calibrate acceleration, braking, cornering and pit-lane limits rather than
  only relabeling the current normalized speed. Add follow/overview cameras.
- [ ] Build coherent grandstands, barriers, catch fencing and pit facilities
  in Blender; validate real-time shader lighting/materials against Blender
  renders. A concept image is a target, not evidence of runtime quality.

## Race control and model-authored radio

- [ ] Add green, local/sector yellow, double yellow, red, blue and chequered
  race-control states, with explicit incident locations and visible marshals/HUD
  indicators. Add safety-car/virtual-safety-car operation as a separate phase.
- [ ] Enforce deterministic safety constraints: yellow-sector speed reduction
  and no overtaking, safe red-flag stopping, controlled restart and recorded
  penalties. Model output must not bypass these race-control invariants.
- [ ] Include flags, incidents, rival actions, tire/damage state and received
  radio in plain-language observations for each driver and team model.
- [ ] Let the models decide their tactical requests and radio speech, including
  complaints about another driver, defense of their actions, pit requests,
  team orders and acknowledgements. Do not substitute scripted complaints for
  model responses or invent hidden motives in the log formatter.
- [ ] Route messages with sender, recipients, simulation timestamp and related
  incident/decision IDs. Show short readable balloons plus the complete radio
  history; label model speech distinctly from official race-control messages.
- [ ] Bound message frequency and context history; record inference latency,
  rejected/invalid actions and delivered messages in deterministic replay.
- [ ] Test crash → yellow → clearance, escalating red, no-overtake enforcement,
  restart order, delayed model replies and visible/logged radio delivery.

## Current verification checkpoint

- [x] Fix hidden-collection Blender transforms: use authored object transforms,
  preserve wheel rotations/helmet scale, and retain malformed drafts hidden.
- [x] Export six independent voxel meshes, with the visor layered after the
  helmet; budget all eight cars, garages and planar shadows below 131,072 vertices.
- [x] Publish corrected meshes and warm sun/cool ambient shading into the same
  running game JVM and inspect a captured native game window.
- [x] Create an editable Interlagos-inspired traced spline and a Blender road
  inspection scene: 4,309m centerline and 13m surface. Not survey-accurate.
- [x] Integrate the circuit with physical race/pit motion and follow cameras.
  Live race completed with eight finishers and six pit stops; 135 recorded
  contacts are excessive and remain a handling/damage calibration issue.
- [ ] Replace planar projected car shadows with scene shadow mapping and
  per-fragment material lighting. Blender's studio lights are not exported
  automatically into Vulkan, and current native shading is not PBR.
- [x] Rebuild the corrected model/planar-lighting ReleaseFast executable from
  the live JVM; capture its running race and radio UI, hide the detailed panel
  and inspect all eight cars on track. This is not validation of the pending
  Interlagos integration or full material/shadow-mapping work.
- [x] Pass five mesh/frame/pit tests (81 assertions) plus the native circuit
  sampling test (13 assertions). Preserve a bounds guard after the GPU stream.
- [ ] Buffer quick keyboard press/release edges: a held F2 worked during native
  UI testing while short synthetic presses were missed. Do not depend solely
  on the key being down at the next rendered frame.

References: [reference-driven workflow](https://www.reddit.com/r/OpenAI/comments/1wa4pjs/astra_image_gen_blender_threejs/),
[SuperTuxKart modeling guidance](https://supertuxkart.net/Making_Karts:_Modeling).
Pit reference: [F1 pit-stop anatomy](https://www.formula1.com/en/latest/article/anatomy-of-a-pit-stop-how-do-f1-teams-service-their-cars-in-less-than-two.5p9LNdd8XJdvP4mRsXoGsB).
Scale reference: [official Interlagos circuit](https://autodromodeinterlagos.prefeitura.sp.gov.br/circuito), 4,309 metres.
