(ns aguafria.zig.discovery-runtime-reflection-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Optional :as optional-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn error-payload :bool [[input [:error-union :anyerror :i32]]]
  (let [info (:error_union (k/typeInfo (k/TypeOf input)))]
    (and (k/== (:payload info) :i32)
         (k/== (:error_set info) :anyerror))))

(a/defn optional-child :bool [[input [:optional :i32]]]
  (k/== (:child (:optional (k/typeInfo (k/TypeOf input)))) :i32))

(a/deftest error-union-reflection
  (let [input (k/var k/undefined [:error-union :anyerror :i32])]
    (k/= input 23)
    (try (testing/expectEqual :i32
                              (:payload (:error_union (k/typeInfo (k/TypeOf input))))))))

(a/deftest optional-reflection
  (let [input (k/var nil [:optional :i32])]
    (k/= input 29)
    (try (testing/expectEqual :i32
                              (-> (k/typeInfo (k/TypeOf input))
                                  type-info/-optional
                                  optional-info/-child)))))
