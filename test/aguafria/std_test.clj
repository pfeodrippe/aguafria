(ns aguafria.std-test
  (:require [aguafria.std]
            [aguafria.std.math :as math]
            [aguafria.std.mem :as mem]
            [aguafria.std.mem.Allocator :as allocator]
            [aguafria.zig :as az]
            [aguafria.zig.std :as std]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(az/defn std-sqrt :- :f64
  [x :- :f64]
  (math/sqrt x))

(deftest normal-require-and-docs-test
  (testing "EDN-derived std declarations are ordinary documented Vars"
    (is (nil? (io/resource "aguafria/std/math.clj")))
    (is (var? #'math/sqrt))
    (is (var? #'mem/Allocator))
    (is (var? #'allocator/alloc))
    (is (= :zig-std (:zig/documentation-source (meta #'math/sqrt))))
    (is (= "@import(\"std\").math.sqrt" (:zig/name (meta #'math/sqrt))))
    (is (= "std/math/sqrt.zig" (:zig/source (meta #'math/sqrt))))
    (is (str/includes? (:doc (meta #'math/sqrt)) "square root")))

  (testing "calling a std Var at the JVM REPL returns inspectable form data"
    (is (= '(aguafria.std.math/sqrt x) (math/sqrt 'x)))
    (is (= "@import(\"std\").math.sqrt(x)"
           (az/emit-expr (math/sqrt 'x)))))

  (testing "the generated catalog exposes the complete namespace graph"
    (is (> (:member-count (std/catalog-info)) 20000))
    (is (> (count (std/namespaces)) 1600))
    (is (some #(= "sqrt" (:name %)) (std/entries 'aguafria.std.math))))

  (testing "re-installation is REPL-safe and retains Var identity"
    (let [sqrt-var #'math/sqrt]
      (is (= {:namespace-count (count (std/namespaces))
              :var-count (:member-count (std/catalog-info))}
             (std/install-all!)))
      (is (identical? sqrt-var (ns-resolve 'aguafria.std.math 'sqrt))))))

(deftest generated-std-native-call-test
  (is (= 3.0 (std-sqrt 9.0))))

(deftest every-generated-namespace-loads-test
  (doseq [namespace-name (std/namespaces)]
    (require namespace-name)
    (is (= (count (std/entries namespace-name))
           (count (ns-publics namespace-name)))
        (str namespace-name " should expose every catalog member as a Var"))))
