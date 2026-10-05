(ns aguafria.zig.precompile-member-exports-fixture
  (:require [aguafria.zig :as a]
            [aguafria.zig.precompile-member-leaf-fixture :as leaf]))

(a/defconst api leaf)
