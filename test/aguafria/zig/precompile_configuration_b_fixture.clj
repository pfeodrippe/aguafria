(ns aguafria.zig.precompile-configuration-b-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig.precompile-configuration-a-fixture]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(a/configure! {:zig-args (c/conj (:zig-args (a/configuration)) "-lc")})

(a/defn increment :i32 [[x :i32]]
  (+ x 1))
