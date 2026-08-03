# Aguafria

Aguafria is a `deps.edn` library for writing ordinary Zig with Clojure data.
It generates standalone `.zig` modules, compiles them with Zig, and makes Zig
Vars callable and inspectable as normal Clojure Vars.

The important boundary is simple: a body captured by `az/defn` is Zig
represented as lists, vectors, maps, symbols, keywords, and literals. `while`
is a Zig while, and a Clojure map in expression position is a Zig anonymous
struct literal. As the one intentional Clojure convenience, the final
expression of a non-`void` function is returned implicitly. The generated
module has no Aguafria runtime dependency.

The REPL and production paths use the same Zig source. FFM adds a boundary
cost only when Clojure calls an exported native function. An artifact built
with `az/build!` is ordinary Zig machine code, with the same optimization, CPU
targeting, SIMD, comptime, and library access as a handwritten Zig build.

## Requirements

- Clojure CLI
- Zig on `PATH` (tested with Zig 0.16.0)
- JDK 22 or newer, for the stable Foreign Function & Memory API

Node.js is needed only when regenerating the checked-in Zig std namespace
catalog; applications using the library do not need Node.js or ZLS.

Applications that invoke Zig from Clojure should enable native access:

```clojure
{:aliases
 {:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

Development libraries are compiled for the JVM's current host OS and
architecture, because that process loads them through FFM. Final
`az/build!` artifacts may use any Zig-supported `:target`/`:cpu` and do not
contain the JVM or Aguafria. The native live-reload suite is currently verified
on macOS/aarch64; Linux, Windows, and macOS/x86_64 live-host verification is
tracked explicitly even though Linux/Windows TigerBeetle artifacts already
cross-compile from the current host.

## First function

```clojure
(ns example.core
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn add :- :i32
  [a :- :i32
   b :- :i32]
  (+ a b))

(add 20 22)
;; => 42
```

This emits the following kind of Zig declaration and compiles the complete
`example.core` module as a shared library:

```zig
pub export fn add(a: i32, b: i32) callconv(.c) i32 {
    return (a + b);
}
```

Generated source and libraries live under `.aguafria/zig`. Paths are
content-addressed by the source, Zig version, platform, and optimization mode,
so unchanged declarations use the cached binary.

## Declarations

Functions use the typed syntax from Vybe's C API:

```clojure
(az/defconst multiplier :i32 3)

(az/defvar calls :u64 0)

(az/defstruct Point
  [[:x :f32]
   [:y :f32]])

(az/defn scaled-add :- :i32
  [a :- :i32 b :- :i32]
  (* multiplier (+ a b)))
```

`az/defstruct` uses Malli-style field entries and emits an ordinary Zig
`struct` by default. A field can include a properties map, which remains
available in the Var's declaration metadata. Use an ordinary attribute map
only when an explicit extern or packed layout is required:

```clojure
(az/defstruct CPoint
  {:layout :extern}
  [[:x {:doc "Horizontal component"} :f32]
   [:y :f32]])

(az/defn origin-offset :- CPoint
  []
  ;; A known struct Var acts as a typed Zig initializer.
  (CPoint {:x 4.0 :y 5.0}))
```

The accepted field shapes are `[:field type]` and
`[:field {:property value} type]`. The old flattened
`[:field :- type ...]` shape is intentionally rejected.
Plain maps are convenient for named fields whose source order is irrelevant.
Converted Zig uses `(az/object [[:field value] ...])` when original field order
must remain observable, such as anonymous structs and comptime reflection.
The `az/defstruct` field vector is always the declaration/layout order.

An exported function uses `callconv(.c)` and is eligible for Clojure
invocation. A Zig-only helper can use arbitrary Zig types and the regular Zig
calling convention:

```clojure
(az/defn ^{:export false :public true} sum-point :- :f32
  [point :- Point]
  (+ (field point x) (field point y)))
