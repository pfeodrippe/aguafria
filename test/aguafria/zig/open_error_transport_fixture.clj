(ns aguafria.zig.open-error-transport-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [foo (k/var k/undefined [:error-union :anyerror :i32])]
    (k/= foo 1234)
    (k/= foo (a/error-value :SomeError))
    (debug/print "type: {}, value: {!}\n" [(k/TypeOf foo) foo])))
