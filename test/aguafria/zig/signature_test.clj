(ns aguafria.zig.signature-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.c :as c]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.signature :as signature]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(deftest imported-static-types-use-their-native-reference
  (doseq [type [c/timespec #'c/timespec 'aguafria.std.c/timespec]]
    (is (= 'aguafria.std.c/timespec (jvm/constructor-type type)))
    (is (= [:* 'aguafria.std.c/timespec]
           (jvm/constructor-type [:* type])))))

(deftest zig-selected-alias-signatures-are-shared-by-call-planners
  (let [fail! (fn [& _] (throw (ex-info "Signature inspection invoked native code" {})))]
    (doseq [v [#'c/clock_gettime #'c/nanosleep]]
      (let [reference (:aguafria/zig-reference (meta v))
            selected (with-redefs [runtime/invoke! fail!
                                   runtime/invoke-with-result! fail!]
                       (signature/callable-declaration reference))]
        (is (= :global-const (:category reference)))
        (is (= 2 (count (:args selected))))
        (is (= :c_int (:return selected)))
        (is (= (:args selected) (jvm/call-parameters (meta v))))))
    (is (= :prepared
           (:status
            (binding [runtime/*compile-only?* true]
              (with-redefs [runtime/invoke! fail!]
                (jvm/precompile-call!
                 '{:function aguafria.std.c/clock_gettime
                   :args [{:comptime :.MONOTONIC} [:* aguafria.std.c/timespec]]}))))))
    (with-open [timestamp (k/var (mem/zeroes (a/type c/timespec)))
                pointer (k/& timestamp)]
      (is (zero? (a/value (c/clock_gettime :.MONOTONIC pointer))))
      (is (pos? (a/value (:sec timestamp)))))))

(defn- alias-cache-process [cache prepare?]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.keyword 'aguafria.std.c
                    'aguafria.std.mem 'aguafria.zig.runtime
                    'aguafria.zig.explain 'clojure.java.io 'clojure.string)
           (aguafria.zig/configure! {:cache-dir ~cache :async? false})
           (let [events# (atom [])
                 result#
                 (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                   (if ~prepare?
                     (with-redefs [aguafria.zig.runtime/invoke!
                                   (fn [& _#] (throw (ex-info "Preparation executed a body" {})))
                                   aguafria.zig.runtime/invoke-with-result!
                                   (fn [& _#] (throw (ex-info "Preparation executed an adapter" {})))]
                       (aguafria.zig/precompile!
                        {:analyze ['aguafria.zig.precompile-alias-fixture]}))
                     (do
                       (require 'aguafria.zig.precompile-alias-fixture)
                       (assert (zero? (aguafria.zig/value
                                       ((resolve 'aguafria.zig.precompile-alias-fixture/monotonic-status)))))
                       (assert (zero? (aguafria.zig/value
                                       ((resolve 'aguafria.zig.precompile-alias-fixture/sleep-status)))))
                       (with-open [timestamp# (aguafria.keyword/var
                                               (aguafria.std.mem/zeroes
                                                (aguafria.zig/type aguafria.std.c/timespec)))
                                   pointer# (aguafria.keyword/& timestamp#)]
                         (assert (zero? (aguafria.zig/value
                                         (aguafria.std.c/nanosleep pointer# nil))))
                         (assert (zero? (aguafria.zig/value
                                         (aguafria.std.c/clock_gettime :.MONOTONIC pointer#)))))
                       :passed)))]
             (prn {:builds (count (filter #(= :compiled (:event %)) @events#))
                   :bundle-hits (count (filter #(= :bundle-cache-hit (:event %)) @events#))
                   :bundle-loads (count (filter #(= :bundle-loaded (:event %)) @events#))
                   :packs (count (get-in result# [:bundles :packs]))
                   :artifact-keys (into #{} (keep :artifact-key) @events#)
                   :bundle-keys (into #{}
                                      (comp (filter #(= :bundle-cache-hit (:event %)))
                                            (map :artifact-key)) @events#)
                   :loaded (count (filter #(seq (:functions %))
                                          (vals @(var-get
                                                  (ns-resolve 'aguafria.zig.runtime
                                                              (symbol "registry"))))))
                   :coverage (:coverage result#)
                   :libraries (into #{}
                                    (comp (filter #(.isFile %))
                                          (filter #(some (fn [suffix#]
                                                           (clojure.string/ends-with?
                                                            (.getName %) suffix#))
                                                         [".dylib" ".so" ".dll"]))
                                          (map str))
                                    (file-seq (clojure.java.io/file ~cache)))}))
           (shutdown-agents)
           (flush)
           (System/exit 0))
        output (Files/createTempFile "aguafria-alias-cache-" ".log"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        process (.start (doto (ProcessBuilder.
                               ^java.util.List
                               [(str (System/getProperty "java.home") "/bin/java")
                                "--enable-native-access=ALL-UNNAMED" "-Xmx2g"
                                "-cp" (System/getProperty "java.class.path")
                                "clojure.main" "-e" (pr-str code)])
                          (.redirectErrorStream true)
                          (.redirectOutput (.toFile output))))]
    (when-not (.waitFor process 180 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Alias cache test JVM timed out" {:log (str output)})))
    (let [text (slurp (.toFile output))]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "Alias cache test JVM failed" {:log (str output) :output text})))
      (edn/read-string (last (str/split-lines text))))))

(deftest aliased-c-functions-reuse-aot-in-a-fresh-ordinary-jvm
  (let [cache (str (Files/createTempDirectory "aguafria-alias-precompile-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (alias-cache-process cache true)
        invoked (alias-cache-process cache false)]
    (is (pos? (:builds prepared)))
    (is (zero? (:loaded prepared)))
    (is (zero? (get-in prepared [:coverage :handler-records :failed] 0)))
    (is (= 1 (:packs prepared)))
    (is (pos? (:bundle-hits invoked)))
    (is (= 1 (:bundle-loads invoked)))
    (is (seq (:bundle-keys invoked)))
    (is (every? (:artifact-keys prepared) (:bundle-keys invoked)))
    (is (zero? (:builds invoked)) (pr-str invoked))
    (is (seq (:libraries prepared)))
    (is (= (:libraries prepared) (:libraries invoked))
        "Fresh ordinary alias calls must reuse AOT without creating dylibs")))
