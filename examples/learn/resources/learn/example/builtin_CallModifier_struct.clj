(ns learn.example.builtin-CallModifier-struct
  (:require [aguafria.zig :as az]))

(az/defenum CallModifier
  [[:auto {:doc "Equivalent to an ordinary function call."}]
   [:never_tail {:doc "Keep this call's return address; reject required tail calls or inlining."}]
   [:never_inline {:doc "Prevent inlining; reject a function that requires it."}]
   [:no_suspend {:doc "Assert that the call will not suspend, including an async callee."}]
   [:always_tail {:doc "Require a tail call, or produce a compile error."}]
   [:always_inline {:doc "Require inlining, or produce a compile error."}]
   [:compile_time {:doc "Require compile-time evaluation, or produce a compile error."}]])
