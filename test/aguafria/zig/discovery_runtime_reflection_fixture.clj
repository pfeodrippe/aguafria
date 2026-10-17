(ns aguafria.zig.discovery-runtime-reflection-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Optional :as optional-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn error-payload :bool [[input [:error-union :anyerror :i32]]]
  (let [info (:error_union (typeInfo (TypeOf input)))]
    (and (== (:payload info) :i32)
         (== (:error_set info) :anyerror))))

(a/defn optional-child :bool [[input [:optional :i32]]]
  (== (:child (:optional (typeInfo (TypeOf input)))) :i32))

(a/deftest error-union-reflection
  (let [input (k/var undefined [:error-union :anyerror :i32])]
    (= input 23)
    (try (testing/expectEqual :i32
                              (:payload (:error_union (typeInfo (TypeOf input))))))))

(a/deftest optional-reflection
  (let [input (k/var nil [:optional :i32])]
    (= input 29)
    (try (testing/expectEqual :i32
                              (-> (typeInfo (TypeOf input))
                                  type-info/-optional
                                  optional-info/-child)))))
