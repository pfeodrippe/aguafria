(ns aguafria.zig.named-error-alias-test
  (:require [aguafria.zig.compiler-work :as compiler-work]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(def module "aguafria.zig.named-error-alias-fixture")

(defn- load-declarations! []
  (binding [runtime/*compile-only?* true
            runtime/*source-only-registration?* true
            compiler-work/*compiler-command-policy*
            (fn [command] (throw (ex-info "Alias inspection must not compile" {:command command})))]
    (require 'aguafria.zig.named-error-alias-fixture)))

(defn- argument-type [type]
  (get-in (runtime/jvm-callable-argument-declaration
           {:module module :args [{:name 'err :type type}]}) [:args 0 :type]))

(deftest canonical-declaration-type-aliases-share-argument-and-result-transport
  (load-declarations!)
  (doseq [type ['Small 'SmallChain (symbol module "Small")]]
    (is (= [:error-set [:OutOfMemory]] (argument-type type))))
  (is (= [:error-set [:AccessDenied :FileNotFound :OutOfMemory]]
         (runtime/jvm-callable-result-type {:module module :return 'Large})))
  (doseq [[alias expected] [['Count :usize] ['Maybe [:optional :u32]]
                           ['Bytes [:slice-const :u8]] ['Items [:array 4 :i32]]]]
    (is (= expected (argument-type alias))))
  ;; Expanding a type alias must not turn a nominal struct into an error schema.
  (is (symbol? (argument-type 'NominalAlias))))

(deftest legacy-canonical-foreign-and-cyclic-alias-heads-remain-distinct
  (load-declarations!)
  (let [small (runtime/declaration-info
               {:module module :kind :const :name 'Small
                :value '(aguafria.zig/type [:error-set [:OutOfMemory]])})
        large (runtime/declaration-info
               {:module module :kind :const :name 'Large :value 'Small})
        expand (fn [declarations]
                 (with-bindings {#'runtime/*materialization-type-declarations* declarations}
                   (argument-type 'Small)))]
    (doseq [head ['type 'aguafria.zig/type]]
      (is (= [:error-set [:OutOfMemory]]
             (expand {[module 'Small] (assoc small :value (list head [:error-set [:OutOfMemory]]))}))))
    (is (= 'Small (expand {[module 'Small]
                          (assoc small :value '(clojure.core/type [:error-set [:OutOfMemory]]))})))
    (is (symbol? (expand {[module 'Small] (assoc small :value 'Large)
                          [module 'Large] large})))))
