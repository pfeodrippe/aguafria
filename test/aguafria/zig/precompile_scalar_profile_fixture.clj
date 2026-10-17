(ns aguafria.zig.precompile-scalar-profile-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-configuration-a-fixture]
            [clojure.core :as c]))

(a/configure! {:zig-args (c/conj (:zig-args (a/configuration)) "-lc")})

(a/defn increment :u64 [[x :u64]]
  (+ x 1))
