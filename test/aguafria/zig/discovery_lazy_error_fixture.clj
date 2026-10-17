(ns aguafria.zig.discovery-lazy-error-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst unused-error (compileError "invalid unused declaration"))

(a/defn increment :u32 [[value :u32]]
  (+ value 1))
