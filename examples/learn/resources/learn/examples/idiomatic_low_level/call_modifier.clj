(ns learn.examples.idiomatic-low-level.call-modifier
  (:require [aguafria.zig :as az]))

(az/defconst CallModifier
  (az/container {:kind :enum}
    (az/enum-field-decl :auto "Equivalent to an ordinary function call.")
    (az/enum-field-decl :never_tail
      "Keep this call's return address; reject required tail calls or inlining.")
    (az/enum-field-decl :never_inline
      "Prevent inlining; reject a function that requires it.")
    (az/enum-field-decl :no_suspend
      "Assert that the call will not suspend, including an async callee.")
    (az/enum-field-decl :always_tail
      "Require a tail call, or produce a compile error.")
    (az/enum-field-decl :always_inline
      "Require inlining, or produce a compile error.")
    (az/enum-field-decl :compile_time
      "Require compile-time evaluation, or produce a compile error.")))
