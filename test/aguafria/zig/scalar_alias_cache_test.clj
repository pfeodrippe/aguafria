(ns aguafria.zig.scalar-alias-cache-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- fresh-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'aguafria.zig.value
                    'clojure.java.io 'clojure.java.shell)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:analyze ['aguafria.zig.scalar-constant-cache-fixture]
                               :parallelism 1 :report-file ~(str cache "/report.edn")}))]
               (prn (select-keys report# [:coverage :bundles])))
             (let [events# (atom []) commands# (atom []) original-shell# clojure.java.shell/sh
                   output#
                   (with-redefs [clojure.java.shell/sh
                                 (fn [& args#]
                                   (swap! commands# conj (vec args#))
                                   (apply original-shell# args#))]
                     (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                       (require 'aguafria.zig.scalar-constant-cache-fixture)
                       (let [context# (the-ns 'aguafria.zig.scalar-constant-cache-fixture)
                             forms# (with-open [reader# (java.io.PushbackReader.
                                                         (clojure.java.io/reader
                                                          (clojure.java.io/resource
                                                           "aguafria/zig/scalar_constant_cache_fixture.clj")))]
                                      (binding [*read-eval* false]
                                        (into [] (take-while some?)
                                              (repeatedly #(read {:eof nil} reader#)))))
                             round# (fn []
                                      (binding [*ns* context#]
                                        (mapv (fn [form#]
                                                {:name (second form#)
                                                 :result (aguafria.zig/value
                                                          (eval (cons (symbol "do")
                                                                      (drop 2 form#))))})
                                              (filter #(and (seq? %)
                                                            (= (symbol "a/deftest") (first %)))
                                                      forms#))))
                             cold# (round#)
                             cold-commands# @commands#
                             _# (reset! commands# [])]
                         {:results [cold# (round#)]
                          :bootstrap-commands cold-commands# :warm-commands @commands#})))]
               (prn (assoc output# :events @events#))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Fresh scalar JVM failed" result)))
    (edn/read-string (:out result))))

(deftest compiler-reported-constant-types-reuse-aot-in-a-fresh-jvm
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "scalar-types-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (fresh-jvm cache true)
        _ (spit (io/file cache "producer.edn") (pr-str producer))
        consumer (fresh-jvm cache false)
        _ (spit (io/file cache "consumer.edn") (pr-str consumer))
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %))
                                 (str/starts-with? (str (:module %)) "aguafria.jvm."))) events)
        bootstrap? (fn [command]
                     (or (and (= 4 (count command)) (= "version" (second command))
                              (= :dir (nth command 2)))
                         (and (= 3 (count command))
                              (= ["build-lib" "--show-builtin"] (subvec command 1)))))]
    (is (= 0 (get-in producer [:coverage :namespaces :baseline-failures])))
    (is (= {:prepared 7} (get-in producer [:coverage :constant-readers :statuses])))
    (is (= (repeat 2 '[inferred-integer-check inferred-boolean-check inferred-float-check
                       wide-float-check infinity-check])
           (map #(mapv :name %) (:results consumer))))
    (is (every? #(= {:ok nil} (:result %)) (apply concat (:results consumer)))
        (pr-str (:results consumer)))
    (is (empty? misses) (pr-str misses))
    (is (seq hits))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (every? bootstrap? (:bootstrap-commands consumer))
        (pr-str (:bootstrap-commands consumer)))
    (is (empty? (:warm-commands consumer)) (pr-str (:warm-commands consumer)))))
