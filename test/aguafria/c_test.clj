(ns aguafria.c-test
  (:require [aguafria.c :as ac]
            [aguafria.keyword :as k]
            [aguafria.zig :as az]
            [aguafria.zig.convert :as convert]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest binding-macro-expands-to-public-declarations
  (let [form (with-meta '(ac/defbindings api (throw (Exception. "not during expansion"))
                           [native_point point_sum])
               {:line 12 :column 3})
        expansion (binding [*ns* (the-ns 'aguafria.c-test)] (macroexpand-1 form))
        declarations (rest expansion)]
    (is (= 'do (first expansion)))
    (is (= '[api native_point point_sum] (mapv second declarations)))
    (doseq [declaration declarations]
      (is (= 'aguafria.zig/defconst (first declaration)))
      (is (= '{:attrs #{aguafria.keyword/pub}} (nth declaration 2)))
      (is (= {:line 12 :column 3} (meta declaration))))
    (is (= '(aguafria.keyword/import
             (aguafria.zig/clj! (throw (Exception. "not during expansion"))))
           (last (first declarations))))
    (is (= '(:point_sum api) (last (last declarations))))))

(deftest binding-macro-validates-declaration-names
  (doseq [[name members] [['api '[a a]] ['api '[api]] ['other/api '[a]]
                          ['api '[other/a]] ['api [:a]] ['api '(a)]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"distinct member names"
                          (apply @#'ac/defbindings [nil nil name "fixture" members])))))

(deftest authored-c-bindings-run-through-ordinary-require
  (let [configuration (runtime/configuration)
        translate ac/translate-zig!
        calls (atom 0)]
    (try
      (with-redefs [ac/translate-zig! (fn [& arguments]
                                        (swap! calls inc)
                                        (apply translate arguments))]
        (require 'aguafria.c-bindings-fixture :reload)
        (is (= 1 @calls))
        (require 'aguafria.c-bindings-fixture)
        (is (= 1 @calls)))
      (require 'aguafria.c-bindings-consumer-fixture :reload)
      (doseq [function '[sum sum-through-api]]
        (with-open [result ((ns-resolve 'aguafria.c-bindings-consumer-fixture function) 12 30)]
          (is (= 42 (az/value result)))))
      (let [point (az/init {:x 10 :y 20}
                           (var-get (ns-resolve 'aguafria.c-bindings-fixture 'native_point)))]
        (with-open [point point
                    result ((ns-resolve 'aguafria.c-bindings-fixture 'point_sum) point)]
          (is (= 30 (az/value result)))))
      (doseq [name '[api native_point point_sum]
              :let [var (ns-resolve 'aguafria.c-bindings-fixture name)
                    declaration (:aguafria/declaration (meta var))]]
        (is (= var (get (ns-publics 'aguafria.c-bindings-fixture) name)))
        (is (= :const (:kind declaration)))
        (is (str/ends-with? (:file (meta var)) "aguafria/c_bindings_fixture.clj")))
      (finally (runtime/configure! configuration)))))

(deftest named-c-imports-preserve-configuration-and-content-identity
  (let [configuration (atom {:modules {"existing" "existing.zig"}
                             :module-cache-tokens {"existing" "first"}})
        calls (atom [])]
    (with-redefs [runtime/configuration #(deref configuration)
                  runtime/configure! #(swap! configuration merge %)
                  ac/translate-zig! (fn [header options]
                                      (swap! calls conj [header options])
                                      {:translated-zig-path "translated.zig"
                                       :cache-key "second"})]
      (is (= "c_fixture" (ac/import! "c_fixture" "fixture.h" {:args ["-lc"]})))
      (is (= [["fixture.h" {:args ["-lc"]}]] @calls))
      (is (= {"existing" "existing.zig" "c_fixture" "translated.zig"}
             (:modules @configuration)))
      (is (= {"existing" "first" "c_fixture" "second"}
             (:module-cache-tokens @configuration)))
      (doseq [name ["std" "root" "builtin" "../file.zig" "" nil]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"module name"
                              (ac/import! name "fixture.h" {}))))
      (is (= 1 (count @calls))))))

(deftest named-c-import-runs-from-a-native-dependency
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "aguafria-c-module-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        header (io/file directory "fixture.h")
        configuration (runtime/configuration)]
    (spit header "static inline int increment(int value) { return value + 1; }\n")
    (try
      (ac/import! "aguafria_c_module_fixture" header
                  {:cache-dir (str (io/file directory "translation"))})
      (binding [*ns* (the-ns 'aguafria.c-test)]
        (eval '(az/defconst native-api (k/import "aguafria_c_module_fixture")))
        (eval '(az/defn increment :c_int [[value :c_int]]
                 ((:increment native-api) value))))
      (with-open [result ((ns-resolve 'aguafria.c-test 'increment) 41)]
        (is (= 42 (az/value result))))
      (finally (runtime/configure! configuration)))))

(deftest translate-header-and-inspect-bindings-test
  (let [directory (.toFile
                   (java.nio.file.Files/createTempDirectory
                    "aguafria-c-binding-test"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        output (io/file directory "generated" "fixture.clj")
        options {:namespace 'aguafria.generated.c-fixture
                 :cache-dir (str (io/file directory "cache"))
                 :overwrite? true}
        first-report (ac/translate-header! "test/fixtures/c_binding_fixture.h"
                                           output options)
        second-report (ac/translate-header! "test/fixtures/c_binding_fixture.h"
                                            output options)]
    (testing "Zig translate-c feeds ordinary structural Aguafria generation"
      (is (.isFile output))
      (is (false? (:cache-hit? first-report)))
      (is (true? (:cache-hit? second-report)))
      (is (false? (:conversion-cache-hit? first-report)))
      (is (true? (:conversion-cache-hit? second-report)))
      (is (false? (:written? second-report)))
      (is (zero? (:fallback-count first-report)))
      (is (not (str/includes? (slurp output) "az/defraw"))))

    (testing "generated C declarations are ordinary documented Vars"
      (ac/load-bindings! output)
      (let [{:keys [bindings]} (ac/namespace-info 'aguafria.generated.c-fixture)
            add (some #(when (= 'agua_add (:name %)) %) bindings)
            point (some #(when (= 'agua_point_t (:name %)) %) bindings)
            counter (some #(when (= 'agua_external_counter (:name %)) %)
                          bindings)]
        (is (= :fn-proto (:kind add)))
        (is (= :c_int (:return add)))
        (is (= :const (:kind point)))
        (is (= :extern-var (:kind counter)))
        (is (str/includes? (:doc add) "Add two signed integers"))))))

(deftest rendered-c-bindings-follow-the-converter-identity
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "aguafria-c-renderer-version-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        output (io/file directory "fixture.clj")
        options {:namespace 'aguafria.generated.cache-fixture
                 :cache-dir (str (io/file directory "cache")) :overwrite? true}
        generate #(ac/translate-header! "test/fixtures/c_binding_fixture.h" output options)]
    (with-redefs [convert/conversion-cache-identity (constantly "renderer-before")]
      (generate)
      (is (:conversion-cache-hit? (generate))))
    (with-redefs [convert/conversion-cache-identity (constantly "renderer-after")]
      (let [regenerated (generate)]
        (is (:cache-hit? regenerated) "Unchanged C translation remains reusable.")
        (is (false? (:conversion-cache-hit? regenerated))
            "Changed Aguafria rendering must not reuse obsolete forms.")
        (is (:conversion-cache-hit? (generate)))))))
