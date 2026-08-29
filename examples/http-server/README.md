# Aguafria HTTP server

A complete native HTTP server in one Aguafria namespace. The server code does
not contain a reload callback, router proxy, or development-only indirection.
Its ordinary call to `response-body` becomes live automatically in development.

This project uses the published macOS ARM64 artifact by default. It does not
need Zig on `PATH`; Aguafria extracts its bundled Zig 0.16.0 toolchain.

It also consumes `uuid-zig` as a normal pinned Zig dependency. Prepare its EDN
Var catalog once (and whenever the package pin changes):

```sh
clojure -X:prepare-packages
```

The server then requires `[aguafria.pkg.uuid :as uuid]` and calls ordinary
catalog Vars such as `uuid/v4-new` and `uuid/urn-serialize`. The generated
catalog is checked in, so a prepared checkout remains editor- and REPL-friendly
without dependency-specific Clojure wrapper files.

## Run and hot reload

Start nREPL:

```sh
clojure -M:nrepl
```

Connect Calva/CIDER to the printed port, open
`src/aguafria_http/server.clj`, and evaluate:

```clojure
(require '[aguafria-http.server :as server])
(server/start!)
(slurp server/server-url)
;; => "Hello from live Aguafria Zig!\n"
```

Change the string in `response-body` and evaluate only that `az/defn`. After
`az/await!` finishes its background native publication, the same listener and
the same JVM serve the new result:

```clojure
(aguafria.zig/await! 'aguafria-http.server)
(slurp server/server-url)
(server/status)
```

Stop it with `(server/stop!)`. To work on Aguafria itself, start Clojure with
`-M:local-aguafria:nrepl`; the local checkout is never selected by default.

## Standalone

The same definitions build a JVM-free optimized executable:

```sh
clojure -M:standalone
./build/http-server
curl http://127.0.0.1:8787/
```

On Apple Silicon with Zig 0.16.0, the verified `ReleaseFast` executable with
UUID request IDs is a 420,216-byte (410 KiB) arm64 Mach-O. The JVM is involved only in generating and
building it; running `build/http-server` is an ordinary native Zig process.

Linux x86-64 users can replace the macOS coordinate in `deps.edn` with
`io.github.pfeodrippe/aguafria-linux-x86-64` at the same version.
