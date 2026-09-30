(ns aguafria.zig.precompile-test
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.testing :as zig-testing]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(deftest invalid-options-do-not-start-precompilation
  (doseq [options [nil {} {:namespace ['somewhere]}
                   {:namespaces 'somewhere} {:namespaces ['somewhere/function]}
                   {:warmup ['somewhere/run!]}
                   {:calls [{:function 'unqualified :args []}]}]]
    (is (thrown? clojure.lang.ExceptionInfo (az/precompile! options)))))

(deftest namespace-precompilation-does-not-run-bodies
  (let [fail! (fn [& _] (throw (ex-info "Precompilation invoked a native body" {})))
        report (with-redefs [runtime/invoke! fail!
                             runtime/invoke-with-result! fail!]
                 (az/precompile! {:namespaces ['aguafria.zig.precompile-fixture]}))
        statuses (into {} (map (juxt :function :status)) (:functions report))]
    (is (= :prepared (statuses 'aguafria.zig.precompile-fixture/do-not-call)))
    (is (= :prepared (statuses 'aguafria.zig.precompile-fixture/increment)))
    (is (= :prepared (statuses 'aguafria.zig.precompile-fixture/subtract)))
    (is (= :skipped (statuses 'aguafria.zig.precompile-fixture/generic-identity)))
    (is (= :specialization
           (:reason (first (filter #(= :skipped (:status %)) (:functions report))))))
    (is (empty? (:calls report)))))

(deftest explicit-signatures-compile-without-invocation
  (let [fail! (fn [& _] (throw (ex-info "Precompilation invoked a native body" {})))
        report (with-redefs [runtime/invoke! fail!
                             runtime/invoke-with-result! fail!]
                 (az/precompile!
                  {:calls '[{:function aguafria.std.debug/assert :args [:bool]}
                            {:function aguafria.std.testing/expectEqual :args [:i32 :i32]}
                            {:function aguafria.zig.precompile-fixture/generic-identity
                             :args [{:comptime :i32} :i32]}]
                   :coercions [:i32]}))
        argument (k/i32 42)
        commands (atom [])
        original shell/sh]
    (is (= 3 (count (:calls report))))
    (with-redefs [shell/sh (fn [& args]
                             (swap! commands conj (vec (take 2 args)))
                             (apply original args))]
      (debug/assert true)
      (zig-testing/expectEqual argument argument)
      (is (= 42 (az/value ((resolve 'aguafria.zig.precompile-fixture/generic-identity)
                           :i32 argument)))))
    (is (empty? @commands) (str @commands))))

(deftest noreturn-preparation-does-not-execute-or-load
  (let [loaded (fn []
                 (into #{}
                       (keep (fn [[module state]] (when (seq (:functions state)) module)))
                       @(var-get (ns-resolve 'aguafria.zig.runtime 'registry))))
        before (loaded)
        fail! (fn [& _] (throw (ex-info "Precompilation invoked a native body" {})))
        report (with-redefs [runtime/invoke! fail!
                             runtime/invoke-with-result! fail!]
                 (az/precompile! {:namespaces ['aguafria.zig.precompile-noreturn-fixture]}))]
    (is (= 3 (count (:functions report))))
    (is (every? #(= :prepared (:status %)) (:functions report)))
    (is (= before (loaded)))
    (is (str/includes? (az/source 'aguafria.zig.precompile-noreturn-fixture)
                       "fn never_run() noreturn"))))

(deftest comptime-result-preparation-does-not-execute-or-load
  (let [loaded (fn []
                 (into #{}
                       (keep (fn [[module state]] (when (seq (:functions state)) module)))
                       @(var-get (ns-resolve 'aguafria.zig.runtime 'registry))))
        before (loaded)
        fail! (fn [& _] (throw (ex-info "Preparation executed a native body" {})))
        types [:u8 [:array 4 :u16] [:error-union :anyerror :i32]]
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (az/precompile!
                  {:calls (mapv (fn [type]
                                  {:function 'aguafria.keyword/typeInfo
                                   :args [{:comptime-type type}]})
                                types)}))
        commands (atom [])
        sh shell/sh]
    (is (= 3 (count (:calls report))))
    (is (every? #(= :prepared (:status %)) (:calls report)))
    (is (= before (loaded)))
    (with-redefs [shell/sh (fn [& args]
                             (swap! commands conj (vec (take 2 args)))
                             (apply sh args))]
      (is (= [{:int {:signedness :unsigned :bits 8}}
              {:array {:len 4 :child {:type "u16"} :sentinel_ptr nil}}
              {:error_union {:error_set {:type "anyerror"} :payload {:type "i32"}}}]
             (mapv #(az/value (k/typeInfo %)) types))))
    (is (empty? @commands) (str @commands))))

(deftest unsupported-and-invalid-signatures-fail-explicitly
  (doseq [call '[{:function aguafria.keyword/var :args [:i32]}
                 {:function aguafria.keyword/& :args [:i32]}
                 {:function aguafria.keyword/+ :args [{:comptime 1} {:comptime 2}]}
                 {:function aguafria.keyword/+ :args [:i32]}
                 {:function aguafria.keyword/+ :args [:not-a-type :i32]}]]
    (is (thrown? Exception (az/precompile! {:calls [call]})))))

(defn- fresh-jvm [cache-dir prepare?]
  (let [code
        (pr-str
         `(do
            (require '~'[aguafria.zig :as az]
                     '~'[aguafria.keyword :as k]
                     '~'[aguafria.zig.runtime :as runtime]
                     '~'[clojure.java.shell :as shell])
            (aguafria.zig/configure! {:cache-dir ~cache-dir})
            (let [commands# (atom [])
                  sh# clojure.java.shell/sh
                  report# (with-redefs [clojure.java.shell/sh
                                        (fn [& args#]
                                          (when (= "build-lib" (second args#))
                                            (swap! commands# conj (vec (take 4 args#))))
                                          (apply sh# args#))]
                            (if ~prepare?
                              (with-redefs [aguafria.zig.runtime/invoke!
                                            (fn [& _#] (throw (ex-info "Called native body" {})))
                                            aguafria.zig.runtime/invoke-with-result!
                                            (fn [& _#] (throw (ex-info "Called adapter body" {})))]
                                (aguafria.zig/precompile!
                                 {:namespaces ['aguafria.zig.precompile-fixture
                                               'aguafria.zig.precompile-noreturn-fixture]
                                  :calls [{:function 'aguafria.keyword/+ :args [:i32 :i32]}
                                          {:function 'aguafria.keyword/typeInfo
                                           :args [{:comptime-type :u8}]}
                                          {:function 'aguafria.keyword/typeInfo
                                           :args [{:comptime-type [:array 4 :u16]}]}
                                          {:function 'aguafria.keyword/typeInfo
                                           :args [{:comptime-type [:error-union :anyerror :i32]}]}]
                                  :coercions [:i32]}))
                              (do
                                (assert (= [{:int {:signedness :unsigned :bits 8}}
                                            {:array {:len 4 :child {:type "u16"} :sentinel_ptr nil}}
                                            {:error_union {:error_set {:type "anyerror"}
                                                           :payload {:type "i32"}}}]
                                           (mapv #(aguafria.zig/value (aguafria.keyword/typeInfo %))
                                                 [:u8 [:array 4 :u16] [:error-union :anyerror :i32]])))
                                (binding [aguafria.zig.runtime/*source-only-registration?* true]
                                  (require 'aguafria.zig.precompile-fixture
                                           'aguafria.zig.precompile-noreturn-fixture))
                                (doseq [[function# arguments#]
                                        [['aguafria.zig.precompile-noreturn-fixture/direct-panic []]
                                         ['aguafria.zig.precompile-noreturn-fixture/indirect-panic
                                          ["prepared indirect panic"]]]]
                                  (let [failure# (try
                                                   (apply (resolve function#) arguments#)
                                                   nil
                                                   (catch clojure.lang.ExceptionInfo error# error#))]
                                    (assert (= :native-panic
                                               (:aguafria/phase (ex-data failure#))))
                                    (assert (= :execution
                                               (:clojure.error/phase (ex-data failure#))))
                                    (assert (str/ends-with?
                                             (:clojure.error/source (ex-data failure#))
                                             "precompile_noreturn_fixture.clj"))))
                                (assert (= 42 (aguafria.zig/value
                                               ((resolve 'aguafria.zig.precompile-fixture/increment) 41))))
                                (assert (= 42 (aguafria.zig/value
                                               ((resolve 'aguafria.zig.precompile-fixture/subtract) 50 8))))
                                (assert (= 42 (aguafria.zig/value
                                               (aguafria.keyword/+ (aguafria.keyword/i32 20)
                                                                   (aguafria.keyword/i32 22))))))))]
              (prn {:builds (count @commands#)
                    :loaded (count (filter #(seq (:functions %))
                                           (vals @(var-get
                                                   (ns-resolve 'aguafria.zig.runtime
                                                               (symbol "registry"))))))
                    :commands @commands#
                    :calls (:calls report#)
                    :prepared (count (filter #(= :prepared (:status %))
                                             (:functions report#)))}))
            (shutdown-agents)
            (flush)
            (System/exit 0)))
        output (Files/createTempFile "aguafria-precompile-" ".log"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        process (.start (doto (ProcessBuilder.
                               ^java.util.List
                               [(str (System/getProperty "java.home") "/bin/java")
                                "--enable-native-access=ALL-UNNAMED"
                                "-cp" (System/getProperty "java.class.path")
                                "clojure.main" "-e" code])
                          (.redirectErrorStream true)
                          (.redirectOutput (.toFile output))))]
    (when-not (.waitFor process 120 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Precompilation test JVM timed out" {:log (str output)})))
    (let [text (slurp (.toFile output))]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "Precompilation test JVM failed" {:output text})))
      (edn/read-string (last (str/split-lines text))))))

(deftest precompilation-persists-across-jvms
  (let [parent (io/file ".aguafria/precompile-tests")
        _ (.mkdirs parent)
        cache-dir (str (Files/createTempDirectory
                        (.toPath (.getAbsoluteFile parent)) "cache-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        cold (fresh-jvm cache-dir true)
        restart (fresh-jvm cache-dir false)]
    (is (pos? (:builds cold)))
    (is (zero? (:loaded cold)))
    (is (zero? (:builds restart)) (str restart))
    (is (= 6 (:prepared cold)))
    (is (= [{:function 'aguafria.keyword/+ :args [:i32 :i32] :status :prepared}
            {:function 'aguafria.keyword/typeInfo :args [{:comptime-type :u8}] :status :prepared}
            {:function 'aguafria.keyword/typeInfo :args [{:comptime-type [:array 4 :u16]}] :status :prepared}
            {:function 'aguafria.keyword/typeInfo :args [{:comptime-type [:error-union :anyerror :i32]}] :status :prepared}]
           (:calls cold)))))
