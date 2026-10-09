# Aguafria

Aguafria is a `deps.edn` library for writing Zig with Clojure forms.

- `a/defn`, `a/defconst`, `a/defvar`, `a/defstruct`, `a/defenum`, and `a/defunion` emit ordinary Zig.
- The same Vars are callable and inspectable from a Clojure REPL during development.
- Re-evaluating a declaration compiles and publishes a new native generation without restarting the JVM.
- Release builds are standalone Zig artifacts with no JVM or Aguafria runtime.
- Aguafria includes its pinned Zig 0.17.0 compiler; it never selects an unrelated Zig from `PATH`.

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

Newly built platform JARs store the archive as `aguafria/toolchain/zig.tar.xz.enc`
using AES-256-GCM. Aguafria decrypts it automatically, authenticates it, and
checks the original Zig archive's SHA-256 before extraction. The key is embedded
in `aguafria.zig.toolchain-crypto`, so no configuration is needed. This packaging
obscures the archive from ordinary archive tools; anyone with the JAR also has
the key. The extracted compiler and libraries remain readable in the user cache.
This loader requires the encrypted format; plaintext and split archives are
rejected. Previously published JARs use their own older loader.

## First function

```clojure
(ns example.core
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as a]))

(a/defn add :i32
  "Add two signed integers."
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

`a/defn` creates a public Zig function. `a/defn-` creates a private Zig
function. Both are callable Clojure Vars in development. The return type follows
the name, followed by an optional docstring, optional attributes, and typed arguments.

```clojure
(a/defn public-value :u32 [] 42)

