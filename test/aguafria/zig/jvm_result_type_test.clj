(ns aguafria.zig.jvm-result-type-test
  (:require [aguafria.zig.convert :as convert]
            [aguafria.zig.emitter :as emit]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(defn- load-fixture! [directory]
  (let [output (.toFile
                (java.nio.file.Files/createTempDirectory
                 "aguafria-result-types"
                 (make-array java.nio.file.attribute.FileAttribute 0)))
        prefix (symbol (str "fixture.result-types-" (gensym)))]
    (convert/convert-tree! directory output {:namespace-prefix prefix})
    (convert/load-tree! output)
    (symbol (str prefix ".main"))))

(deftest inspection-preserves-cyclic-root-module-identity
  (let [module (load-fixture! "test/fixtures/inspection_cycle")
        result (runtime/inspect-module!
                module
                (fn [declarations]
                  {:source (str (emit/emit-module (str module) declarations)
                                "\ntest { _ = total(); }\n")}))]
    (is (zero? (:exit result)) (:err result))
    (is (= 7 (value/decoded ((ns-resolve module 'total)))))))

(deftest native-root-slices-close-transitive-back-references
  (let [module (load-fixture! "test/fixtures/root_context_closure")
        box (ns-resolve module 'box)]
    (is (= :prepared (:status (runtime/precompile-function! box))))
    (is (= {:value 9} (value/decoded (box))))))

(deftest cold-converted-adapters-retain-their-cleanup-functions
  (let [module (load-fixture! "test/fixtures/root_context_closure")
        context (the-ns module)
        expression (symbol (str module) "setting")
        adapter (binding [runtime/*compile-only?* true]
                  (#'jvm/prepare-expression! context expression [] 'result))
        registry (var-get #'runtime/registry)
        events (atom [])]
    (is (nil? (get-in @registry [(str module) :source])))
    (binding [runtime/*compile-only?* true]
      (#'jvm/precompile-expression! adapter))
    (doseq [function ((juxt :function :release :release-native) adapter)]
      (is (some #(= function (:qualified-name %))
                (vals (get-in @registry [(str module) :definitions])))))
    (binding [explain/*reporter* #(swap! events conj %)]
      (is (= 9 (value/decoded
                (#'jvm/invoke-expression! context expression [] [] 'result)))))
    (is (not-any? #(= :compiled (:event %)) @events) (pr-str @events))))

(deftest native-results-use-the-functions-own-return-types
  (let [module (load-fixture! "test/fixtures/jvm_result_types")
        events (atom [])
        call (fn [name & args]
               (binding [explain/*reporter* #(swap! events conj %)]
                 (value/decoded (apply (ns-resolve module name) args))))]
    (with-redefs [runtime/invoke! (fn [& _]
                                   (throw (ex-info "Invocation during preparation" {})))]
      (doseq [name '[anonymous make_array optional make_slice fallible]]
        (is (= :prepared (:status (runtime/precompile-function! (ns-resolve module name)))))))
    (is (= {:left 3 :right 5} (call 'anonymous)))
    (is (= [3 5] (call 'make_array)))
    (is (= 7 (call 'optional true)))
    (is (nil? (call 'optional false)))
    (is (= [104 101 108 108 111] (call 'make_slice)))
    (is (= {:ok 11} (call 'fallible false)))
    (is (= :Failed (get-in (call 'fallible true) [:error :name])))
    (is (not-any? #(= :compiled (:event %)) @events) (pr-str @events))))
