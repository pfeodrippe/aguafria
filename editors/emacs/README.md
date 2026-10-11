# Scoped evaluation in Emacs

With a JVM CIDER connection to a project using this Aguafria checkout, load:

```elisp
(load-file "/path/to/aguafria/editors/emacs/aguafria-eval.el")
```

- **`M-x aguafria-eval-buffer`** — evaluate the current buffer.
- **`M-x aguafria-eval-highlight-buffer`** — highlight all forms that would be
  evaluated, using the same metadata-only plan. It does **not** load the buffer
  or evaluate its code; the namespace must already be loaded in CIDER.
- **`M-x aguafria-eval-clear-highlights`** — clear the preview. Editing the source
  also clears it automatically.
- **`M-x aguafria-eval-namespaces`** — evaluate a space/comma-separated namespace list.
- **`M-x aguafria-eval-stop`** — stop scheduling forms. CIDER's usual interrupt
  command is available for an in-flight evaluation.

The runner uses **`cider-load-buffer`**, then **`cider-interactive-eval`** on each
original source region, wrapped in **`(aguafria.zig/explain! ...)`** without
editing the buffer. CIDER's normal handlers display values, result overlays,
stdout/stderr and errors, according to your existing CIDER settings. There is no
separate output buffer, REPL, value renderer, or custom evaluator.
The small JVM helper only locates source ranges; its metadata query uses CIDER's
existing tooling session without overwriting the REPL's result history.

`a/explain!` is quiet by default: successful bundle/in-memory reuse produces no
headings or summaries. Disk hits, compilation and failures remain visible, with
the responsible **file, line, and source**. Exceptions also reveal their form.
Visible reports have blank lines and **`>>> BEGIN explain` / `<<< END explain`**
markers. Ordinary program output is never suppressed.

For full tracing, including every form, cache hits and summaries:

```clojure
(binding [aguafria.zig.explain/*verbose* true]
  (a/explain! ...))
```

Reporting does not wait for futures; a late conveyed event gets its own complete
source-labelled report.

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

Each complete body-level form and native `a/defconst` / `a/defvar` initializer
is evaluated once through the ordinary JVM path. A `let`, loop, or conditional
stays intact: its children run normally, not as separate probes. Explicit native
initializer types are retained with `aguafria.keyword/as`; the displayed source
remains the original initializer. Loading a native declaration registers its
initializer but does not perform this ordinary JVM evaluation. Ordinary Clojure
`def` initializers are not replayed after the buffer load. Unsaved edits are used.
Comment-block entrypoints are not invoked again. Helpers called by a body still
run normally. Functions requiring arguments or pre/post contracts are skipped
rather than given fabricated inputs. Explicitly `noreturn` native helper bodies
are also skipped.

Preview highlights whole lexical forms, not every nested call separately. A
single highlighted `let` includes the calls within it, preserving their normal
scope and control flow. Preview does not move point, modify or save the buffer,
change REPL result history, or call CIDER's load/evaluation commands. Stale
responses are discarded if the buffer changes during the metadata query.

`k/switch` is indented like `case`. This file installs the clojure-mode rule;
the Var also exposes `:style/indent 1` for CIDER. The root and Learn
`.cljfmt.edn` files provide the corresponding rule for cljfmt/Calva.

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
  verifying verbose and quiet `explain!`-wrapped evaluations,
  correct file/line and verbatim-source reports with boundaries, no custom output
  buffer, no repeated Values output, and an unsaved-source/error-stopping fixture.
  Run from the repository root with `AGUAFRIA_TEST_NREPL_PORT` set to a separate
  test CIDER nREPL with the Learn source/resources on its classpath.
