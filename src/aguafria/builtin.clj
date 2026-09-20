(ns aguafria.builtin
  "Zig's compiler-provided @import(\"builtin\") module, not std.builtin.
  Require as builtin and refer to members such as builtin/is_test."
  (:refer-clojure :only [])
  (:require [aguafria.zig]
            [aguafria.zig.std]))

(aguafria.zig.std/install-builtin! clojure.core/*ns*)
