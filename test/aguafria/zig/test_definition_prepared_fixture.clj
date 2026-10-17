(ns aguafria.zig.test-definition-prepared-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest first-prefix
  (panic "Preparation or loading executed the first test"))

(a/defn later :i32 [] 42)

(a/deftest final-prefix
  (panic "Preparation or loading executed the final test"))
