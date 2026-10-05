(ns aguafria.zig.open-error-transport-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.lang.foreign Arena ValueLayout]
           [java.nio.file Files]))

(deftest layout-reader-identity-is-not-storage-image-identity
  (with-open [arena (Arena/ofConfined)]
    (let [source (.allocate arena 8 8)
          target (.allocate arena 8 8)
          writes (atom [])
          ;; Intentionally share the exact same layout-reader object between
          ;; different producers. Its identity says nothing about error IDs.
          schema {:kind :error-union
                  :error-fn (fn [_] {:name :SomeError :code 17})
                  :set-error-fn (fn [storage error-name]
                                  (swap! writes conj error-name)
                                  (.set storage ValueLayout/JAVA_LONG 0 91))}]
      (.set source ValueLayout/JAVA_LONG 0 17)
      (value/copy-native! target schema source schema
                          {:source-image "producer-one"
                           :destination-image "producer-two"})
      (is (= [:SomeError] @writes))
      (is (= 91 (.get target ValueLayout/JAVA_LONG 0)))
      (reset! writes [])
      (value/copy-native! target schema source schema
                          {:source-image "producer-one"
                           :destination-image "producer-one"})
      (is (empty? @writes))
      (is (= 17 (.get target ValueLayout/JAVA_LONG 0)))
      (value/copy-native! target schema source schema)
      (is (= [:SomeError] @writes)
          "Without proven image identity, even one reader must remap names"))))

(deftest a-reloaded-implementation-does-not-inherit-wrapper-provenance
  (let [wrapper (.getCanonicalPath (io/file ".aguafria/provenance-unit/wrapper"))
        implementation (.getCanonicalPath (io/file ".aguafria/provenance-unit/implementation"))
        function {:native-image wrapper
                  :declaration {:logical-id [:literal] :abi-fingerprint "abi"}}
        state (atom {"literal"
                     {:dispatch-state {[[:literal] "abi"] {:implementation-generation 2}}
                      :native-generations [{:generation 1 :library-path wrapper}
                                           {:generation 2 :library-path implementation}]}})]
    (with-redefs-fn {#'runtime/registry state}
      (fn []
        (is (nil? (#'runtime/function-native-image "literal" function)))
        (swap! state assoc-in ["literal" :native-generations 1 :library-path] wrapper)
        (is (= wrapper (#'runtime/function-native-image "literal" function)))
        (is (nil? (#'runtime/function-native-image "literal" (dissoc function :native-image))))))))

(defn phase! [cache prepare?]
  (a/configure! {:cache-dir cache})
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.open-error-transport-fixture))
  (let [commands (atom [])
        original shell/sh
        output (java.io.StringWriter.)
        images (atom nil)]
    (with-redefs [shell/sh (fn [& arguments]
                             (when (= "build-lib" (second arguments))
                               (swap! commands conj (vec arguments)))
                             (apply original arguments))]
      (if prepare?
        (let [report (precompile/precompile!
                      {:analyze ['aguafria.zig.open-error-transport-fixture]
                       :report-file (str cache "/report.edn")})]
          (assert (zero? (get-in report [:coverage :operations :not-fully-prepared]))
                  (pr-str (:coverage report)))
          (assert (= 1 (count (get-in report [:bundles :packs])))
                  "Open errors require one native image, not guessed cross-image codes"))
        (binding [*out* output *err* output]
          (with-open [foo (k/var k/undefined [:error-union :anyerror :i32])]
            (k/= foo 1234)
            (assert (= {:ok 1234} (a/value foo)))
            (k/= foo (a/error-value :SomeError))
            (assert (= :SomeError (get-in (a/value foo) [:error :name])))
            (with-open [source (k/as (a/error-value :SomeError)
                                     [:error-union :anyerror :i32])]
              (reset! images [(get (value/realize! foo) :native-image)
                              (get (value/realize! source) :native-image)])
              (k/= foo source))
            (assert (= :SomeError (get-in (a/value foo) [:error :name])))
            (debug/print "type: {}, value: {!}\n" [(k/TypeOf foo) foo])))))
    {:builds (count @commands)
     :commands @commands
     :images @images
     :output (str output)}))

(defn standalone-phase! [cache]
  (a/configure! {:cache-dir cache})
  (with-open [foo (k/var k/undefined [:error-union :anyerror :i32])
              source (k/as (a/error-value :SomeError) [:error-union :anyerror :i32])]
    (k/= foo 1234)
    (let [error (try
                  (value/set-value! foo source)
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
      {:images [(:native-image (value/realize! foo))
                (:native-image (value/realize! source))]
       :rejection (ex-data error)
       :preserved (a/value foo)})))

(defn- child! [cache prepare?]
  (let [code `(do
                (require 'aguafria.zig.open-error-transport-test)
                (prn ~(if (= :standalone prepare?)
                        `(standalone-phase! ~cache)
                        `(phase! ~cache ~prepare?)))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Open-error preparation child failed" result)))
    (edn/read-string (:out result))))

(deftest one-bundle-open-error-assignment-survives-a-jvm-restart
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "open-error-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (child! cache true)
        restarted (child! cache false)]
    (is (pos? (:builds prepared)))
    (is (zero? (:builds restarted)) (pr-str (:commands restarted)))
    (is (every? some? (:images restarted)))
    (is (apply = (:images restarted)))
    (is (str/includes? (:output restarted) "value: error.SomeError"))))

(deftest standalone-open-error-images-do-not-copy-foreign-error-codes
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "standalone-open-error-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        {:keys [images rejection preserved]} (child! cache :standalone)]
    (is (every? some? images))
    (is (not (apply = images)))
    (is (= :open-error-set-needs-local-image (:reason rejection)))
    (is (= {:ok 1234} preserved))))
