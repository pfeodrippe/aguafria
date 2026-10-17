(ns aguafria.zig.open-error-transport-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [foo (k/var undefined [:error-union :anyerror :i32])]
    (= foo 1234)
    (= foo (a/error-value :SomeError))
    (debug/print "type: {}, value: {!}\n" [(TypeOf foo) foo])))
