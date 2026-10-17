(ns learn.example.test-comptime-invalid-error-code
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-invalid-error-code
  (= :_ (errorFromInt 12345)))
