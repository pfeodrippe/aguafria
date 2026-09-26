(ns learn.example.builtin-CallModifier-struct
  (:require [aguafria.zig :as az]))

(az/defenum CallModifier
  [[:auto {:doc "Equivalent to function call syntax."}]
   [:never_tail {:doc "Prevents tail call optimization. This guarantees that the return
address will point to the callsite, as opposed to the callsite's
callsite. If the call is otherwise required to be tail-called
or inlined, a compile error is emitted instead."}]
   [:never_inline {:doc "Guarantees that the call will not be inlined. If the call is
otherwise required to be inlined, a compile error is emitted instead."}]
   [:no_suspend {:doc "Asserts that the function call will not suspend. This allows a
non-async function to call an async function."}]
   [:always_tail {:doc "Guarantees that the call will be generated with tail call optimization.
If this is not possible, a compile error is emitted instead."}]
   [:always_inline {:doc "Guarantees that the call will be inlined at the callsite.
If this is not possible, a compile error is emitted instead."}]
   [:compile_time {:doc "Evaluates the call at compile-time. If the call cannot be completed at
compile-time, a compile error is emitted instead."}]])
