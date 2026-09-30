(ns aguafria.zig.peer-call-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as native-testing]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.package :as package]
            [aguafria.zig.peer-call :as peer-call]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private source (slurp "test/fixtures/peer_calls.zig"))

(deftest proof-requires-unmodified-peer-forwarding
  (is (peer-call/peer-wrapper-source? source "compare"))
  (doseq [unsafe [(str/replace source "@TypeOf(expected, actual)" "@TypeOf(actual)")
                  (str/replace source "return compareTyped" "_ = @TypeOf(expected); return compareTyped")
                  (str/replace source "fn compareTyped" "inline fn compareTyped")
                  (str/replace source "expected: T" "comptime expected: T")
                  (str/replace source "expected: T" "expected: anytype")
                  (str/replace source "expected: anytype" "comptime expected: anytype")
                  (str/replace source "compareTyped(T, expected, actual)" "compareTyped(T, actual, expected)")
                  (str "const Nested = struct { " source " }; ")]]
    (is (false? (peer-call/peer-wrapper-source? unsafe "compare")))))

(defn- recorded [f]
  (let [events (atom [])]
    (binding [explain/*reporter* #(swap! events conj %)]
      {:result (f) :events @events})))

(deftest changed-values-reuse-handlers-without-changing-native-emission
  (is (= {:ok nil} (native-testing/expectEqual 3735928544 (k/as 3735928544 :usize))))
  (let [success (recorded #(native-testing/expectEqual 3735928320 (k/as 3735928320 :usize)))
        failed (recorded #(native-testing/expectEqual 3735928320 (k/as 3735928544 :usize)))]
    (is (= {:ok nil} (:result success)))
    (is (= "TestExpectedEqual" (get-in failed [:result :error :name])))
    (doseq [result [success failed]]
      (is (seq (:events result)))
      (is (not-any? #(= :compiled (:event %)) (:events result)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out of range"
                        (native-testing/expectEqual -1 (k/as 0 :usize))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out of range"
                        (native-testing/expectEqual 256 (k/as 0 :u8))))
  (is (= {:ok nil} (native-testing/expectEqual 1.2 (k/as 1.2 :f32))))
  (let [{:keys [result events]} (recorded #(native-testing/expectEqual 3.4 (k/as 3.4 :f32)))]
    (is (= {:ok nil} result))
    (is (not-any? #(= :compiled (:event %)) events)))
  ;; This decimal is just above a float32 midpoint. Going through a JVM
  ;; double-to-float cast rounds down instead; Zig's source literal rounds up.
  (is (= {:ok nil}
         (native-testing/expectEqual 1.0000000596046448
                                     (k/as 1.0000001192092896 :f32))))
  (is (= {:ok nil} (native-testing/expectEqual true true)))
  (let [{:keys [result events]} (recorded #(native-testing/expectEqual false false))]
    (is (= {:ok nil} result))
    (is (not-any? #(= :compiled (:event %)) events)))
  (is (= "@import(\"std\").testing.expectEqual(1234, actual)"
         (emitter/emit-expr (the-ns 'aguafria.zig.peer-call-test)
                            '(native-testing/expectEqual 1234 actual)))))

(deftest package-functions-and-precompile-use-the-same-generic-plan
  (let [configuration (runtime/configuration)
        namespace 'aguafria.pkg.peer-call-fixture
        function (symbol (str namespace) "compare-values")
        root (.getCanonicalPath (io/file "test/fixtures/peer_calls.zig"))]
    (try
      (runtime/configure! {:async? false
                           :modules (assoc (:modules configuration) "peer_fixture" root)
                           :module-cache-tokens (assoc (:module-cache-tokens configuration)
                                                       "peer_fixture" source)})
      ;; The fixture is already local; exercise catalog loading without a
      ;; package download. Compilation and every native call remain real.
      (with-redefs [package/install! (constantly {"peer_fixture" {:package-root (.getParent (io/file root))}})]
        (package/install-catalog!
         {:schema-version 1 :packages {}
          :namespaces [{:name namespace
                        :members [{:category :function :clojure-name "compare-values"
                                   :package "peer_fixture" :param-count 2
                                   :signature "pub fn compare(expected: anytype, actual: anytype) !void"
                                   :source "peer_calls.zig" :symbol function :zig-alias "peer_fixture"
                                   :zig-name "compare"}]}]}))
      (is (= root (:zig/source-file (meta (find-var function)))))
      (let [compare (find-var function)
            actual (k/as 9876 :u29)]
        (let [preparation (recorded #(binding [runtime/*compile-only?* true]
                                       (jvm/precompile-call!
                                        {:function function :args [{:literal 17 :type :comptime_int} :u29]})))]
          (is (= :prepared (get-in preparation [:result :status]))))
        ;; No call to compare before this point: preparation must create the
        ;; same adapter as runtime, even with a different literal's value.
        (let [{:keys [result events]} (recorded #(compare 9876 actual))]
          (is (= {:ok nil} result))
          (is (seq events))
          (is (not-any? #(= :compiled (:event %)) events)))
        (is (= {:error {:name "Different"}} (compare 1234 actual))))
      (let [compare (find-var function)
            actual (k/as 9.75 :f64)]
        (is (= :prepared (:status (binding [runtime/*compile-only?* true]
                                    (jvm/precompile-call!
                                     {:function function :args [{:literal 1.25 :type :comptime_float} :f64]})))))
        (let [{:keys [result events]} (recorded #(compare 9.75 actual))]
          (is (= {:ok nil} result))
          (is (not-any? #(= :compiled (:event %)) events))))
      (finally (runtime/configure! configuration)))))
