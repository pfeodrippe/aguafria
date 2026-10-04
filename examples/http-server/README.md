# Aguafria HTTP server

A complete native HTTP server in one Aguafria namespace. The server code does
not contain a reload callback, router proxy, or development-only indirection.
Its ordinary call to `serve-connection!` becomes live automatically in development.

This project uses the local Aguafria checkout and its bundled Zig 0.17.0
toolchain by default. Zig does not need to be on `PATH`.

It also consumes `uuid-zig` as a normal pinned Zig dependency. Prepare its EDN
Var catalog once (and whenever the package pin changes):

```sh
clojure -X:deps prep
clojure -X:prepare
```

The server then requires `[aguafria.pkg.uuid :as uuid]` and calls ordinary
catalog Vars such as `uuid/v4-new` and `uuid/urn-serialize`. Preparation writes
the catalog and namespace entry points to ignored `generated/`. Neither is
committed. Start a new REPL after preparing; package namespaces then load
through ordinary `require`.

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

Change the string in `serve-connection!` and evaluate only that `a/defn`. After
`a/await!` finishes its background native publication, the same listener and
the same JVM serve the new result:

```clojure
(aguafria.zig/await! 'aguafria-http.server)
(slurp server/server-url)
(server/status)
```

Stop it with `(server/stop!)`.

To run the HTTP/live-reload regression, start `clojure -M:test:nrepl`
and evaluate `(require 'aguafria-http.server-test)` followed by
`(clojure.test/run-tests 'aguafria-http.server-test)`. It makes loopback requests,
publishes two edits through ordinary `a/defn` evaluation, checks native host
identity and retained request counts, restores the handler, and stops the server.

## Standalone

The same definitions build a JVM-free optimized executable:

```sh
clojure -M:standalone
./build/http-server
curl http://127.0.0.1:8787/
```

On Apple Silicon with Zig 0.17.0, the verified `fast` executable with UUID
request IDs is a 416,824-byte (407 KiB) arm64 Mach-O. The JVM generates and
builds it; running `build/http-server` is an ordinary native Zig process.

The checkout selects the bundled toolchain for the host platform.
