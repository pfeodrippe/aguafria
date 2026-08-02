(ns aguafria.zig-api-test
  (:require [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(deftest declaration-doc-attributes-and-inferred-types-test
  (let [namespace-symbol (gensym "aguafria.zig-api-test.scratch-")
        scratch (create-ns namespace-symbol)
        declarations (atom [])]
    (try
      (binding [*ns* scratch
                runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as az])
        (eval '(az/defconst clean-constant
                 "Inspectable constant."
                 {:export false :public false :source-comment false}
                 42))
        (eval '(az/defvar clean-variable {:public false} :u32 1))
        (eval '(az/defn clean-function
                 "Inspectable function."
                 {:export false :public true}
                 :- :u32 [[x :u32]]
                 (+ x clean-variable)))
        (eval '(az/defstruct CleanPoint
                 "Inspectable struct."
                 {:layout :normal :public false}
                 [[:x :f32] [:y {:doc "Vertical"} :f32]])))
      (let [by-name (into {} (map (juxt :name identity)) @declarations)]
        (is (= 4 (count @declarations)))
        (is (nil? (:type (get by-name 'clean-constant))))
        (is (= {:export false :public false :source-comment false}
               (:attributes (get by-name 'clean-constant))))
        (is (= :u32 (:type (get by-name 'clean-variable))))
        (is (= :normal (:layout (get by-name 'CleanPoint))))
        (is (= "Inspectable constant."
               (:doc (meta (ns-resolve scratch 'clean-constant)))))
        (is (= "Inspectable function."
               (:doc (meta (ns-resolve scratch 'clean-function)))))
        (is (= "Inspectable struct."
               (:doc (meta (ns-resolve scratch 'CleanPoint))))))
      (finally
        (remove-ns namespace-symbol)))))
