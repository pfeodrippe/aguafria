(ns aguafria.zig.native-test-owner-cache-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- fresh-jvm [cache producer?]
  (let [fixture 'aguafria.zig.native-test-owner-cache-fixture
        code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.precompile
                    'aguafria.zig.explain)
           (aguafria.zig/configure! {:cache-dir ~cache})
           (if ~producer?
             (let [fail!# (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                   report# (with-redefs [aguafria.zig.runtime/invoke! fail!#
                                         aguafria.zig.runtime/invoke-with-result! fail!#
                                         aguafria.zig.runtime/run-test! fail!#]
                             (aguafria.zig.precompile/precompile!
                              {:analyze ['~fixture] :parallelism 1
                               :report-file ~(str cache "/report.edn")}))]
               (prn (select-keys report# [:coverage :bundles :namespace-images])))
             (let [events# (atom []) phase# (atom :require)
                   result#
                   (binding [aguafria.zig.explain/*reporter*
                             #(swap! events# conj (assoc % :phase @phase#))]
                     (require '~fixture)
                     (let [increment# (ns-resolve '~fixture '~'increment)
                           scalar# (aguafria.zig/value (increment# :u32 41))
                           adapters# (filter :jvm-adapter?
                                             (aguafria.zig.runtime/registered-declarations
                                              ~(str fixture)))
                           _# (reset! phase# :native-owner)
                           test# ((ns-resolve '~fixture '~'increment-test))]
                       {:scalar scalar# :adapter-count (count adapters#)
                        :test test#}))]
               (prn (assoc result# :events @events#))))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp"
                         (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Fresh native test owner JVM failed" result)))
    (edn/read-string (last (str/split-lines (:out result))))))

(deftest final-test-snapshot-reuses-preparation-after-jvm-adapter-registration
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "native-test-owner-" (make-array java.nio.file.attribute.FileAttribute 0)))
        producer (fresh-jvm cache true)
        _ (spit (io/file cache "producer.edn") (pr-str producer))
        consumer (fresh-jvm cache false)
        _ (spit (io/file cache "consumer.edn") (pr-str consumer))
        image (first (filter #(= 'aguafria.zig.native-test-owner-cache-fixture
                                 (:namespace %)) (:namespace-images producer)))
        definition (first (:test-checks image))
        owner (first (:test-owners image))
        source (slurp (get-in owner [:artifact :source-path]))
        events (:events consumer)
        hits (filter #(= :bundle-cache-hit (:event %)) events)]
    (is (= {:prepared 1} (get-in producer [:coverage :test-definition-checks])))
    (is (= {:prepared 1} (get-in producer [:coverage :native-test-owner-checks])))
    (is (not= (:artifact definition) (:artifact owner)))
    (is (str/includes? source "fn later"))
    (is (not (str/includes? source "__jvm_")))
    (is (= 42 (:scalar consumer)))
    (is (pos? (:adapter-count consumer)))
    (is (= :passed (get-in consumer [:test :status])))
    (is (some #(and (= :native-owner (:phase %)) (= :disk-cache-hit (:event %))
                    (= (get-in owner [:artifact :library-path]) (:path %))) events)
        (pr-str events))
    (is (empty? (filter #(#{:compiled :compile-failed} (:event %)) events)) (pr-str events))
    (is (seq hits))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))))
    (is (every? #(= (get-in producer [:bundles :packs 0 :id]) (:bundle-id %)) hits))
    (is (empty? (filter #(and (= :disk-cache-hit (:event %)) (nil? (:bundle-id %))
                              (str/starts-with? (str (:module %)) "aguafria.jvm.")) events))
        (pr-str events))))