(a/defn- implementation-detail :u32 [] 7)
```

Public Zig visibility is not a C ABI export. Request a stable exported symbol
only when an external native caller needs one:

```clojure
(a/defn exported-entry :i32
  {:attrs #{:export}}
  [[value :i32]]
  value)
```

Constants and mutable Zig globals are normal Clojure Vars:

```clojure
(a/defconst port :u16 8787)

(a/defvar requests-served :u64 0)
```

The type is optional when Zig can infer it:

```clojure
(a/defconst answer 42)
```

Struct fields use a Malli-like vector schema. A field can carry a metadata map
without changing the shape of the declaration:

```clojure
(a/defstruct Point
  "A position in world space."
  [[:x :f32]
   [:y {:doc "Vertical coordinate."} :f32]])

(Point {:y 2.0 :x 1.0})
```

All container members share one vector, including nested methods. Enums accept
bare tags or detailed tag vectors:

```clojure
(a/defenum Color
  [:red [:blue {:doc "Blue channel"} 4]])

(a/defn ShortList :type
  [[T {:attrs #{ak/comptime}} :type]
   [length {:attrs #{ak/comptime}} :usize]]
  (a/struct
    [[:items [:array length T]]
     (a/fn- capacity :usize [] length)]))
```

Named unions use `a/defunion`, with the same field vectors and constructor calls:

```clojure
(a/defunion Payload
  {:attrs #{ak/enum}}
  [[:int :i32]
   [:float :f64]])

(Payload {:int 42})
```

Omit `ak/enum` for an untagged union. Use `{:type Tag}` for a named tag
type, or `{:layout :packed}` / `{:layout :extern}` for an explicit layout.
Field documentation and nested methods work just as they do in structs.

Anonymous `a/struct`, `a/enum`, `a/union`, and `a/opaque` use the same member
vector, optionally preceded by a container options map.
Evaluating them on the JVM returns an inspectable native type. For example,
`(a/struct [[:value {:var 1234} :i32]])` retains its static member, which can
be accessed with `a/field` and mutated with `ak/+=`.

Use `a/fn` for public container methods and `a/fn-` for private ones. Their
signature is name, return type, optional documentation/attributes, typed arguments,
then body—just like `a/defn`. Named constructors also work inside their own
struct methods, e.g. `(Point {:x 0.0 :y 0.0})`.

Container state uses `[:count {:var 0} :u32]`; container constants use
`[:limit {:const 100} :u32]`. Both are public by default. `:default` still
specifies an instance-field default, not a container constant.

Variable declarations put the type immediately after the name:
`(a/defvar count :u32 {:attrs #{ak/threadlocal}} 0)`. Omit the type when it can
be inferred, e.g. `(a/defvar mouse-down false)`. `:attrs` always takes a set,
even for one attribute; both native
declarations and JVM calls reject single values. Local compile-time variables
use `(ak/var 1 :i32 {:attrs #{ak/comptime}})`. Thread-local native storage is
resolved on each calling JVM platform thread; virtual threads are rejected
because they can migrate between OS threads.

Function Var metadata exposes the authored typed argument vectors and documents
the return type, including private and external functions, using ordinary Clojure
`doc` and editor documentation.

`(a/defextern external-function :i32 [[x :i32]])` already implies `extern`.
Declaring an external function does not load its native library. Newly defined
callers are checked by Zig without linking; provide their library/object inputs
before invoking them. Native type errors still fail during definition, while
missing external symbols fail when linking the call. Keep optional build/link
recipes in runnable `comment` forms, not as namespace-loading side effects.

Normal layout is the default. Use declaration options only for behavior that
differs from that default.

## Writing Zig with Clojure forms

Inside Aguafria declarations, Clojure data represents Zig syntax; it does not
introduce a second runtime abstraction:

| Clojure form | Zig meaning |
| --- | --- |
| `(f a b)` | function call |
| `(let [x 1] ...)` | immutable local by default |
| `(let [[x y] pair] ...)` | fixed array, tuple or vector destructuring |
| `(let [x (ak/var 1 :i32)] ...)` | typed mutable local; also owned mutable storage on the JVM |
| `if`, `when`, `cond` | Zig control flow |
| `while`, `doseq` | Zig loops |
| `(ak/= x value)` | assignment |
| `(ak/= [x y] pair)` | assign existing targets from one evaluated value in compiled code |
| `(a/set-many! target value ...)` | ordered assignments; later values may read earlier writes |
| `(a/field p :x)` | `p.x` |
| `(a/index values i)` | `values[i]` |
| `(Point {:x 1.0 :y 2.0})` | typed struct literal |
| `(a/init {:x 1.0 :y 2.0} Point)` | explicit value-first struct initializer |
| `(a/array [4 5 6] :u32)` | typed array initializer with inferred length |
| `(a/array [1 2] {:sentinel 0} :u8)` | sentinel array initializer with inferred length |
| `(a/type [:array 4 {:sentinel 0} :u8])` | sentinel array type (`[4:0]u8`) |
| `(a/init [1 2] [:array 2 :u8])` | initializer with an explicit type schema |
| `(k/++ left right)` / `(k/** values 3)` | native array concatenation / repetition |
| `(a/with-block :result (k/break :result 42))` | labeled block returning a value; keyword labels are not variables |
| `(a/get point :x)` / `(a/get points i)` | native field or indexed element access |
| `(a/get-in points [4 :x])` | nested native access through a literal vector of indices and fields |
| `(:x point)` / `(-> point :x)` | keyword field access, equivalent to `a/field` in native code and on JVM native handles; no default-value argument |
| `(let [{:keys [x y]} point] ...)` | native map destructuring; explicit renamed/nested bindings and `:as` are supported |
| `(a/defn sum :i32 [[{:keys [x y]} Point]] (k/+ x y))` | destructuring typed function arguments without changing their native types |
| `(k/for [{:keys [x y]} points] ...)` | destructuring loop captures; also works for optional, while and switch captures |
| `ak/...` | Zig operators, keywords, and `@builtins`; value calls also use the native JVM bridge |

Native destructuring evaluates each source once. Extracted fields are values,
not writable aliases; an explicit pointer capture's `:as` binding keeps the
pointer. Vector patterns support nesting, `:as`, and a trailing `&` slice.
Fields must exist in the native type (`:or` map defaults are rejected), and
native bounds checks still apply. Ordinary JVM Clojure keeps its own binding
semantics.

For example:

```clojure
(a/defn sum-to :u32
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
            [aguafria.zig :as a]))

(a/defn maximum :u32
  [[a :u32]
   [b :u32]]
  (std-math/max a b))
```

`a/cast` is a small source-rewriting macro and is thread-friendly:

```clojure
(-> iterator
    (flecs/ecs_field_w_size (ak/sizeOf Circle) 1)
    (a/cast [:c-pointer Circle]))
```

### Clojure-computed types and values

Use `a/clj!` to evaluate a Clojure expression in the caller's namespace and
embed its result as literal data inside an Aguafria declaration:

```clojure
(defn array-n [n] [:array n :u8])
(defn characters [s] (vec s))

(a/defconst message
  (a/clj! (array-n 5))
  (a/clj! (characters "hello")))
```

This emits the same `[5]u8` constant as a handwritten type and character vector.
Escapes also work in function signatures, bodies, and struct field types/defaults.
They execute when the declaration executes, not during macroexpansion or in
native code. Redefining a helper does not change an existing declaration: reevaluate that declaration
to capture the new result and hot-reload it. Native builds and calls do not
rerun the helper.

The expression can use namespace Vars, aliases, and surrounding Clojure lexical
bindings, but not native locals:

```clojure
(let [sss (fn [s] (vec (seq s)))]
  (a/defconst local-message
    (a/clj! (array-n 5))
    (a/clj! (sss "hello"))))
```

This also works inside a Clojure function: each invocation that executes the
declaration captures fresh host values. Results must be literal scalars, symbols,
vectors, maps, or sets. Lists and live JVM objects are rejected; use vectors for sequence
data. There is no generated-code mode or options argument.

## REPL and hot reload

`a/vector` constructs a typed SIMD vector on either side of the JVM/native
boundary: `(a/vector [1 2 3 4] :i32)` emits `@Vector(4, i32){1, 2, 3, 4}`.

Result-context builtins such as `k/ptrFromInt`, `k/ptrCast` and `k/bitCast`
need a destination type in Zig. From the JVM their unresolved calls retain
their operands until `(k/as call type)` supplies that context, then execute
native Zig. Printing an unresolved call reports that a result type is required;
it is not a pointer with a guessed pointee type. For explicitly aligned array
storage, use `(a/array bytes {:align (k/alignOf :u32)} :u8)`.
Unlike binding metadata, this requests aligned storage on the JVM too.

Pointer schemas use `[:* child-type]` or `[:* options child-type]`, for example
`[:* {:volatile? true} :u8]` and `[:* {:align 4} :u8]`. The default size is
single-item; use `:size :many`, `:size :slice`, or `:size :c` when needed.
Alignment expressions can use native results such as `(k/alignOf :i32)`.
`k/alignOf` accepts type schemas directly, including arrays and pointers; no
`a/type` wrapper is needed.

Numeric constructors, native arithmetic and numeric function results retain
their Zig type as `ZigValue`s. Use `k/+` and other native operations to keep
working with them, and `k/&` for an owned pointer to their storage. `k/var`
provides mutable storage; taking its address does not copy it. `(a/value x)`
explicitly extracts a JVM snapshot. Predicates remain ordinary JVM booleans,
so `false` behaves correctly in Clojure conditionals. Comptime-only numbers
retain their type but need a concrete runtime type before taking an address.

Use `(a/debug! expression)` (including as a `->` step) to inspect its Zig type
with a Clojure file, line and column. In native declarations it adds an opt-in
compiler inspection pass, not runtime logging; the expression still executes
once. From the JVM it returns the identical value after native reflection.
You can also inspect a top-level declaration with `(a/debug! #'my-function)`
or `(a/debug! (a/defn answer :i32 [] 42))`.

`(a/debug-reports)` returns structured reports. The same data is written
atomically to `.aguafria/debug/types.edn`. For tooling-only output:

```clojure
(a/configure! {:debug-output #{:file}})
```

Compiler reports cover expressions Zig actually analyzes, including generic
specializations, not every possible path through a file. Expressions requiring
a result type, such as a bare `k/intCast`, need an explicit `k/as` within the
probe. Failed inspection is reported as unavailable with diagnostics, never as
a guessed type. This is a reporting API/data file, not an installed nREPL/LSP or
clj-kondo integration. ZIR is untyped, whereas AIR is produced after semantic
analysis per function.

`(a/type-report "path/to/example.clj")` returns source spans for every form
and subform without loading or evaluating that file. `(a/type-report!
"path/to/example.clj")` writes the report under `.aguafria/types/`. Reports
use only exact-revision Zig compiler observations or ZLS hover results mapped
through the emitter. There is no Clojure-side type inference. Forms without a
Zig-tool result remain unresolved. Learn builds require ZLS matching Zig 0.17.0;
set `AGUAFRIA_ZLS` to its executable, or install it as `zls` on PATH. The
verified source revision and build instructions are in the
[migration report](ZIG_0_17_0_MIGRATION_2026-10-03.md#zls-for-zig-0170).
It analyzes generated Zig with Aguafria's pinned compiler. The original examples
are unchanged. Focus a code block and use Alt+Up/Down for keyboard inspection.

Start the project through the nREPL alias used by CIDER, Calva, or another
nREPL client:

```sh
clojure -M:dev:nrepl
```

Require the namespace and call its Vars normally. Re-evaluate only the changed
`a/defn`, `a/defconst`, `a/defvar`, or type declaration. Aguafria compiles
the affected native units and publishes their new dispatch targets.

Compilation can run asynchronously. Wait for a namespace or for all pending
work when a deterministic boundary is needed:

```clojure
(a/await! 'example.core)
(a/await!)
```

Inspect compilation and publication state at any time:

```clojure
(a/stats)
```

The returned data includes queued, compiling, finished, cached, failed, and
published declarations. Compiler failures retain the originating namespace,
Var, Clojure form, emitted Zig location, command, and Zig diagnostic.

Compatible changes update existing callers. Breaking signatures and layouts
create new native generations; live objects using an old layout remain on that
generation until callers migrate or the application restarts. This is the same
kind of practical boundary encountered when redefining Java-backed state in a
Clojure REPL.

`a/set-value!` changes a live `a/defvar` through its native storage without
compilation:

```clojure
(a/set-value! requests-served 0)
```

## Native values

Type constructors work both inside Aguafria declarations and in ordinary JVM
code. `ak/as` takes the value first and accepts the same type data as signatures:

```clojure
(ak/i32 (+ 1 1))                  ;; => 2
(ak/f32 (/ 7.0 3.0))              ;; => 2.3333333
(-> 42 (ak/as :i32))              ;; => 42
(with-open [items (ak/as [1 2 3] [:array 3 :u8])]
  (a/value items))               ;; => [1 2 3]
```

Inside Zig these emit checked `@as(type, value)` coercions. JVM calls use cached
in-process native adapters; composites own native storage and should be closed
with `with-open`. `^:var` marks mutable locals inside compiled Aguafria code;
it does not change Clojure's immutable `let` bindings. For either environment,
use an explicit mutable initializer and assignment:

```clojure
(let [value (ak/var (ak/as nil [:optional [:slice-const :u8]]))]
  (ak/= value "hi")
  (debug/print "{?s}\n" [value]))
```

On the JVM, `ak/var` owns native storage; use `with-open` to close it explicitly.
Inside an Aguafria `let`, the initializer emits a normal Zig `var`.
For mixed mutable and immutable destructuring, bind the elements first and
then rebind the mutable ones. The same source has ordinary lexical scope on
the JVM and emits native Zig locals, not runtime wrapper allocations:

```clojure
(let [[x y z] [1 2 3]
      x (ak/var x :u32)
      y (ak/var y :u32)]
  (ak/= y 100)
  (ak/= [:_ x :_] [4 5 6])
  (debug/print "{} {} {}\n" [x y z]))
```

Tuple elements can also be typed native values, such as structs, errors and
slices. Destructuring does not erase their types. Optimized Zig can eliminate
the intermediate bindings; Debug builds may retain them for debugging.

Coercing an existing native value can produce a view; keep its source open
while using that view. The result retains its source against garbage collection.

JVM call boundaries turn standard Zig assertion/safety panics into exceptions.
This is not a memory sandbox: native `defer` cleanup is skipped, so affected
native state may need reinitialization. Explicit process exits, traps, custom
abort handlers, background-thread panics and memory corruption can still
terminate the process. Standalone executables retain Zig's normal behavior.

Development libraries keep debug information by default. Contained panics
capture the native stack before returning to Java, then map available source
locations back to Clojure forms. The exception preserves the Zig panic message,
native frames, and standard `:clojure.error/source`, `:line`, and `:column`
metadata; no editor-specific mode is needed. Symbolication uses `atos` on macOS
or `addr2line` on glibc Linux. If symbols or those tools are unavailable, the
original panic and captured addresses remain available rather than inventing
a source location. Explicitly stripped builds can use
`{:development-debug-info :none}`.

On macOS, full-debug compilation also uses `dsymutil` (from Apple's developer
tools) to retain a `.dwarf` file beside each cached native library. Keep these
files together: they preserve source reporting independently of Zig's temporary
object cache. A missing debug file invalidates a full-debug cache entry.

Directly representable results return as ordinary Clojure values. Zig values
with native-only representation use typed Aguafria values backed by FFM
memory; they print and pretty-print as their real value and can be passed to
other Aguafria Vars.

Structs use maps, arrays and vectors use Clojure vectors, enums use keywords,
optionals use `nil` or their payload, tagged unions use single-entry maps, and
error unions use `{:ok value}` or `{:error ...}`. Typed pointer values remain
borrowed native pointers with explicit lifetime rules.

## Zig source and packages

Print a declaration or value as Zig with the pinned Zig formatter:

```clojure
(a/zig-source! #'main)                     ;; function declaration, without calling it
(a/zig-source! #'limit)                    ;; constant declaration, including its name
(a/zig-source! Point)                      ;; named type and its members/documentation
(a/zig-source! [:optional [:slice-const :u8]]) ;; ?[]const u8
(a/zig-source! #'debug/print)               ;; @import("std").debug.print
(with-open [items (ak/as [1 2 3] [:array 3 :u32])]
  (a/zig-source! items))                   ;; typed value expression
```

`zig-source!` writes to Clojure's `*out*` and returns `nil`; use `with-out-str`
to capture it. Pass a Var (`#'name`) or a quoted symbol for a declaration.
A plain JVM literal cannot identify the constant it originally came from.
This prints source; it does not execute the inspected declaration.

### Native loop bindings

`k/for` takes a flat vector of capture/input pairs. Inputs advance in parallel
(Zig's zipped iteration), not Clojure `for`'s nested iteration:

```clojure
(k/for [(k/* item) (k/& some-integers)
        index (a/range 0)]
  (k/= @item (k/intCast index)))
```

`(k/* item)` is a pointer capture in this binding position only; elsewhere `k/*`
is multiplication and needs at least two operands. `(a/range start)` emits an
open-ended `start..`, while `(a/range start end)` excludes `end`. These loops
also execute natively when evaluated directly on the JVM. Use typed native
arrays/slices for runtime iteration; heterogeneous tuples require inline loops.
The former nested `[[capture input] ...]` binding layout is rejected.

### Discover fields and functions

`a/describe` returns ordinary Clojure data about a native value, type constructor,
or Var. It uses Zig's type reflection, including specialized generic types:

```clojure
(select-keys (a/describe message) [:type :kind :fields])
;; For a [5]u8 array:
;; {:type "[5]u8", :kind :array,
;;  :fields [{:name :len, :type "usize"}]}

(let [description (a/describe (std/ArrayList :u21))]
  (:fields description)     ;; items: []u21; capacity: usize
  (:functions description)) ;; append, deinit, initCapacity, ... with signatures
```

`:members` lists public container declarations; `:functions`, `:constants`,
`:variables`, and `:types` group them by declaration kind. Zig methods are
functions with receiver parameters, so their signatures appear in `:functions`
alongside static functions. `:fields` describes instance fields separately.
Constants and variables are identified without reading their values.

Entries include documentation when available. Prepared accessor/function Vars
appear as qualified symbols under `:accessor` / `:var`, for example
`aguafria.std.ArrayList/-items` and `aguafria.std.ArrayList/append`. Resolve those
symbols to call them, or use `(a/field receiver :member)` directly.

Inspection does not read field contents, dereference receiver pointers, or call
the discovered functions. The first inspection of a type may compile a native
reflection adapter; subsequent calls reuse it. Zig reflection exposes public
container declarations, not private methods. Describing an unspecialized generic
accessor Var reports `:requires-receiver? true`; describe an actual receiver or
specialized type to get its concrete member signatures. A plain JVM scalar has
lost its original declaration identity; pass its Var to retain the declared Zig
type and documentation.

Directly describing a private declaration's Var/function value reflects inside
its defining module; it does not change the declaration's visibility. Listing a
container's members still follows Zig's public-declaration reflection rules.

A byte array and a string literal retain different native types. For example,
`[5]u8` decodes to `[104 101 108 108 111]`, while the literal `"hello"` has type
`*const [5:0]u8` and prints as a pointer. Pointer printing does not implicitly
dereference it. For a known-valid literal pointer, `(a/deref same-message)`
reads its array and `(a/slice same-message 0 5)` returns `"hello"`.

The converter translates a Zig file or tree into formatted Clojure namespaces
made from Aguafria declarations. It does not rely on `a/defraw`. The resulting
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

`a/build!` emits and builds an ordinary Zig library or executable. Release
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
- [Ghostty](examples/ghostty/README.md): native application conversion and
  editor reload workflows.
- [Simple game](examples/simple-game): Flecs, graphics, physics, and audio.

Each example owns its dependencies and generated output; examples do not
depend on one another.

## Project development

```sh
clojure -X:prepare
clojure -M:check-keyword
clojure -M:test
```

`:prepare` generates the std metadata catalog and namespace entry points under
ignored `generated/`. Neither is committed. First preparation uses Node.js
(`node`, or `AGUAFRIA_NODE`) and the pinned Zig toolchain; later preparations
reuse the catalog unless generator/toolchain inputs change or it is damaged.
Nested namespaces such as `aguafria.std.Io.File` then work with ordinary
`require`. Packaged JARs include the catalog and entry points. Local
and Git consumers can run `clojure -X:deps prep` before starting their REPL;
the Learn example exposes that standard dependency prep as `clojure -X:prepare`.

For third-party Zig packages, add `generated` to the project's `:paths` and use
`{:prepare {:exec-fn aguafria.zig.package/prepare!}}` in `:aliases`. Rerun
`clojure -X:prepare` when `aguafria-packages.edn` changes, then restart the REPL.
This updates the catalog and `aguafria.pkg.*` entry points, including removal of
obsolete ones. Both outputs live under ignored `generated/`, not committed
project resources. Ordinary Maven/Clojure dependencies still use normal tools.deps;
they are not automatically interpreted as Zig packages.

Hand-written namespaces with relative native imports can bundle those sources
in an `aguafria-project.edn` classpath resource:

```clojure
{:schema-version 1
 :asset-root "native"
 :asset-files ["helper.zig"]
 :modules {"my.app.math" {:source-kind :aguafria
                         :relative-path "math.zig"}}}
```

Here `native/helper.zig` is copied beside the compiled module for
`(a/defimport helper "helper.zig" [...])`. `:source-kind :aguafria` retains
normal hand-written declaration semantics; the catalog only supplies assets.
Raw `defimport` members currently work inside native declarations, not as
standalone JVM calls. Preparation reports those calls as unsupported while
preparing their concrete enclosing functions.

### Explain native compilation and cache reuse

Wrap ordinary evaluations with `a/explain!`:

```clojure
(a/explain!
  (a/defn add :i32 [[x :i32]] (k/+ x 1)))
(a/explain! (add 41))
```

The forms execute normally, once, with their usual side effects. The wrapper
returns the same result or propagates the same exception and prints actual
compiler/cache activity to stdout (including the REPL output). `compiled` means
a native artifact was built, `disk-cache-hit` means an existing Aguafria artifact
was reused, and `memory-cache-hit` means native code already loaded in this JVM
was reused. These are not statistics for Zig's internal compilation cache.
Reporting does not force compilation, run extra warm-ups, or wait for async
work; conveyed async events can arrive after the immediate summary.

### Optional JVM precompilation

Run this explicitly when desired, never as part of `:prepare`:

```sh
clojure -X:precompile :namespaces '[my.app.audio my.app.math]'
```

Or from Clojure: `(a/precompile! {:namespaces '[my.app.audio my.app.math]})`.
This requires the namespaces and compiles concrete native function bodies and
JVM wrappers without invoking those functions. Ordinary Clojure top-level code
still runs during `require`. Preparation also compiles the initial images used
by ordinary namespace loading, reported under `:namespace-images`. Lazy
converted namespaces and extern-link-on-demand modules are reported as skipped.
Dependencies already registered in the current REPL are included from the
native compilation snapshot, so restarting that REPL does not leave their
initial images unprepared.
Test definitions encountered during loading use the ordinary native check path
at the same registration point. Preparation never runs those test bodies;
`:test-checks` under each namespace image records success or compiler errors.
Namespaces containing only host Clojure code are reported as having no native
preparation work.
The report lists declarations skipped because they
need a generic specialization, comptime result, extern linkage, test runner or
process-entry host. Compilation failures propagate normally.

Native handlers can be discovered automatically or prepared from explicit signatures.

Automatic discovery can also inspect emitted Zig operations without running
native bodies. From `examples/learn`, inspect every example namespace with:

```sh
clojure -X:precompile :source-dirs '["resources/learn/example"]' :parallelism 2
```

Or select namespaces with `:analyze '[my.app.math]`. Directories must already
be on the classpath. The same API works for application and library namespaces;
Learn is a coverage corpus, not a special precompilation path. For example:

```clojure
(a/precompile! {:analyze '[my.app.audio my.app.math]
                 :parallelism 4})
```

Use `:ignore '[my.app.expensive-example]` to exclude selected namespaces before
loading/precompiling them. Reports list `:ignored` separately, never as prepared.
This excludes direct selections, not transitive imports required by another
namespace, and does not disable ordinary evaluation or documentation tests.

Zig's compile-time reflection supplies the observed operand
types and literal values; emitter records link them to the existing JVM handler
generators. There is no Clojure type inference. This uses compiler reflection,
not an AIR dump. Two bounded virtual-thread
workers run by default. Ordinary Clojure namespace loading is sequential.

The report is written to `.aguafria/precompile/report.edn` (override with
`:report-file`), with per-namespace checkpoints in its `.d` directory. Compiler
errors, unresolved specializations, unsupported types/storage placements and
handler compilation failures remain explicit. This is **not yet exhaustive
warming of all JVM subforms**. Inspection uses `zig test --test-no-exec
-fno-emit-bin`; no native example or test body is called. Native libraries are
compiled to disk without loading them. Native invocation is rejected during
preparation; Zig still executes its normal `comptime` logic, and `require` still
evaluates ordinary top-level Clojure code/macros.

Imported calls such as `testing/expectEqual` reuse their JVM handler when an
untyped integer or float changes but its native counterpart's type stays the
same. Aguafria checks the original Zig source: the function must first convert
all arguments to a common type and pass them to a normal typed helper. Zig's
`@TypeOf` confirms that type in a compile-only check. Float literals retain
their decimal spelling and Zig rounding; integer range checks still apply.
Calls that inspect the original argument types or require comptime values keep
their existing specialization. This changes JVM adapters, not emitted lesson
or application code. Preparation and normal evaluation use the same planner.
Their callable source generator and artifact lookup are shared too: a valid
bundle entry is used before a standalone library, without compiling a duplicate
dylib. Already-loaded handlers do not bypass disk preparation. Source, toolchain
or compiler-option changes can still require a new artifact.

Concrete function preparation includes constructors for its native input/result
types. Primitive literal constructors also prepare the owned storage used by
direct JVM `k/var` initialization. Named type aliases retain their defining
namespace when their constructor is placed in a shared adapter.

Inspection first compiles an uninstrumented baseline. If probes make valid
source fail, smaller probe groups isolate those failures so other operations
can still be discovered. Reports retain the baseline diagnostics, isolated
probe failures and partial contextual-cast preparation. Field/index probes
preserve lvalue storage; compound assignment handlers use the normal bridge
planner. A `:prepared` operation is not a claim that its enclosing body is
fully warmed.

For explicit signatures:

```sh
clojure -X:precompile :calls '[{:function aguafria.keyword/+ :args [:i32 :i32]}]' :coercions '[:i32]'
```

`:namespaces`, `:calls` and `:coercions` can be combined. `:coercions` prepares
constructors for the listed type schemas, including addressable numeric storage.
Call argument entries are native type schemas; `{:comptime value}` supplies a
source-level argument for builtin/imported/generic function specialization.
Operator signatures require native operand types, not untyped source literals.
Call adapters use the same registration/cache path as JVM invocation but never
invoke the body. Unsupported storage/constructor adapter paths fail explicitly.
No warm-up workload is executed, and no `main`, test or comment form is called.
Zig still performs normal compile-time evaluation. This does not enumerate all
possible generic specializations or all separately evaluated JVM subforms.
Your project can define a `:precompile` alias with
`:exec-fn aguafria.zig.precompile/precompile!` and native-access JVM options;
the root and Learn projects already include it.

Both paths populate the shared `~/.aguafria/zig` cache by default. Override it
with `-Daguafria.cache-dir=...` or `a/configure!`'s `:cache-dir` option.
Preparation reports remain project-local under `.aguafria/precompile`;
they are not the binary cache. Existing project-local caches are not moved or deleted.
Later JVMs and projects reuse
those binaries when compiler, target, build options and dependencies match.
Live values, function handles and native state are not persisted; first-use
loading still costs time. New specializations or invalidated inputs still build.

Native artifact and bundle keys use full SHA-256 with canonical map/set encoding
and explicit key-format/native-ABI versions. Source, argument order, compiler,
target, dependencies and build settings remain significant. External file inputs
are hashed by content, not modification time; relocatable `.o`/`.obj` inputs can
be reused after relocation. Source-module, shared-library and archive paths stay
significant because location may affect `@src`, relative assets or linking.
Key-version changes invalidate old entries without deleting them. There is no
fallback to the old key format; binaries are rebuilt/prepared under the new keys.

Explicit precompilation packs generated JVM handlers into one immutable native
library per preparation, without a handler-count cap.
Already cached handlers are included, so earlier packs do not leave the new
preparation split across libraries. Incompatible compiler configurations produce
an explicit error rather than silently creating multiple bundles. Fresh
preparation validates and links the collected handlers in a single compiler build;
it does not load or execute them. A rejected build aborts preparation with its
diagnostics, without publishing the pack or retrying smaller groups. A handler
that cannot join the pack is reported as unsupported, not built separately.
The report's `:bundles :compiler-invocations` counts pack builds only. Compiler
type queries, namespace images, infrastructure and native test programs currently
still use additional compiler invocations during preparation.
`:compiler-work :compiler-invocations` counts the whole preparation, including
rejected compiler attempts and infrastructure builds. Its `:one-compilation?`
flag therefore stays false while those extra passes remain. Debug-information
and compiler-metadata commands are counted separately; samples are bounded.

Runtime lookup checks a content-keyed bundle index first, then the individual
artifact, then compiles a missing specialization normally. It never scans packs.
Warm maps have expected constant-time lookup; hashing inputs, cold file reads,
native loading and FFM binding still cost time. `a/explain!` reports
`bundle-cache-hit` and `bundle-loaded` events. Later runtime misses populate the
same shared cache as standalone artifacts; a subsequent explicit precompilation
can pack them. Existing standalone binaries are not automatically pruned.
Loaded bundle arenas remain alive until JVM exit so native pointers and cleaners
cannot outlive their code. Restart the JVM after explicitly clearing the cache.

Generated JVM adapters default to `safe`, with safety checks, error tracing
and unwind information. Their allocation/result-buffer machinery and panic guard
live in a shared, optimized support library. Debug symbols are retained by default.
This does not change the `:optimize` setting for ordinary user modules or standalone
builds. Use `(a/configure! {:jvm-optimize "debug"})` (or the JVM property
`aguafria.jvm-optimize`) when debugging adapters; only `debug` and `safe`
are accepted. Preparation and runtime use the same adapter configuration/cache keys.

Kaocha configuration lives in [`tests.edn`](tests.edn). Release packaging uses
the deps.edn-native [`build.clj`](build.clj) tasks through the
[`Makefile`](Makefile):

```sh
make package
make verify
make publish
```
