# Pure Zig Ghostty through Aguafria

This workspace contains no Clojure application code and no generated Aguafria
namespaces. It points the Aguafria VS Code extension at the existing pinned
Ghostty Zig submodule in `../ghostty/vendor/ghostty`.

1. Open this directory in VS Code with the Aguafria extension under
   `tools/vscode`.
2. Run **Aguafria: Jack In**. Cold indexing of the pinned Ghostty graph is
   cached within that JVM; reconnecting does not index it again.
3. Open an ordinary Ghostty `.zig` file beneath
   `../ghostty/vendor/ghostty`.
4. Put the cursor in a top-level declaration and run
   **Aguafria: Evaluate Current Zig Declaration**.
5. Inspect build/publication state through
   **Aguafria: Show Hot-Reload Status**.

This workspace targets Ghostty's reusable Zig libraries. It deliberately does
not invoke Ghostty's AppKit application entry point inside the JVM: macOS owns
that UI entry point and requires the process main thread, while Aguafria's
native development host is a JVM-owned thread. Live Zig library declarations
are still compiled, invoked, versioned, and inspected in that same JVM. The
ordinary Ghostty macOS app and VT library remain standalone Zig build targets.

Tested live edits include the leaf `toKibiBytes` declaration and the
`Size = lib.Struct(...)` comptime-produced type in `src/size_report.zig`.
Compatible changes publish in place; layout changes create retained versions;
invalid edits keep the preceding generation active with a mapped diagnostic.

The conventional `.nrepl-port` and authenticated runtime handshake are written
to this wrapper workspace. Ghostty's submodule stays pristine.
