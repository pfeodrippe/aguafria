# TigerBeetle Agua

This is a normal Clojure `deps.edn` project for developing the converted
TigerBeetle code through Aguafria. Its classpath contains:

- this example's `src` and `resources`;
- Aguafria itself through the local `../..` dependency;
- `generated/`, whose `.clj` files are ordinary namespaces.

Development stays in one JVM/PID. Aguafria loads generated Zig libraries into
that JVM and runs long-lived Zig mains on native threads; it does not launch a
second JVM or a TigerBeetle child process. Generated Vars are called like
normal Clojure functions and compile/cache themselves on first use. Native scalar
and aggregate results retain their Zig types and storage in inspectable handles.
Use `(a/value result)` for an ordinary JVM snapshot and `with-open` for owned
results. Structs, packed fields, arrays, and vectors support semantic Clojure
printing and construction.
The example uses TigerBeetle's converted
CLI client only as a convenient high-level transaction parser while a reusable
direct transaction bridge is developed.

Aguafria uses its embedded Zig 0.17.0 compiler. ZLS is not needed to load,
compile, run, or regenerate this project. The vendored native builds and
JVM hot-reload checks pass on 0.17; see the
[migration report](../../ZIG_0_17_0_MIGRATION_2026-10-03.md) for exact coverage
and the remaining project-level checks.

## Calva / nREPL

Open this directory in VS Code and use Calva's **Jack-in** with `deps.edn` and
the `:dev` alias. Calva supplies its normal nREPL/CIDER middleware. No custom
editor extension is required.

Alternatively, start a plain nREPL:

```sh
clojure -M:nrepl
```

Open `src/tigerbeetle_agua/core.clj`, evaluate the namespace, and work down the
two `(comment ...)` forms at the end. The first is an API scratchpad. The second
is a self-contained hot-reload walkthrough with three progressively deeper
edits: a scalar function body, TigerBeetle's real comptime-dependent transfer
parser verified through live account balances, and a comptime type factory
whose returned struct method updates an already-compiled caller. The replica,
JVM, and nREPL stay running throughout.

## Generate and check

If the checked-in generated tree is absent, run this before evaluating
`tigerbeetle-agua.core`:

```sh
clojure -M:generate
```

The converter is intentionally a separate namespace, so it remains available
when the generated namespaces are not:

```clojure
(require '[tigerbeetle-agua.generate :as generate])
(generate/ensure-generated!)
```

The command converts `vendor/tigerbeetle` to `generated/`, including both the
default and VOPR build
profiles. Explicit input and output directories may be supplied as the first
and second arguments.

Compile the generated main module and run its real `version` command with:

```sh
clojure -M:check
```

Run the example's tests with:

```sh
clojure -M:test
```

Prepare the JVM walkthrough, VSR helpers and main-module operations without
running their native function bodies:

```sh
clojure -X:precompile
```

The report at `.aguafria/precompile/tigerbeetle.edn` distinguishes prepared
handlers from generic, process-entry and other unsupported cases. Artifacts
use the shared `~/.aguafria/zig` cache. This does not instantiate every generic
or prepare every namespace in the vendored project; pass additional namespaces
to `a/precompile!` when working on them.

The first compile is intentionally substantial; later compatible declaration
edits reuse Aguafria's cache and publish into running native hosts. Breaking
signatures or layouts retain the old graph until a cooperative restart and any
required explicit migration, rather than corrupting live native state.

Calling an unchanged generated Var repeatedly does not compile repeatedly.
The first JVM call to a supported non-`export` function builds one
development-only callable trampoline; its loaded binding is then reused. A
fresh JVM must evaluate the generated namespaces and load native libraries
again, while matching Zig build artifacts still come from the content-addressed
disk cache. `(status)` exposes compiler activity and `:cache-hit-count` so this
is observable from the REPL.
