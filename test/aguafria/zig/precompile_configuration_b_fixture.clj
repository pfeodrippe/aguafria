(ns aguafria.zig.precompile-configuration-b-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig.precompile-configuration-a-fixture]
            [aguafria.zig :as a]))

(a/configure! {:zig-args (conj (:zig-args (a/configuration)) "-lc")})

(a/defn increment :i32 [[x :i32]]
  (k/+ x 1))
