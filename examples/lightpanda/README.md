# Lightpanda through Aguafria

This example converts the pinned Lightpanda submodule into ordinary Clojure
namespaces containing `az/defn`, `az/defconst`, containers, and `ak/...` Zig
operations. Development runs in one JVM with nREPL; the final artifact is an
ordinary optimized Zig browser with no JVM or Aguafria development runtime.

## Generate and build

From this directory:

```sh
git submodule update --init vendor/lightpanda
make -C vendor/lightpanda download-v8
clojure -M:generate
clojure -M:standalone
```

Generation writes only beneath `generated/`. Standalone materialization reads
the generated catalog, Clojure namespaces, and bundled assets, then recreates
the project beneath the ignored `build/standalone/` directory without reading
the vendored `.zig` files.

The resulting browser is:

```sh
./build/standalone/zig-out/bin/lightpanda version
./build/standalone/zig-out/bin/lightpanda fetch --dump html \
  "data:text/html,<script>document.write('Lightpanda-'+(6*7))</script>"
```

## One-JVM development

Start `clojure -M:nrepl`, connect Calva/CIDER, open
`lightpanda-agua.core`, and evaluate its final `comment` form. It demonstrates:

- direct calls to generated Lightpanda Vars;
- a real JavaScript/DOM browser command on a native thread in the JVM;
- a compatible leaf edit;
- an edit to converted Lightpanda code; and
- a real comptime type-factory edit while existing native state keeps the
  same address.

The automated edit/restore matrix is:

```clojure
(require '[lightpanda-agua.hot-reload-benchmark :as hot])
(hot/run-all!)
```

Use `(lightpanda-agua.core/status)` and `(aguafria.zig/stats)` for plain EDN
compiler, cache, generation, host, and failure state. A failed Zig compilation
reports the originating Clojure namespace/form first and retains the complete
Zig diagnostic and command in exception data.

Lightpanda's finite CLI `main` owns process-global upstream allocator state.
Run one such top-level CLI command per JVM; direct generated Vars and the
Aguafria live state remain available for continuous REPL development. A
long-lived server main is itself the intended development host and does not
need to be restarted for compatible declaration publications.

