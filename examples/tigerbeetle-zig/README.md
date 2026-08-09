# Pure Zig TigerBeetle through Aguafria

This workspace contains no Clojure application code and no generated Clojure
namespaces. It points the Aguafria VS Code extension at the pinned TigerBeetle
checkout in `../tigerbeetle-agua/vendor/tigerbeetle`.

1. Open this directory in VS Code with the Aguafria extension from
   `tools/vscode`.
2. Run **Aguafria: Jack In**. The extension indexes the Zig project, builds an
   instrumented development binary outside the upstream checkout, formats a
   local development replica when needed, and starts it on
   `127.0.0.1:53011`.
3. Open an ordinary TigerBeetle `.zig` file below
   `../tigerbeetle-agua/vendor/tigerbeetle`.
4. Put the cursor in a top-level declaration and run
   **Aguafria: Evaluate Current Zig Declaration**. File and changed-form
   evaluation are available from the command palette.
5. Inspect compilation, retained generations, and the live process through
   **Aguafria: Show Hot-Reload Status**.

The development replica and publication manifest live under this wrapper's
ignored `.aguafria/` directory. TigerBeetle's checkout remains pristine.
Normal TigerBeetle builds do not include Aguafria, the JVM, Clojure, or nREPL.

The extension supplies `AGUAFRIA_PUBLICATION_MANIFEST` to the development
replica it starts. A TigerBeetle CLI launched manually must receive the same
manifest so it observes the newest parser/client generation too:

```sh
cd examples/tigerbeetle-zig
AGUAFRIA_PUBLICATION_MANIFEST="$PWD/.aguafria/publications.manifest" \
  ../tigerbeetle-agua/build/editor-development/tigerbeetle repl \
  --cluster=0 --addresses=127.0.0.1:53011 \
  --command='lookup_accounts id=1'
```

The verified acceptance workflow covers a direct body edit; a newly added
helper used by an existing caller; the real generic
`object_default(comptime operation) ObjectType(operation)` parser; a
whole-file changed-declaration batch; an invalid edit that retains the
preceding generation; and transaction-visible durable behavior against the
same live replica. The final batch published only the exact comptime-dependent
frontier and changed an omitted transfer amount to `75` without restarting the
JVM or replica.
