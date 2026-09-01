# Aguafria

Aguafria is a `deps.edn` library for writing Zig with Clojure forms.

- `az/defn`, `az/defconst`, `az/defvar`, and `az/defstruct` emit ordinary Zig.
- The same Vars are callable and inspectable from a Clojure REPL during development.
- Re-evaluating a declaration compiles and publishes a new native generation without restarting the JVM.
- Release builds are standalone Zig artifacts with no JVM or Aguafria runtime.
- Aguafria includes its pinned Zig 0.16.0 compiler; it never selects an unrelated Zig from `PATH`.

## Install

Aguafria requires Clojure 1.12 or newer and JDK 22 or newer.

Use the artifact for the machine running the JVM:

```clojure
{:deps
 {org.clojure/clojure {:mvn/version "1.12.0"}

  ;; Apple Silicon macOS
  io.github.pfeodrippe/aguafria-macos-aarch64
  {:mvn/version "0.1.6"}}}
```

For x86-64 Linux, use:

```clojure
io.github.pfeodrippe/aguafria-linux-x86-64
{:mvn/version "0.1.6"}
```

Enable the JDK Foreign Function & Memory API when calling native code:

```clojure
{:aliases
 {:dev
  {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

No separate Zig installation is required. The matching embedded toolchain is
verified, extracted atomically into a cache, and reused.

## First function

```clojure
(ns example.core
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn add
  "Add two signed integers."
  :-
  :i32
  [[a :i32]
   [b :i32]]
  (+ a b))

(add 20 22)
;; => 42
```

The final expression is returned implicitly, as it is in Clojure. The
standalone declaration is ordinary Zig:

```zig
pub fn add(a: i32, b: i32) i32 {
    return a + b;
}
```

Use `ak/return` only for an early or explicit Zig return.

## Declarations

`az/defn` creates a public Zig function. `az/defn-` creates a private Zig
function. Both are callable Clojure Vars in development.

```clojure
(az/defn public-value :- :u32 [] 42)

