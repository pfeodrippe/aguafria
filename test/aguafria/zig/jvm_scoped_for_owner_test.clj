(ns aguafria.zig.jvm-scoped-for-owner-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- lesson-jvm [cache producer?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain 'clojure.java.io 'clojure.java.shell)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (let [modules# ['learn.example.test-inline-for
                           'learn.example.test-for-nested-break]]
             (if ~producer?
               (let [fail!# (fn [& _#] (throw (ex-info "Producer invoked native code" {})))
                     report#
                     (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                   aguafria.zig.runtime/invoke-with-result! fail!#]
                       (aguafria.zig.precompile/precompile!
                        {:analyze modules# :parallelism 1
                         :report-file ~(str cache "/report.edn")}))]
                 (prn {:coverage (:coverage report#) :bundles (:bundles report#)
                       :owners
                       (vec (for [entry# (:analysis report#)
                                  operation# (:operations entry#)
                                  :when (#{'aguafria.zig/inline-for 'aguafria.zig/for-loop}
                                         (:function operation#))]
                              (assoc (select-keys operation# [:id :function :handlers])
                                     :module (:namespace entry#))))}))
               (let [events# (atom []) commands# (atom []) original-sh# clojure.java.shell/sh
                     results#
                     (with-redefs [clojure.java.shell/sh
                                   (fn [& arguments#]
                                     (swap! commands# conj (vec arguments#))
                                     (apply original-sh# arguments#))]
                       (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                         (mapv
                          (fn [module#]
                            (require module#)
                            (let [resource# (str (clojure.string/replace
                                                  (clojure.string/replace (str module#) "." "/")
                                                  "-" "_") ".clj")
                                  forms#
                                  (with-open [reader# (java.io.PushbackReader.
                                                       (clojure.java.io/reader
                                                        (clojure.java.io/resource resource#)))]
                                    (binding [*read-eval* false]
                                      (into [] (take-while some?)
                                            (repeatedly #(read {:eof nil} reader#)))))
                                  tests# (filter #(and (seq? %) (= 'a/deftest (first %))) forms#)]
                              {:module module#
                               :results
                               (binding [*ns* (the-ns module#)]
                                 (mapv (fn [form#]
                                         {:name (second form#)
                                          :value (aguafria.zig/value
                                                  (eval (cons 'do (drop 2 form#))))})
                                       tests#))}))
                          modules#)))]
                 (prn {:results results# :events @events# :commands @commands#}))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (spit (io/file cache (if producer? "producer-process.edn" "consumer-process.edn"))
          (pr-str result))
    (when-not (zero? (:exit result))
      (throw (ex-info "Native for-owner fixture JVM failed" {:cache cache :result result})))
    (edn/read-string (:out result))))

(defn- changed-array-jvm [cache]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.explain
                    'clojure.java.io 'clojure.java.shell)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache})
           (require 'learn.example.test-inline-for)
           (let [forms# (with-open [reader# (java.io.PushbackReader.
                                             (clojure.java.io/reader
                                              (clojure.java.io/resource
                                               "learn/example/test_inline_for.clj")))]
                          (binding [*read-eval* false]
                            (into [] (take-while some?)
                                  (repeatedly #(read {:eof nil} reader#)))))
                 test# (first (filter #(and (seq? %) (= (symbol "a/deftest") (first %))) forms#))
                 binding# (nth test# 2)
                 scope# (nth binding# 2)
                 nums# (symbol "nums")
                 sum# (symbol "sum")
                 error# (symbol "error")
                 body#
                 (list (symbol "let") (second binding#)
                       (list (symbol ".set")
                             (list 'aguafria.zig/native-segment nums#)
                             'java.lang.foreign.ValueLayout/JAVA_INT
                             (list 'clojure.core/long 0) (list 'clojure.core/int 4))
                       (list (symbol "try") scope#
                             (list (symbol "catch") 'clojure.lang.ExceptionInfo error#
                                   (list 'clojure.core/hash-map
                                         :reason (list :reason (list 'clojure.core/ex-data error#))
                                         :sum (list 'aguafria.zig/value sum#)))))
                 events# (atom []) commands# (atom []) original-sh# clojure.java.shell/sh
                 result#
                 (with-redefs [clojure.java.shell/sh
                               (fn [& arguments#]
                                 (swap! commands# conj (vec arguments#))
                                 (apply original-sh# arguments#))]
                   (binding [*ns* (the-ns 'learn.example.test-inline-for)
                             aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                     (eval body#)))]
             (prn {:result result# :events @events# :commands @commands#}))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (spit (io/file cache "changed-array-process.edn") (pr-str result))
    (when-not (zero? (:exit result))
      (throw (ex-info "Changed inline array fixture JVM failed" {:cache cache :result result})))
    (edn/read-string (:out result))))

(deftest original-inline-and-labeled-for-bodies-reuse-the-prepared-pack
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "scoped-for-owners-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (lesson-jvm cache true)
        _ (spit (io/file cache "producer.edn") (pr-str producer))
        consumer (lesson-jvm cache false)
        _ (spit (io/file cache "consumer.edn") (pr-str consumer))
        changed (changed-array-jvm cache)
        _ (spit (io/file cache "changed-array.edn") (pr-str changed))
        events (concat (:events consumer) (:events changed))
        hits (filter #(= :bundle-cache-hit (:event %)) events)
        misses (filter #(or (#{:compiled :compile-failed} (:event %))
                            (and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %))
                                 (str/starts-with? (:module %) "aguafria.jvm."))) events)
        bodies (mapcat :results (:results consumer))]
    (is (= 3 (count (:owners producer))))
    (is (every? #(and (seq (:handlers %))
                      (every? (fn [handler] (= :prepared (:status handler))) (:handlers %)))
                (:owners producer)) (pr-str (:owners producer)))
    (is (= '[inline-for-loop nested-break nested-continue] (mapv :name bodies)))
    (is (every? #(= {:ok nil} (:value %)) bodies) (pr-str bodies))
    (is (= {:reason :scoped-capture-value-changed :sum 0} (:result changed)))
    (is (empty? misses) (pr-str misses))
    (is (seq hits))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) (:events consumer)))))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) (:events changed)))))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (every? #(and (#{2 4} (count %)) (= "version" (second %)))
                (concat (:commands consumer) (:commands changed)))
        (pr-str [(:commands consumer) (:commands changed)]))
    (is (<= (count (:commands consumer)) 1))
    (is (<= (count (:commands changed)) 1))))
