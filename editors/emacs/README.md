# Scoped evaluation in Emacs

With a JVM CIDER connection to a project using this Aguafria checkout, load:

```elisp
(load-file "/path/to/aguafria/editors/emacs/aguafria-eval.el")
```

- **`M-x aguafria-eval-buffer`** — evaluate the current buffer.
- **`M-x aguafria-eval-namespaces`** — evaluate a space/comma-separated namespace list.
- **`M-x aguafria-eval-stop`** — stop scheduling forms. CIDER's usual interrupt
  command is available for an in-flight evaluation.

The runner uses **`cider-load-buffer`**, then **`cider-interactive-eval`** on each
original source region, wrapped in **`(aguafria.zig/explain! ...)`** without
editing the buffer. CIDER's normal handlers display values, result overlays,
stdout/stderr and errors, according to your existing CIDER settings. There is no
separate output buffer, REPL, value renderer, or custom evaluator.
The small JVM helper only locates body ranges; its metadata query uses CIDER's
existing tooling session without overwriting the REPL's result history.

`a/explain!` prints the call-site **file, line, and source** before evaluation,
followed by compilation/cache events and their summary. Each report is separated
by blank lines and **`>>> BEGIN explain` / `<<< END explain`** markers, including
when evaluation throws.

The automation passes the **exact buffer region** in `:aguafria.explain/source`
metadata: newlines, indentation, comments, reader syntax and unsaved edits are
preserved rather than reconstructed from parsed forms or reread from stale disk
contents. This metadata is display-only. Manual calls without source metadata
fall back to Clojure code pretty-printing. CIDER sends the original file/line;
the wrapper adds no newlines that would shift them.

`bundle-cache-hit` indicates bundle reuse; `memory-cache-hit` indicates already
loaded code, and `disk-cache-hit` can refer to a standalone cached artifact.
`compiled` or `compile-failed` indicates new compilation. This traces the body
forms, not the preceding namespace load, and is not a strict zero-compiler gate.

Each complete body-level form is evaluated once. A `let`, loop, or conditional
stays intact: its children run normally, not as separate probes. Declarations
and initializers load once from the current buffer, including unsaved edits.
Comment-block entrypoints are not invoked again. Helpers called by a body still
run normally. Functions requiring arguments or pre/post contracts are skipped
rather than given fabricated inputs.

Each CIDER response completes before the next evaluation is sent. Errors and
source edits stop the sequence. Returned error values remain values, just as in
manual CIDER evaluation; this is not a separate assertion-verification gate.

The current form keeps its background highlight throughout the pause:

```elisp
(setq aguafria-eval-delay 0.5) ; seconds after each evaluation
```

## Tests

- `test/aguafria/zig/repl_test.clj`: non-evaluating source-range planning, aliases,
  argument handling, literals and reader syntax.
- `examples/learn/test/learn/editor_eval_test.clj`: ordinary evaluation of the
  planned Values/vector/pointer/optional/array bodies, with native expectations.
- `editors/emacs/aguafria-eval-test.el`: CIDER delegation, response forwarding,
  highlighting, sequencing, and stopping on errors/edits.
- `examples/learn/test/learn/editor_eval_integration.el`: actual Emacs/CIDER,
  verifying 18 `explain!`-wrapped evaluations on the existing REPL, correct
  file/line and verbatim-source reports with boundaries, no custom output buffer,
  no repeated Values output, and an unsaved-source/error-stopping fixture.
  Run from the repository root with `AGUAFRIA_TEST_NREPL_PORT` set to a separate
  test CIDER nREPL with the Learn source/resources on its classpath.
