(ns aguafria.zig.precompile-module-type-exports-fixture
  (:require [aguafria.zig :as a]
            [aguafria.zig.precompile-module-type-leaf-fixture :as leaf]))

(a/defconst Container leaf)
