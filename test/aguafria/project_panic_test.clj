(ns aguafria.project-panic-test
  (:require [aguafria.std]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as az]
            [clojure.test :refer [deftest is testing]]))

(az/defconst
  panic
  "Project-owned root panic policy."
  {:attrs #{:public}}
  std-debug/simple_panic)

(az/defn project-panic-value
  :-
  :i32
  []
  42)

(deftest project-defined-panic-takes-precedence-test
  (testing "the native function remains callable"
    (is (= 42 (project-panic-value))))
  (testing "Aguafria does not inject or link its shared panic support"
    (let [info (az/module-info 'aguafria.project-panic-test)]
      (is (= :project (:development-panic info)))
      (is (nil? (:development-panic-support-path info)))
      (is (not-any? #(and (string? %)
                          (.contains ^String % "development-support"))
                    (:command info))))))