```

Zig `std` is mechanically exposed as regular Clojure namespaces from one EDN
catalog. Require the bootstrap first, followed by the nested namespaces whose
symbols you use:

```clojure
(ns example.hello
  (:require [aguafria.std]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn ^{:export false} hello :- :void
  []
  (debug/print "hello\n" []))
```

Use `az/defimport` for external Zig modules. Every explicitly named external
member becomes a real, documented Clojure Var.

Use `az/defraw` only for a top-level Zig construct that the emitter does not
yet model directly. It is an explicit source escape hatch, not an implicit
identifier mechanism. The Zig-to-Clojure converter never emits `az/defraw`;
every converted top-level declaration uses a structural `az/def...` form.

`az/defn` also accepts a docstring. Its Var metadata retains the docstring,
typed arglist, and normalized declaration for REPL tools:

```clojure
(az/defn length-squared
  "Return the squared length."
  :- :f32
  [x :- :f32 y :- :f32]
  (+ (* x x) (* y y)))

(select-keys (meta #'length-squared)
             [:doc :arglists :aguafria/declaration])
```

## Form mapping

Expressions are prefix data even when Zig renders them infix:

| Clojure data | Zig |
| --- | --- |
| `(+ a b)` | `(a + b)` |
| `(and ready valid)` | `(ready and valid)` |
| `(ak/intCast value)` | `@intCast(value)` |
| `(ak/bit-not bits)` | `(~bits)` for a token reserved by the Clojure reader |
| `(ak/div-assign total divisor)` | `total /= divisor` |
| `(field point x)` | `(point).x` |
| `(deref pointer)` | `(pointer).*` |
| `(unwrap optional)` | `(optional).?` |
| `(index items i)` | `(items)[i]` |
| `(slice items start end)` | `(items)[start..end]` |
| `(if condition yes no)` in expression position | Zig `if` expression |
| `{:x 1 :y 2}` | `.{.x = 1, .y = 2}` |
| `[1 2 3]` | `.{1, 2, 3}` |
| `(raw "zig expression")` | source escape hatch |

Function bodies recognize Zig statements:

```clojure
(az/defn sum-to :- :i32
  [n :- :i32]
  (ak/var total :i32 0)
  (ak/var i :i32 0)
  (while (< i n)
    (ak/+= total i)
    (ak/+= i 1))
  total)
```

Supported statement forms include `ak/return`, `ak/const`, `ak/var`, `set!`,
qualified Zig assignment operators, `if`, `while`, `for`, `az/block`,
`ak/defer`, `ak/errdefer`, `ak/break`, `ak/continue`, `ak/unreachable`,
`az/comment`, and `az/raw`. `do` groups multiple
statements for a branch or deferred block.

Most assignment tokens can be written directly, such as `(+= total value)`.
For a token the Clojure reader rejects, use its named `ak` Var, such as
`(ak/div-assign total divisor)`. The generated keyword Vars are the direct
representation; no separate `builtin` form is involved.

The final expression in a non-`void` function is implicitly returned. This is
recursive for tail `if` and `do` forms:

```clojure
(az/defn abs-i32 :- :i32
  [x :- :i32]
  (if (< x 0)
    (- x)
    x))
```

This emits explicit `return` statements in both Zig branches. `(return value)`
is still supported when an earlier exit is needed.

Types can be Zig names (`:i32`, `Point`, or a raw string) or compositional
vectors:

| Type data | Zig type |
| --- | --- |
| `[:* :i32]` | `*i32` |
| `[:*const :i32]` | `*const i32` |
| `[:many :u8]` | `[*]u8` |
| `[:sentinel-const :u8 0]` | `[*:0]const u8` |
| `[:slice-const :u8]` | `[]const u8` |
| `[:array 4 :f32]` | `[4]f32` |
| `[:vector 4 :f32]` | `@Vector(4, f32)` |
| `(ak/Vector 4 :f32)` | `@Vector(4, f32)` through the generated keyword Var |
| `[:c-pointer :u8]` | `[*c]u8` |
| `[:optional :i32]` | `?i32` |
| `[:error-union :void]` | `!void` |

## Generated Zig keyword Vars

Zig's `@` prefix is a reader macro in Clojure, so Aguafria exposes every Zig
compiler `@` function as a real Var in `aguafria.keyword`, normally aliased to
`ak`:

```clojure
(ns example.simd
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn ^{:export false} lane-sum :- :i32
  [values :- (ak/Vector 4 :i32)]
  (ak/reduce :.Add values))

(az/defn narrow :- :i32
  [value :- :i64]
  (ak/intCast value))
```

These are not Clojure wrappers and introduce no runtime layer. The emitter
recognizes each Var directly through its metadata. Declaration macros only
qualify the caller's alias—for example `ak/intCast` becomes the inspectable
symbol `aguafria.keyword/intCast`—so stored declaration metadata never contains
a second `builtin` representation. A different namespace alias works too.

The catalog is generated from the installed Zig compiler's
`std/zig/BuiltinFn.zig`. A matching ZLS enriches each Var with the signature
and language-reference documentation it serves for that Zig version. Exact
compiler properties—including parameter count, error behavior, lvalue support,
and function-scope restrictions—remain in `:zig/*` Var metadata:

```clojure
(clojure.repl/doc ak/intCast)

(select-keys (meta #'ak/intCast)
             [:zig/name :zig/signature :zig/param-count
              :zig/eval-to-error :zig/version :zig/source])

(ak/catalog-info)       ; generated Zig/ZLS version and source hashes
(ak/entries)            ; every Zig @ function as plain catalog data
(ak/language-keywords)  ; ordinary keywords, including if and while
(ak/reader-tokens)      ; generated Zig-only operators and assignments
```

Clojure-native syntax stays readable and unqualified: write `(if ...)`,
`(while ...)`, or `(try ...)`. Zig-only syntax is a real Var, for example
`(ak/const value :u32 1)`, `(ak/+= total value)`, and `(ak/return value)`.
Aguafria structural helpers are documented Vars such as `(az/field point x)`
and `(az/while-loop ...)`. `ak/field`, by contrast, intentionally means Zig's
distinct `@field` function.

## EDN-derived Zig std namespaces

The same generator exposes Zig's complete public `std` graph, including nested
containers, as ordinary require-able Clojure namespaces. `aguafria.std` is a
single handwritten bootstrap: it reads `resources/aguafria/zig-std.edn`,
creates the catalog's namespaces, interns their Vars, and registers them with
Clojure's loader. No `std/...` Clojure source files are generated.

Place the bootstrap before the derived namespaces in the `:require` vector;
Clojure processes these libspecs in order:

```clojure
(ns example.standard-library
  (:require [aguafria.std]
            [aguafria.std.math :as math]
            [aguafria.std.mem :as mem]
            [aguafria.std.mem.Allocator :as allocator]
            [aguafria.zig :as az]
            [aguafria.zig.std :as zig-std]))

(az/defn square-root :- :f64
  [x :- :f64]
  (math/sqrt x))

(clojure.repl/doc math/sqrt)
(select-keys (meta #'allocator/alloc)
             [:zig/name :zig/signature :zig/source :zig/version])

(zig-std/catalog-info)
(zig-std/namespaces)
(zig-std/entries 'aguafria.std.math)
```

Each EDN-derived symbol is a genuine Var. Calling it directly in a JVM REPL
returns canonical, inspectable form data:

```clojure
(math/sqrt 'x)
;; => (aguafria.std.math/sqrt x)

(az/emit-expr (math/sqrt 'x))
;; => "@import(\"std\").math.sqrt(x)"
```

Compilation consumes the Var metadata directly and emits an explicit
`@import("std")` reference, so no hidden import declaration, wrapper, or
runtime abstraction is introduced. Type/container Vars such as `mem/Allocator`
and nested members such as `allocator/alloc` use the same representation.

The catalog is derived from Zig's own `-femit-docs` semantic graph—not a list
of handwritten wrappers—so aliases, nested public containers, signatures,
documentation, and source locations follow the installed Zig compiler.

When Zig is upgraded, keyword and complete std-catalog regeneration and drift
detection are mechanical:

```sh
clojure -M:generate-keyword
clojure -M:check-keyword
```

Set `AGUAFRIA_ZIG`, `AGUAFRIA_ZLS`, or `AGUAFRIA_NODE` to select non-default
executables. ZLS is optional during generation: if it is missing or has a
different version, the complete compiler catalog is still generated with
source-table documentation. Zig and Node.js are required to regenerate the std
EDN catalog. Normal library use needs neither tool and reads no generated
Clojure source.

## Converting Zig source to ordinary Clojure namespaces

The opt-in `aguafria.zig.convert` namespace converts a Zig file using the
installed compiler's own parser. One Zig file becomes one normal Clojure file:

```clojure
(require '[aguafria.zig.convert :as convert])

(convert/convert-file!
 "src/vector.zig"
 "generated/my/project/vector.clj"
 {:namespace 'my.project.vector
  :overwrite? true})
```

The output contains only an ordinary `ns` form followed by `az/defn`,
`az/defconst`, `az/defvar`, `az/defstruct`, `az/deffield`, `az/defcomptime`,
`az/defextern`, `az/deftest`, and related structural forms. It contains no
`batch/begin!`, `batch/end!`, tooling namespace, or `az/defraw`. Each macro
interns its Var directly in the namespace declared by that file, exactly as if
the user had written and evaluated the form there:

```clojure
(convert/load-converted! "generated/my/project/vector.clj")

(ns-resolve 'my.project.vector 'length)
;; => #'my.project.vector/length
```

`load-converted!` is optional. Put the generated root on the Clojure source
path, exactly like any other source root, and normal `require`, editor
evaluation, or REPL evaluation follows the regular compile/hot-reload path.
Generated project dependencies use ordinary `:as` aliases; only an edge that
would close a real Zig import cycle uses Clojure's non-eager `:as-alias`.
Aguafria discovers the EDN catalog, source-loads those cyclic dependencies as
complete modules, and coalesces a whole-file evaluation before compiling, so
users never call a batching API or preload namespaces by hand. The loader is
only a large-file optimization: while
the ordinary forms are evaluated, it externally collects their declaration
descriptors, then publishes one complete source snapshot instead of compiling
an intermediate module after every declaration. It never changes Var
ownership. Its default `{:compile? false}` makes a converted file immediately
inspectable; use `{:compile? true}` or call `az/recompile!` when the module is
ready to build.

Convert and optionally bulk-load a whole project with one namespace per Zig
file:

```clojure
(convert/convert-tree!
 "vendor/project"
 "generated/project"
 {:namespace-prefix 'project
  :overwrite? true
  :bundle-assets? true})

(convert/load-tree! "generated/project")
(convert/stats)
```

When a project contains `build.zig`, `convert-tree!` asks the installed Zig
version's own configure graph for generated value modules such as
`vsr_options` or `test_options`. Their generated Zig source is stored as EDN
data beside the importing namespace and materialized automatically during
development compilation—users do not add paths or namespace metadata. The
default build step is inspected unless another profile is selected:

```clojure
(convert/convert-tree!
 "vendor/project"
 "generated/project"
 {:namespace-prefix 'project
  :build-steps ["test:unit:build"]})
```

Pass `:capture-build-modules? false` only when configuration should deliberately
be skipped. For `Step.Options.addOptionPath`, Aguafria injects an options-only
build target into Zig's version-matched build runner. Zig executes just the
selected option nodes and their path producers; Aguafria then bundles the
resolved files or directories under `.aguafria-build-paths`, stores relocatable
tokens in EDN, and resolves them when the generated namespace loads. This works
for source paths and generated artifacts such as `Compile.getEmittedBin()`—the
catalog never retains an absolute original-checkout or `.zig-cache` path.

`load-tree!` adds that generated root to the current dynamic classloader before
loading it. In this repository, `clojure -M:nrepl` already includes
`generated/tigerbeetle`, so `require` and editor namespace evaluation work in a
fresh development REPL.

With `:bundle-assets? true`, the generated tree also contains a manifest-backed
`.aguafria-assets` copy of every non-Zig project file. The conversion report can
then be passed to `materialize-project!` elsewhere without the original Zig
checkout: generated Clojure/EDN regenerates every `.zig` file, while the bundle
restores only the ordinary non-Zig assets. Materialization runs emitted Zig
through the configured `zig fmt` by default; pass `:format? false` only to
inspect the emitter's pre-format output.

`convert-file`, `zig->clojure`, and `render-zig` are pure/in-memory variants.
`verify-file` renders the produced Aguafria forms back to Zig and runs `zig
ast-check`, `zig test`, or `zig build-obj`. Every operation returns plain data
with declaration, fallback, timing, command, and verification statistics.

The checked-in sample conversion lives under `sample/clojure/sample`. The
pinned self-contained TigerBeetle corpus contains 245 generated namespaces,
374 bundled non-Zig assets, and 4,032
structural top-level declarations, with zero generated `az/defraw`
declarations, zero nested raw boundaries, and zero unresolved known syntax
heads. The materialized converted project passes `zig build check` on Zig
0.16.0 without consulting the original Zig files. Conversion fails with
structured, source-located fallback data if an
unsupported Zig AST node is encountered. The repository test regenerates the
complete corpus, rejects every raw boundary, loads every file, and verifies
that its Vars belong to the namespace declared in that same file.

## Reloading and asynchronous compilation

Every Clojure namespace is currently one generated Zig module. In development,
each addressable scalar Zig function has a stable dispatch cell keyed by its
logical Var identity and ABI fingerprint. This includes ordinary Zig-only
`pub fn`/private helpers; only actual C exports are directly callable from
Clojure through FFM. Re-evaluating an ABI-compatible callee swaps its
implementation pointer atomically, so already-compiled callers in the same
namespace or a directly dependent namespace observe the new body without being
recompiled themselves.

Aguafria tracks calls on both sides of the FFM boundary. An obsolete shared
library remains loaded while a JVM invocation or native implementation is
active, then its arena is closed after it becomes unreferenced and quiescent.
Current, retained, active, retirement-pending, and retired generations are all
visible through `az/stats`.

Breaking scalar function signatures create distinct retained ABI versions.
The newest signature remains the normal Var, while retained versions are
inspectable and directly callable when debugging or migrating:

```clojure
(def old-abi (-> #'calculate meta :aguafria/declaration :abi-fingerprint))

;; After reevaluating calculate with a different signature:
(az/function-versions 'example.core/calculate)
(az/invoke-version! 'example.core/calculate old-abi [41])
```

For scalar callees, breaking signatures retain the old ABI cell: callers in
the same namespace or another namespace continue through the old implementation
until they are corrected and reevaluated. When a same-namespace stale caller
makes the complete new source temporarily invalid, the callee can publish as
an independent live slice. The full static namespace source remains visible,
and its expected type error is reported by `az/stats` while that partial
development publication is active.

Dependency graphs may be acyclic or cyclic. Hand-written Aguafria projects
normally use ordinary acyclic `:require` edges, but cycles are supported rather
than reserved for generated projects. In development, each namespace is a
logical Zig module in one immutable transitive snapshot behind a tiny loader
root. This lets A import B while B imports A without compiling A twice. A
hand-written cyclic integration test proves that redefining A updates an
already-compiled B caller without recompiling B.

`az/recompile-component!` prepares every namespace in the requested strongly
connected component in parallel from one immutable dependency snapshot. All
libraries load before one registry transition. Native wrappers share an
odd/even publication epoch, so a caller waits rather than entering a partially
updated set of dispatch/state cells; preparation failure leaves the complete
old component running and records the original Zig diagnostic in `az/stats`.

`az/defvar` state is also live. Development code reads and writes a schema-keyed
pointer cell whose canonical address remains owned by its first published
library generation. Old and newly compiled functions—in the same namespace or
different namespaces—therefore share one native state capsule. Compatible
reloads preserve it without copying. A schema change retains the old capsule
and stops before publication until the user supplies explicit Zig migration:

```clojure
(az/defn migrate-counter :- :void
  [old-address :- :usize new-address :- :usize]
  (ak/const old-value [:*const :i32] (ak/ptrFromInt old-address))
  (ak/const new-value [:* :i64] (ak/ptrFromInt new-address))
  (az/assign "=" (az/deref new-value)
    (ak/intCast (az/deref old-value))))

;; After reevaluating counter with its new i64 type:
(az/migrate-state! #'counter #'migrate-counter)
(az/state-versions #'counter)
```

The migration must be an exported `(usize old, usize new) void` Aguafria
function. Aguafria never guesses field meaning. `az/type-versions` similarly
shows compatible, retained, and breaking generations for `az/defstruct`,
container constants, and comptime functions returning `type`. A dependent Var
adopts a breaking type generation only when reevaluated; old code remains on
its retained schema.

Reloadable scalar calls made by Zig comptime use the current statically
compiled implementation; runtime calls use the swappable dispatch cell. This
keeps type/value computation legal at comptime without giving up runtime hot
reload.

The live-versioning path now includes concrete and generic/inline dependent
closure, compatible and breaking comptime type-factory generations, and a real
running TigerBeetle state migration. The three reproducible running-program
gates are:

```sh
clojure -M:vopr-hot-acceptance 2000 1
clojure -M:vopr-type-hot-acceptance 2000 1
clojure -M:vopr-migration-hot-acceptance 2000 1
```

The first starts converted VOPR, defines a new native function and state Var, and then
reevaluates the existing `full_core` to call that function while the original
host remains active. The live state probe proves that the same host executed
the new call path before completing normally. The second publishes a breaking
real `OptionsType` generation while the active host remains safely pinned to
its old object graph. The third changes the existing VOPR `full_core` signature
and converts `log_performance_mode` from `bool` to a struct. The old host
completes on its retained graph; explicit Zig migration runs only after
quiescence, then a linked replacement host in the same JVM PID uses the new
ABI/state and reproduces the deterministic VOPR result. Aguafria does not guess
how to reinterpret arbitrary live stack or heap objects. Final standalone
source never uses development dispatch, epoch, or state cells and therefore
compiles as one valid ordinary Zig program.

The same generic path has also been exercised against converted TigerBeetle
code: an already-compiled Aguafria caller observed the existing Zig-only
`compaction_op_min` change from 96 to 97 and back to 96 in the same JVM, while
the caller's implementation generation did not change. That scalar probe, the
running `OptionsType` structural retention scenario, and the quiescent
`full_core`/state migration above exercise the converted TigerBeetle graph
itself rather than a TigerBeetle-shaped fixture.

Compilation is synchronous by default so a bad declaration throws at its
defining form. For a namespace containing many declarations, enable concurrent
snapshot compilation:

```clojure
(az/configure! {:async? true})
```

or set this before starting the JVM:

```sh
AGUAFRIA_ASYNC_COMPILE=true clojure -M:dev
```

Each declaration queues an immutable complete-module snapshot. Those Zig
builds may run in parallel, but Aguafria publishes only the newest requested
generation, so a slower stale build cannot roll the namespace backward. The
first function call waits for the newest pending build. You can synchronize
explicitly with `(az/await! 'example.core)` or wait for every module with
`(az/await!)`.

After changing compiler options, `(az/recompile! 'example.core)` rebuilds the
module without redefining a Var; `(az/recompile!)` schedules every known module.
It honors the current synchronous/asynchronous setting.

Other compiler settings are configurable in Clojure:

```clojure
(az/configure! {:zig "/path/to/zig"
                :cache-dir ".aguafria/zig"
                :optimize "ReleaseFast"
                :cpu "native"
                :target nil
                :zig-args []})
```

The corresponding executable override is `AGUAFRIA_ZIG`. Current source and
build metadata are inspectable with `(az/source 'example.core)` and
`(az/module-info 'example.core)`.

## Zig libraries and standalone programs

Third-party Zig dependencies do not need conversion. Named Zig modules remain
normal `@import` dependencies: map an import name to its root Zig source file,
then declare the members used from Clojure:

```clojure
(az/configure!
 {:modules {"zmath" "/checkout/zmath/src/root.zig"}})

(az/defimport zmath "zmath"
  [[matrix-multiply "mat.mul"]])

(az/defn ^{:export false} multiply-matrices :- Result
  [left :- Matrix right :- Matrix]
  (zmath/matrix-multiply left right))
```

The local alias and every member are resolvable through normal Clojure tools:
`(resolve 'zmath)` returns the import Var and
`(ns-resolve *ns* 'zmath/matrix-multiply)` returns the member Var. A dotted or
qualified call that does not resolve to an Aguafria Var is rejected during
emission; it is never silently treated as a Zig symbol.

Aguafria passes these as Zig CLI modules and makes configured external modules
available throughout the development dependency graph. Explicit entries
override same-named value modules captured from a converted project's Zig
configure graph. `:zig-args` remains
available for linker flags, include paths, system libraries, or newer Zig
options. Builds with external modules or custom Zig arguments always invoke
Zig so a changed dependency cannot be hidden by Aguafria's artifact cache;
Zig's own cache still applies. Hand-written projects can intentionally supply
such modules this way; converted value-based option modules require no manual
configuration. Aguafria declarations that call an external module remain hot;
the unconverted dependency itself has no per-Var dispatch. After editing that
dependency, reevaluate/recompile its Aguafria callers, or convert the dependency
only when its own declarations must be independently live. ABI and layout
changes still use Aguafria's retained-version and explicit-migration rules.

Define a regular public, non-exported Zig `main` and build the current module as
an optimized executable:

```clojure
(require 'aguafria.std
         '[aguafria.std.debug :as debug])

(az/defn ^{:export false :public true} main :- :void
  []
  (debug/print "hello from pure Zig\n" []))

(az/build! 'example.core
           {:kind :exe
            :name "example"
            :optimize "ReleaseFast"
            :cpu "native"})
;; => {:output-path ".../example", :command [...], :duration-ms ...}
```

`:kind` may be `:exe`, `:dynamic-lib`, `:static-lib`, or `:object`. `:output`,
`:target`, `:modules`, and `:zig-args` can be overridden per build. The artifact
contains no JVM, Clojure, FFM, or Aguafria runtime layer.

## Inspection and compilation statistics

Everything needed by a future monitor is exposed as plain Clojure data:

```clojure
(az/source 'example.core)       ; current complete Zig source
(az/module-info 'example.core)  ; paths, hash, generation, declarations
(az/stats 'example.core)        ; module and bounded build history
(az/stats)                      ; aggregate summary, all modules, all builds
(az/state-versions #'counter)   ; native state capsules and migrations
(az/type-versions #'Point)      ; retained schema generations
```

`az/stats` reports requested and published generations; queued, compiling,
finished, stale, migration-required, and failed snapshots; current
declarations, stable logical
identities, ABI/schema fingerprints, dispatch versions, active native/JVM call
counts, state/type versions and migrations, retained and retired libraries;
direct dependencies, reverse
dependents, deterministic strongly connected components and cycle flags; cache
hits; compiler thread; timings; diagnostics count; generated paths; and
standalone builds. The same
identity/fingerprint data lives under `:aguafria/declaration` in each Var's
metadata. Statistics omit native handles, arenas, promises, and other opaque
runtime objects, so they can be printed, tapped, serialized, or rendered by a
monitor.

## Reproducible performance checks

Benchmarking is separate tooling rather than clutter in `aguafria.zig`. Every
result is plain EDN and records OS/architecture plus Java, Clojure, and Zig
versions:

```sh
clojure -M:bench                              # default native microbenchmarks
clojure -M:bench project sample
clojure -M:bench project tigerbeetle

clojure -M:bench check micro                 # enforced EDN budgets
clojure -M:bench check sample
clojure -M:bench check tigerbeetle
```

The micro mode measures clean/cached/single-Var compilation, serial and
parallel scheduling, reload dispatch, FFM calls, and a separately loaded final
`:reloadable? false` ReleaseFast library. The final artifact check rejects any
Aguafria dispatch/state/epoch marker and compares its composed call loop with a
direct handwritten loop. Project modes use a fresh temporary output root and
measure conversion, EDN std catalog/Var installation, generated namespace
loading, and complete Zig emission; checked-in generated files are untouched.
Performance ceilings live in `dev/aguafria/performance_budgets.edn`, and a
failed check exits nonzero with every actual/expected metric path.

## Compiler errors

Emitter validation and Zig failures retain structured `ex-data` and render a
Rust-style report. A compiler error includes the originating Clojure
declaration/form, source line and caret when a file is available, generated Zig
line and caret, compiler message, module path, exact command, and raw Zig
diagnostics. Synchronous definitions throw immediately; asynchronous failures
throw from `az/await!` and remain visible in `az/stats`. If an older native
generation was already published, ordinary Var invocation keeps using it after
a failed reload instead of taking the running program offline.

## Native values from Clojure

Exact scalar results are ordinary JVM values. Unsigned `u64`/`usize` results
use `BigInteger` when they exceed `Long/MAX_VALUE`. Values without a lossless
JVM scalar representation use a native-backed `ZigValue`: printing and
`clojure.pprint` show the semantic value, while `az/native-segment` and
`az/native-bytes` retain access to the authoritative representation.

```clojure
(az/defstruct Flags
  {:layout :packed}
  [[:enabled :bool]
   [:opcode :u3]
   [:reserved :u4]])

(def flags (Flags {:enabled true :opcode 5 :reserved 9}))
(az/value flags)
;; => {:enabled true, :opcode 5, :reserved 9}

(az/native-bytes flags)
;; => [-101]

(az/close! flags)
```

`az/defconst` and `az/defvar` roots likewise expose their real semantic Zig
values, never Aguafria's internal declaration maps. Structs—including nested
packed structs—accept field maps. Arrays, SIMD vectors, and slices accept
Clojure vectors; slice backing memory lives for the native call and is retained
when a native result can borrow it. Enums accept keywords, and tagged unions
accept a single-entry map such as `{:integer 42}`. Zig optionals use `nil` or
their payload value. Error unions use `{:ok value}` or an exact decoded
`{:error {:name :SomeError :code n}}`; retaining the native code makes the
error reusable without guessing Zig's generation-specific error numbering.
These representations compose recursively through optionals, slices, error
unions, arrays/vectors, and normal-struct fields, and work as direct function
arguments/results. Typed Zig pointers are borrowed `ZigPointer` values: use
`az/pointer-type`, `az/pointer-address`, or `az/pointer-segment` to inspect them
and pass the pointer itself directly to another Aguafria function. The Zig
owner still controls the pointee lifetime.
These values pass directly to other Aguafria Vars through development-only
pointer bridges; standalone output remains ordinary optimized Zig with no
Clojure runtime. Native storage is released automatically when a value becomes
unreachable, or deterministically with idempotent `az/close!`.

`(az/set-value! live-var new-value)` writes through a live `az/defvar` handle
in place without compiling. It uses the same type/range checks and accepts the
same maps, keywords, and vectors as constructors. This is native mutation, so
coordinate with a running Zig thread through a safe point, lock, atomic, or a
synchronized Zig function exactly as handwritten Zig requires.

Top-level optional, slice, and error-union `defvar` values can be changed with
`az/set-value!`; Aguafria retains Clojure-created slice backing memory for that
state until `az/clear!`. An untagged union can be constructed with an explicit
single-entry map, but a value returned by arbitrary Zig code has no runtime
active tag; Aguafria reports that ambiguity instead of guessing from its bytes.

## Development

```sh
clojure -M:check-keyword
clojure -M:test
clojure -M:nrepl
clojure -M:bench check suite  # populate, profile, and budget a warm full suite
```

Kaocha discovers the tests through [`tests.edn`](tests.edn). Its CLI arguments
can be appended directly, for example `clojure -M:test --focus
aguafria.keyword-test`.

The tests include real Zig shared-library compilation, FFM invocation,
cross-function calls, external Zig modules, implicit returns, control flow,
generated keyword/std completeness and metadata, normal nested std namespace
requires, reader-safe syntax, Rust-style diagnostics, statistics, optimized
standalone output, caching, and hot reload.