(az/defn- implementation-detail :- :u32 [] 7)
```

Public Zig visibility is not a C ABI export. Request a stable exported symbol
only when an external native caller needs one:

```clojure
(az/defn exported-entry
  {:attrs #{:export}}
  :-
  :i32
  [[value :i32]]
  value)
```

Constants and mutable Zig globals are normal Clojure Vars:

```clojure
(az/defconst port :u16 8787)

(az/defvar requests-served :u64 0)
```

The type is optional when Zig can infer it:

```clojure
(az/defconst answer 42)
```

Struct fields use a Malli-like vector schema. A field can carry a metadata map
without changing the shape of the declaration:

```clojure
(az/defstruct Point
  "A position in world space."
  [[:x :f32]
   [:y {:doc "Vertical coordinate."} :f32]])

(Point {:y 2.0 :x 1.0})
```

Normal layout is the default. Use declaration options only for behavior that
differs from that default.

## Writing Zig with Clojure forms

Inside Aguafria declarations, Clojure data represents Zig syntax; it does not
introduce a second runtime abstraction:

| Clojure form | Zig meaning |
| --- | --- |
| `(f a b)` | function call |
| `(let [x 1] ...)` | immutable local by default |
| `^{:var true :zig/type :i32} x` | typed mutable local |
| `if`, `when`, `cond` | Zig control flow |
| `while`, `doseq` | Zig loops |
| `set!` | assignment |
| `(az/field p :x)` | `p.x` |
| `(az/index values i)` | `values[i]` |
| `(Point {:x 1.0 :y 2.0})` | typed struct literal |
| `ak/...` | Zig-only operators, keywords, and `@builtins` |

For example:

```clojure
(az/defn sum-to
  :-
  :u32
  [[limit :u32]]
  (let [^{:var true :zig/type :u32} total 0
        ^{:var true :zig/type :u32} index 0]
    (while (< index limit)
      (ak/+= total index)
      (ak/+= index 1))
    total))
```

`aguafria.keyword` exposes Zig spellings that have no honest Clojure meaning,
including `ak/undefined`, `@` builtins, and operators such as Zig equality.
Ordinary Clojure forms such as `if` and `let` remain ordinary symbols. Nothing
is injected as an unbound magical name.

`aguafria.std` and its nested namespaces are interned from EDN resources, so
they support normal `:require`, completion, `doc`, and REPL discovery without
thousands of generated source files:

```clojure
(ns example.math
  (:require [aguafria.std.math :as std-math]
            [aguafria.zig :as az]))

(az/defn maximum :- :u32
  [[a :u32]
   [b :u32]]
  (std-math/max a b))
```

`az/cast` is a small source-rewriting macro and is thread-friendly:

```clojure
(-> iterator
    (flecs/ecs_field_w_size (ak/sizeOf Circle) 1)
    (az/cast [:c-pointer Circle]))
```

## REPL and hot reload

Start the project through the nREPL alias used by CIDER, Calva, or another
nREPL client:

```sh
clojure -M:dev:nrepl
```

Require the namespace and call its Vars normally. Re-evaluate only the changed
`az/defn`, `az/defconst`, `az/defvar`, or type declaration. Aguafria compiles
the affected native units and publishes their new dispatch targets.

Compilation can run asynchronously. Wait for a namespace or for all pending
work when a deterministic boundary is needed:

```clojure
(az/await! 'example.core)
(az/await!)
```

Inspect compilation and publication state at any time:

```clojure
(az/stats)
```

The returned data includes queued, compiling, finished, cached, failed, and
published declarations. Compiler failures retain the originating namespace,
Var, Clojure form, emitted Zig location, command, and Zig diagnostic.

Compatible changes update existing callers. Breaking signatures and layouts
create new native generations; live objects using an old layout remain on that
generation until callers migrate or the application restarts. This is the same
kind of practical boundary encountered when redefining Java-backed state in a
Clojure REPL.

`az/set-value!` changes a live `az/defvar` through its native storage without
compilation:

```clojure
(az/set-value! requests-served 0)
```

## Native values

Directly representable results return as ordinary Clojure values. Zig values
with native-only representation use typed Aguafria values backed by FFM
memory; they print and pretty-print as their real value and can be passed to
other Aguafria Vars.

Structs use maps, arrays and vectors use Clojure vectors, enums use keywords,
optionals use `nil` or their payload, tagged unions use single-entry maps, and
error unions use `{:ok value}` or `{:error ...}`. Typed pointer values remain
borrowed native pointers with explicit lifetime rules.

## Zig source and packages

The converter translates a Zig file or tree into formatted Clojure namespaces
made from Aguafria declarations. It does not rely on `az/defraw`. The resulting
namespaces emit behaviorally equivalent Zig and can participate in the same
REPL workflow.

Third-party Zig packages can be declared as data, fetched and pinned before
launch, and exposed as normal namespaces such as:

```clojure
(ns example.ids
  (:require [aguafria.pkg.uuid :as uuid]))
```

See the complete published-dependency workflow in
[the HTTP server example](examples/http-server/README.md).

## Standalone builds

`az/build!` emits and builds an ordinary Zig library or executable. Release
artifacts contain neither Clojure nor the JVM, so FFM and hot-reload machinery
do not affect their runtime performance or size.

The HTTP server example demonstrates both paths:

```sh
cd examples/http-server

# REPL development
clojure -M:nrepl

# JVM-free optimized executable
clojure -M:standalone
./build/http-server
```

## Examples

- [HTTP server](examples/http-server/README.md): small published-library,
  package, hot-reload, and standalone example.
- [Racing game](examples/racing-game/README.md): native rendering, Flecs,
  embedded inference, monitoring, and live development.
- [TigerBeetle](examples/tigerbeetle-agua/README.md): large generated
  Aguafria project.
- [TigerBeetle from pure Zig](examples/tigerbeetle-zig/README.md): editor
  workflow for an existing Zig project.
- [Ghostty](examples/ghostty/README.md) and
  [Ghostty from pure Zig](examples/ghostty-zig/README.md): native application
  conversion and editor reload workflows.
- [Lightpanda](examples/lightpanda/README.md): another large Zig application.
- [Simple game](examples/simple-game): Flecs, graphics, physics, and audio.

Each example owns its dependencies and generated output; examples do not
depend on one another.

## Project development

```sh
clojure -M:check-keyword
clojure -M:test
```

Kaocha configuration lives in [`tests.edn`](tests.edn). Release packaging uses
the deps.edn-native [`build.clj`](build.clj) tasks through the
[`Makefile`](Makefile):

```sh
make package
make verify
make publish
```
