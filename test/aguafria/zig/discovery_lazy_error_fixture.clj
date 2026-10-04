(ns aguafria.zig.discovery-lazy-error-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst unused-error (k/compileError "invalid unused declaration"))

(a/defn increment :u32 [[value :u32]]
  (k/+ value 1))
