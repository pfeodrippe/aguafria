# Aguafria Zig for VS Code

Edit ordinary `.zig` files and publish a top-level declaration into a live
Aguafria native runtime. The project remains Zig: no generated Clojure source
is created, and a normal standalone Zig build contains none of the JVM, nREPL,
editor, or development-dispatch machinery.

Requirements: a trusted workspace, Java 22 or newer, Clojure CLI, and Zig 0.16
on `PATH`.

## Start and evaluate

1. Open a Zig workspace containing `build.zig` or `aguafria.edn`.
2. Run **Aguafria: Jack In** (or **Start Development Program**).
3. Put the cursor in a top-level declaration.
4. Run **Evaluate Current Zig Declaration**, or press `Cmd+Alt+Enter` on macOS
   (`Ctrl+Alt+Enter` elsewhere).

Selection, changed-file, and whole-file commands use the complete unsaved
buffer. The JVM parser validates declaration boundaries and source hashes;
VS Code is never the structural authority.

Publication success means the native generation is installed—not merely that
the edit was queued. Compiler failures appear in the Problems panel at the
original unsaved Zig range and identify the generation that stayed active.

## Runtime lifecycle

The extension starts one authoritative JVM per workspace and writes:

- `.nrepl-port` for conventional local discovery;
- `.aguafria/runtime.edn` for project/runtime identity;
- `.aguafria/editor-token` with user-only permissions when authentication is
  explicitly enabled; and
- `.aguafria/editor.lock` to prevent two JVMs from claiming the workspace.

**Disconnect Editor** leaves that JVM and native state alive. **Reconnect**
verifies the protocol, capabilities, project id, runtime id, JVM pid, optional
token, and canonical roots before using it. **Stop Development Program** asks
that exact identified runtime to stop itself, so it remains reliable after a
complete Extension Host reload. Discovery files are removed by the server only
when their exact contents still match.

Each evaluation carries the last source hash accepted from that runtime. If a
second editor has published a newer buffer, Aguafria rejects the stale edit and
reports the accepted hash/generation instead of silently overwriting it.

The transport is ordinary nREPL with namespaced `aguafria/*` operations. The
token is shared by the runtime, not bound to VS Code, so multiple VS Code,
Emacs, Vim, CLI, CIDER, or other nREPL clients can connect concurrently.
The default is conventional unauthenticated loopback nREPL behavior: no token
file is created or required. Projects that want the additional local token
check can set `:authentication? true` in `aguafria.edn`, or set
`AGUAFRIA_EDITOR_AUTH=true`. This does not limit the number or kind of clients;
they all use the same project-local token.

## Inspection

**Inspect Declaration/Value** uses Aguafria's native value decoder. Structs,
arrays, unions, optionals, packed layouts, and arbitrary-width integers remain
exact; large integers and pointers use explicit tagged wire values rather than
lossy JavaScript numbers. **Show Emitted Hot Slice**, **Show Generation
History**, and the Aguafria tree expose compilation and retained versions.

## Configuration

`aguafria.edn` is optional for a simple project. Useful keys include
`:project-root`, `:namespace-prefix`, `:entry-point`, `:entry-function`,
`:arguments`, `:build-profiles`, `:bootstrap?`, `:start-program?`, `:async?`,
and `:max-source-bytes`. Paths are relative to the workspace containing the
file. VS Code settings select the Clojure executable, JVM maximum heap, startup
timeout, publication barrier, and whether an owned runtime survives extension
deactivation. `aguafria.allowExternalProjectRoot` must be enabled explicitly
when a trusted `aguafria.edn` intentionally points outside the open workspace.

Library projects can set `:start-program? false`: their declarations are still
compiled, invoked, inspected, and hot-reloaded in the authoritative JVM.

## Standalone builds

Use the project's ordinary `zig build` command. Aguafria's editor cache lives
under `.aguafria/` and is not an input to the release artifact. Native release
performance and binary contents are therefore the same concern as in the
original Zig project.
