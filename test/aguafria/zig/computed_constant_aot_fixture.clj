(ns aguafria.zig.computed-constant-aot-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

;; Not an authored string literal at the initializer root. Its exact pointer
;; type and sentinel must come from compiler metadata, not source guessing.
(a/defconst message
  (switch (u8 1)
    1 "computed string"
    "other string length"))
