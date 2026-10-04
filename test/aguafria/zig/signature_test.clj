(ns aguafria.zig.signature-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.c :as c]
            [aguafria.std.math :as math]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.signature :as signature]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(deftest parsed-signatures-preserve-error-union-results
  (doseq [[source result]
          [["pub fn plain(comptime T: type, value: T) T" 'T]
           ["pub fn fallible(comptime T: type, value: T) !T" [:error-union 'T]]
           ["pub fn explicit(comptime T: type, value: T) error{Overflow}!T"
            [:error-union [:error-set [:Overflow]] 'T]]
           ["pub fn optional(comptime T: type, value: T) ?T" [:optional 'T]]
           ["pub fn fallibleVoid() !void" [:error-union :void]]]]
    (is (= result (:return (signature/declaration source))) source)))

(deftest fallible-imports-share-preparation-and-ordinary-calls
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.precompile-fallible-import-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Preparation executed native code" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! 'aguafria.zig.precompile-fallible-import-fixture))
        calls (filter #(= 'aguafria.std.math/shlExact (:function %)) (:operations report))
        events (atom [])]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (= 1 (count calls)))
    (is (every? #(and (= :observed (:status %)) (seq (:handlers %))
                      (every? (fn [handler] (= :prepared (:status handler))) (:handlers %)))
                calls)
        (pr-str (mapcat :handlers calls)))
    (binding [explain/*reporter* #(swap! events conj %)]
      (with-open [small (k/u32 3)
                  other (k/u32 5)
                  large (k/u32 0xffffffff)]
        (is (= {:ok 12} (a/value (math/shlExact :u32 small 2))))
        (is (= {:ok 20} (a/value (math/shlExact :u32 other 2))))
        (is (= "Overflow" (get-in (a/value (math/shlExact :u32 large 2)) [:error :name])))))
    (is (not-any? #(= :compiled (:event %)) @events) (pr-str @events))))

(deftest backing-integer-casts-share-zig-result-context
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.precompile-backing-integer-fixture :reload))
  (let [type (var-get (resolve 'aguafria.zig.precompile-backing-integer-fixture/Code))
        fail! (fn [& _] (throw (ex-info "Preparation executed native code" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! 'aguafria.zig.precompile-backing-integer-fixture))
        casts (filter #(and (= 'aguafria.keyword/as (:function %))
                            (:contextual-input? %)) (:operations report))
        events (atom [])]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:probe-failures report)))
    (is (seq casts))
    (is (every? #(and (= :observed (:status %)) (seq (:handlers %))
                      (every? (fn [handler] (= :prepared (:status handler))) (:handlers %)))
                casts) (pr-str casts))
    (binding [explain/*reporter* #(swap! events conj %)]
      (with-open [number (k/u8 8)
                  other (k/u8 13)
                  code (k/as (k/fromBackingInt (k/intCast number)) type)
                  changed (k/as (k/fromBackingInt (k/intCast other)) type)]
        (is (= 8 (a/value (k/intFromEnum code))))
        (is (= 13 (a/value (k/intFromEnum changed))))))
    (is (not-any? #(= :compiled (:event %)) @events) (pr-str @events))
    (is (= 8 (a/value (k/intFromEnum (k/as (k/fromBackingInt (k/intCast 8)) type)))))
    (let [error (try
                  (k/as (k/fromBackingInt (k/u16 8)) type)
                  nil
                  (catch Exception failure (discovery/error-report failure)))]
      (is (str/includes? (:stderr error) "expected type 'u8', found 'u16'")
          (pr-str error)))))

(deftest comptime-function-bodies-preserve-their-call-context
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.precompile-comptime-body-fixture :reload))
  (let [fail! (fn [& _] (throw (ex-info "Preparation executed native code" {})))
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (discovery/prepare! 'aguafria.zig.precompile-comptime-body-fixture))
        calls (filter #(= 'aguafria.zig.precompile-comptime-body-fixture/size-is-four
                          (:function %)) (:operations report))
        size-is-four (resolve 'aguafria.zig.precompile-comptime-body-fixture/size-is-four)
        events (atom [])]
    (is (zero? (get-in report [:baseline :exit])) (:diagnostics report))
    (is (not (:compiler-errors? report)) (:diagnostics report))
    (is (empty? (:probe-failures report)))
    (is (= 2 (count calls)))
    (is (every? #(and (= :observed (:status %)) (seq (:handlers %))
                      (every? (fn [handler] (= :prepared (:status handler))) (:handlers %)))
                calls) (pr-str calls))
    (binding [explain/*reporter* #(swap! events conj %)]
      (is (true? (size-is-four :u32)))
      (is (false? (size-is-four :u16))))
    (is (not-any? #(= :compiled (:event %)) @events) (pr-str @events))))

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

(defn- import-cache-process [cache prepare? fixture invocation]
  (let [code
        `(do
           (require 'aguafria.zig 'aguafria.keyword 'aguafria.std.c
                    'aguafria.std.mem 'aguafria.std.math 'aguafria.zig.runtime
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
                        {:analyze ['~fixture]}))
                     (do
                       (require '~fixture)
                       ~invocation
                       :passed)))]
             (prn {:builds (count (filter #(= :compiled (:event %)) @events#))
                   :compiled-details (mapv #(select-keys % [:module :artifact-key :path])
                                           (filter #(= :compiled (:event %)) @events#))
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
                   :failures (into []
                                   (comp (mapcat :operations) (mapcat :handlers)
                                         (filter #(= :failed (:status %)))
                                         (map #(select-keys % [:message :stderr :source-path])))
                                   (:analysis result#))
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
        output (Files/createTempFile "aguafria-import-cache-" ".log"
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
      (throw (ex-info "Import cache test JVM timed out" {:log (str output)})))
    (let [text (slurp (.toFile output))]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "Import cache test JVM failed" {:log (str output) :output text})))
      (edn/read-string (last (str/split-lines text))))))

(defn- verify-import-cache! [fixture invocation]
  (let [cache (str (Files/createTempDirectory "aguafria-import-precompile-"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (import-cache-process cache true fixture invocation)
        invoked (import-cache-process cache false fixture invocation)]
    (is (pos? (:builds prepared)))
    (is (zero? (:loaded prepared)))
    (is (zero? (get-in prepared [:coverage :handler-records :failed] 0))
        (pr-str (:failures prepared)))
    (is (= 1 (:packs prepared)))
    (is (pos? (:bundle-hits invoked)))
    (is (= 1 (:bundle-loads invoked)))
    (is (seq (:bundle-keys invoked)))
    (is (every? (:artifact-keys prepared) (:bundle-keys invoked)))
    (is (zero? (:builds invoked)) (pr-str (dissoc invoked :libraries)))
    (is (seq (:libraries prepared)))
    (is (= (:libraries prepared) (:libraries invoked))
        "Fresh ordinary imported calls must reuse AOT without creating dylibs")
    {:prepared prepared :invoked invoked}))

(deftest aliased-c-functions-reuse-aot-in-a-fresh-ordinary-jvm
  (let [{:keys [prepared]}
        (verify-import-cache!
         'aguafria.zig.precompile-alias-fixture
         '(do
            (assert (zero? (aguafria.zig/value
                            ((resolve 'aguafria.zig.precompile-alias-fixture/monotonic-status)))))
            (assert (zero? (aguafria.zig/value
                            ((resolve 'aguafria.zig.precompile-alias-fixture/sleep-status)))))
            (with-open [timestamp (aguafria.keyword/var
                                   (aguafria.std.mem/zeroes
                                    (aguafria.zig/type aguafria.std.c/timespec)))
                        pointer (aguafria.keyword/& timestamp)]
              (assert (zero? (aguafria.zig/value
                              (aguafria.std.c/nanosleep pointer nil))))
              (assert (zero? (aguafria.zig/value
                              (aguafria.std.c/clock_gettime :.MONOTONIC pointer)))))))]
    (is (zero? (get-in prepared [:coverage :runtime-candidates :not-fully-prepared] 0))
        (pr-str (:coverage prepared)))))

(deftest backing-integer-casts-reuse-aot-in-a-fresh-ordinary-jvm
  (verify-import-cache!
   'aguafria.zig.precompile-backing-integer-fixture
   '(let [type (var-get (resolve 'aguafria.zig.precompile-backing-integer-fixture/Code))]
      (with-open [number (aguafria.keyword/u8 8)
                  other (aguafria.keyword/u8 13)
                  code (aguafria.keyword/as
                        (aguafria.keyword/fromBackingInt (aguafria.keyword/intCast number)) type)
                  changed (aguafria.keyword/as
                           (aguafria.keyword/fromBackingInt (aguafria.keyword/intCast other)) type)]
        (assert (= 8 (aguafria.zig/value (aguafria.keyword/intFromEnum code))))
        (assert (= 13 (aguafria.zig/value (aguafria.keyword/intFromEnum changed))))
        (assert (true? (aguafria.keyword/!= code :.zero)))
        (assert (true? (aguafria.keyword/== code :.eight)))))))

(deftest owner-local-layout-adapters-reuse-aot-in-a-fresh-ordinary-jvm
  (verify-import-cache!
   'aguafria.zig.precompile-owner-layout-fixture
   '(let [type (var-get (resolve 'aguafria.zig.precompile-owner-layout-fixture/PrivateRecord))]
      (with-open [record (type {:slice 42 :const 9})
                  field (aguafria.zig/field record :slice)
                  other (aguafria.zig/field record :const)]
        (assert (= {:slice 42 :const 9} (aguafria.zig/value record)))
        (assert (= 42 (aguafria.zig/value field)))
        (assert (= 9 (aguafria.zig/value other)))))))

(deftest comptime-function-bodies-reuse-aot-in-a-fresh-ordinary-jvm
  (verify-import-cache!
   'aguafria.zig.precompile-comptime-body-fixture
   '(let [size-is-four (resolve 'aguafria.zig.precompile-comptime-body-fixture/size-is-four)]
      (assert (true? (size-is-four :u32)))
      (assert (false? (size-is-four :u16))))))

(deftest comptime-constant-receivers-reuse-aot-in-a-fresh-ordinary-jvm
  (let [{:keys [prepared]}
        (verify-import-cache!
         'aguafria.zig.precompile-comptime-receiver-fixture
         '(doseq [[reference expected]
                  [['aguafria.zig.precompile-comptime-receiver-fixture/first-root 6]
                   ['aguafria.zig.precompile-comptime-receiver-fixture/second-root 20]
                   ['aguafria.zig.precompile-comptime-receiver-fixture/inferred-root 6]]]
            (let [root (var-get (resolve reference))
                  settings (:settings root)]
              (assert (= expected (aguafria.zig/value ((:bytes settings))))))))]
    (is (zero? (get-in prepared [:coverage :handler-records :failed] 0)))))

(deftest constant-container-methods-reuse-aot-in-a-fresh-ordinary-jvm
  (verify-import-cache!
   'aguafria.zig.discovery-const-container-caller-fixture
   '(let [code (var-get (resolve 'aguafria.zig.discovery-const-container-fixture/Code))
          cell (var-get (resolve 'aguafria.zig.discovery-const-container-fixture/Cell))]
      (assert (= 8 (aguafria.zig/value ((:increment code) 7))))
      (assert (= 14 (aguafria.zig/value ((:twice cell) 7)))))))

(deftest fallible-imports-reuse-aot-in-a-fresh-ordinary-jvm
  (let [{:keys [prepared]}
        (verify-import-cache!
         'aguafria.zig.precompile-fallible-import-fixture
         '(with-open [small (aguafria.keyword/u32 3)
                      other (aguafria.keyword/u32 5)
                      large (aguafria.keyword/u32 0xffffffff)]
            (assert (= {:ok 12} (aguafria.zig/value
                                 (aguafria.std.math/shlExact :u32 small 2))))
            (assert (= {:ok 20} (aguafria.zig/value
                                 (aguafria.std.math/shlExact :u32 other 2))))
            (assert (= "Overflow" (get-in (aguafria.zig/value
                                           (aguafria.std.math/shlExact :u32 large 2))
                                          [:error :name])))))]
    (is (zero? (get-in prepared [:coverage :runtime-candidates :not-fully-prepared])))))
