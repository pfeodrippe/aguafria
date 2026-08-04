# Ghostty Agua

This project turns the vendored Ghostty Zig project into ordinary Aguafria
Clojure namespaces, exposes the real Ghostty terminal core in one nREPL JVM,
and rematerializes standalone native artifacts with no JVM dependency.

## Commands

Run these from `examples/ghostty`:

```sh
# Zig → Aguafria Clojure (generated only when you request it)
clojure -M:generate

# Same-JVM Calva/CIDER development
clojure -M:nrepl

# Finite native VT/FFM smoke check
clojure -M:check

# Generated Clojure → independent ReleaseFast libghostty-vt
clojure -M:standalone

# Generated Clojure → independent universal Ghostty.app
clojure -M:macos-app

# Focused example tests
clojure -M:test --config-file tests.edn
```

The generated Clojure tree is `../../generated/ghostty`. The independent
source tree and artifacts are under `build/standalone`; it does not read the
original Ghostty `.zig` files while materializing or building.

## nREPL workflow

Connect Calva/CIDER to `clojure -M:nrepl`, open
`ghostty-agua.core`, and evaluate its final `comment` form step by step. It
contains three live edits of increasing depth:

1. change one hand-written Aguafria function body while retaining the existing
   native Ghostty terminal;
2. define new function A and reevaluate existing B to call A;
3. edit converted Ghostty's own focus encoder and observe 73 (`I`) change to
   88 (`X`) through an ordinary Clojure call, then restore it.

Useful entry points include `start!`, `write!`, `resize!`, `state`,
`type-layout-json`, `focus-final-byte`, `publish-hot-title!`, `await-reload!`,
and `status`. The native handle never leaves the nREPL JVM.

See `IMPLEMENTATION_PLAN.md` for exact versions, timings, counts, and remaining
host verification gates.
