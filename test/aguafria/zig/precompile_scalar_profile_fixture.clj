(ns aguafria.zig.precompile-scalar-profile-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-configuration-a-fixture]))

(a/configure! {:zig-args (conj (:zig-args (a/configuration)) "-lc")})

(a/defn increment :u64 [[x :u64]]
  (k/+ x 1))
