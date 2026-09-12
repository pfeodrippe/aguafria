# Pitoco: scripting and native extensions

A standalone Pitoco executable contains its compiled native engine. No JVM,
nREPL, or AguaFria development runtime is required to run it. There are two
independent extension paths:

- **Native plugins:** write Clojure + AguaFria Zig, Zig, C, or another language
  that can export the SDK's C ABI. Compile a shared library for the host platform.
  Pitoco loads that library and provides host function pointers to it.
- **External scripting:** an ordinary Clojure application owns its own JVM and
  optional nREPL. It sends data/commands to Pitoco. Reloading script definitions
  changes that external program. Replacing compiled native code is a separate
  explicit plugin lifecycle operation or a new standalone engine build.

Both paths address the same native command dispatcher. The initial surface is
playback control, export dispatch, and plugin lifecycle/commands. It does not yet
register new solvers, meshes, materials, render passes, or audio nodes.

## Try the compiled AguaFria plugin

From `examples/field-lab/plugins/rewind`:

```sh
clojure -M:build
```

This produces `build/libpitoco-rewind.dylib` on macOS. Its source is readable
Clojure using `az/defn`; its output is a native library exporting
`pitoco_plugin_v1`. Neither its load callback nor its command callback needs a
JVM. The example's `rewind` command submits a seek-to-zero command to its host.

To enable scripting for a new standalone run, create an empty bridge directory
and set `PITOCO_BRIDGE_DIR` to its absolute path when starting `build/pitoco`.
The directory is optional. No bridge is opened by default.

For the current development host, evaluate once in its existing REPL:

```clojure
(require '[field-lab.live :as live])
(live/enable-scripting! "build/pitoco-bridge")
```

This development convenience queues a native bridge-open command on the UI
thread. It does not make the scripting client depend on this REPL. Standalone
uses the environment option above or the C ABI `PITOCO_BRIDGE` command.

In a **separate program**, using `examples/field-lab/scripting` as a local Clojure
library dependency (or starting `clojure -M:nrepl` in that directory):

```clojure
(require '[pitoco.client :as pitoco])

(def connection
  (pitoco/connect "/absolute/path/to/field-lab/build/pitoco-bridge"))

(pitoco/status connection)

(pitoco/await!
 connection
 (pitoco/load-plugin!
  connection
  "/absolute/path/to/field-lab/plugins/rewind/build/libpitoco-rewind.dylib"))

(pitoco/await!
 connection
 (pitoco/plugin-command! connection "example.rewind" "rewind"))

(pitoco/await!
 connection
 (pitoco/unload-plugin! connection "example.rewind"))
```

`pitoco/seek!` and `pitoco/command!` accept ordinary Clojure data. For example,
`(pitoco/command! connection {:op :pause})`. Supported `:op` values are `:seek`,
`:pause`, `:play`, `:export`, `:stop`, `:load-plugin`, `:unload-plugin`, and
`:plugin-command`. There is no source evaluation inside the transport.

## ABI and ownership

The public header is `native/sdk/pitoco.h`. Structures start with `abi_version`
and `struct_size`. Version 1 accepts structures at least as large as its known
prefix. Plugins must target the host architecture and C calling convention.
The wire transport serializes values, never native structure layouts or pointers.

`pitoco_submit_v1` copies text before returning and may be called from worker
threads. `PITOCO_QUEUED` acknowledges admission and returns a ticket. The bounded
mailbox returns `PITOCO_BUSY` when occupied. `pitoco_result_v1` retains the latest
64 dispatch results within one host lifetime; tickets are not durable across
process restarts. Status is a synchronized copy; it is not a borrowed scene.

Pitoco executes commands and plugin callbacks on the owning thread. Callbacks
must return promptly and must not throw across the C ABI. Plugin command
callbacks return `PITOCO_OK` or a terminal SDK error code; they must translate
child-command admission (`PITOCO_QUEUED`) into their own dispatch result, as the
example does. A plugin may queue a
subsequent host command from its command callback. User UI actions already
pending are preserved; extension dispatch waits for an available frame.
A successful seek/export dispatch means controls were handed to the application
loop. It does not claim the export file has finished writing. Long simulation
jobs need the future job/result API before they can use this contract.

The plugin descriptor and its strings remain library-owned until unload.
`on_load` receives a borrowed, immutable host table and initializes plugin state.
`on_unload` must support partial initialization, stop/join all plugin workers,
release plugin-owned allocations, and relinquish callbacks/host pointers before
returning. The host calls it before closing the library. Native plugins are
trusted in-process code; the ABI does not isolate their memory accesses.

At most 16 plugins are attached. IDs are unique, 1–64 ASCII letters/digits from
`[a-z0-9_.-]`. All lifecycle operations use IDs after load. To replace a build,
unload it, compile to a new artifact path, then load the new artifact. Automatic
state migration and seamless replacement of running solver jobs are not present.

## Optional local transport

The first bridge is a small, local, single-request file mailbox. A host lock
prevents two hosts owning the same directory; client locks serialize cooperating
scripting programs. Clients publish `request` by atomic rename. The host writes
`reply` by atomic rename. The client reads it as EDN and removes it. UTF-8 framing
has four newline-terminated fields: `PITOCO/1`, operation, integer, and text.
Command text is limited to 4096 bytes. Requests larger than 8192 bytes or with
invalid framing are rejected. `status` and `result` are read-only operations;
native command operation numbers are defined in the SDK header.

A timeout leaves request/reply artifacts for diagnosis and is an uncertain
outcome, not an instruction to retry automatically. This prototype is intended
for local interactive commands, not audio callbacks or bulk mesh transfer.
Durable job history, high-throughput binary buffers, richer diagnostics, and
additional transports can be added behind the same versioned operation model.

Development generations link one shared native extension host to retain its
plugin registry across AguaFria recompiles. Standalone statically includes the
same implementation. Editing the host's own C++ ABI/state requires unloading its
plugins and a controlled host transition; ordinary AguaFria recompilation is not
plugin migration.

## Verification

Run `./test/extensions.sh` from the example directory. It compiles the actual
AguaFria plugin, a deliberately incompatible plugin, and a native test host.
Native assertions cover ABI rejection, queue backpressure, copied payloads,
concurrent submission, playback bounds, partial-initialization cleanup, lifecycle,
duplicate IDs, callback
submission, completion retention, and repeated load/unload. The independent
Clojure client then controls that native process, checks its results, and verifies
that its own JVM has not loaded `aguafria.zig`. No GUI process is launched.

Verified on 2026-09-12: the separate-process Clojure suite passed 23 assertions,
including malformed/truncated/oversized wire rejection. The same compiled plugin
was attached to the existing Pitoco window (PID 37705), rewound its 241-frame
refined three-body FEM cache, survived an application development recompile,
rewound again, and was unloaded. The cache was returned to tick 100. The standalone
`build/pitoco` and `build/Pitoco.app` built successfully; dependency inspection
found system/graphics libraries and no JVM or AguaFria development runtime.
The plugin itself links only the system library. Standalone was built without
opening another GUI instance.

The full simulation regression suite also passed 45 tests / 364 assertions after
integration. External-controller replay through ticks 0, 180, and 100 produced
byte-identical trajectory, particle, reference and connectivity exports. The
refreshed exports preserve 17 significant digits and all 148,215 particle rows;
verification records are in `exports/coupled-live-17/`.
