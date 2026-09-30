(ns aguafria.zig.explain-test
  (:require [aguafria.zig :as az]
            [aguafria.zig.explain :as explanation]
            [aguafria.zig.runtime :as runtime]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest evaluation-is-unchanged
  (let [calls (atom 0)
        result (Object.)
        writer (java.io.StringWriter.)]
    (binding [*out* writer]
      (is (identical? result
                      (az/explain!
                       (swap! calls inc)
                       (println "ordinary output")
                       result))))
    (is (= 1 @calls))
    (is (str/includes? (str writer) "ordinary output"))
    (is (str/includes? (str writer) "no compiler/cache events"))))

(deftest exceptions-are-not-replaced
  (let [failure (ex-info "original" {:detail 42})
        writer (java.io.StringWriter.)]
    (binding [*out* writer]
      (is (identical? failure
                      (try (az/explain! (throw failure))
                           (catch Throwable error error)))))
    (is (str/includes? (str writer) "no compiler/cache events"))))

(deftest broken-report-output-does-not-change-evaluation
  (let [writer (proxy [java.io.Writer] []
                 (write [& _] (throw (java.io.IOException. "disconnected")))
                 (flush [] (throw (java.io.IOException. "disconnected")))
                 (close []))
        failure (ex-info "original" {})]
    (binding [*out* writer]
      (is (= 42 (az/explain! 42)))
      (is (identical? failure
                      (try (az/explain! (throw failure))
                           (catch Throwable error error)))))))

(deftest reporting-is-opt-in
  (is (= "" (with-out-str
              (explanation/event! {:event :compiled :module "silent"}))))
  (let [output (with-out-str
                 (az/explain!
                  (explanation/event! {:event :compiled :module "example"})
                  (explanation/event! {:event :disk-cache-hit :module "example"})))]
    (is (str/includes? output "1 compiled, 1 disk-cache-hit"))))

(deftest asynchronous-work-is-not-forced
  (let [gate (promise)
        writer (java.io.StringWriter.)
        work (binding [*out* writer]
               (az/explain!
                (future
                  @gate
                  (explanation/event! {:event :compiled :module "async"})
                  :done)))]
    (try
      (is (not (realized? work)))
      (is (str/includes? (str writer) "no compiler/cache events"))
      (deliver gate true)
      (is (= :done (deref work 10000 :timeout)))
      (is (str/includes? (str writer) "[aguafria] compiled async"))
      (finally (deliver gate true)))))

(deftest real-native-compilation-and-reuse
  (let [module (symbol (str "aguafria.explain-fixture-" (java.util.UUID/randomUUID)))
        ns-object (create-ns module)
        qualified (symbol (str module) "increment")
        declaration-output
        (with-out-str
          (binding [*ns* ns-object
                    runtime/*source-only-registration?* true]
            (clojure.core/refer 'clojure.core)
            (eval '(aguafria.zig/explain!
                    (aguafria.zig/defn increment :i32 [[x :i32]] (+ x 1))))))
        cold (with-out-str (az/explain! (runtime/precompile-function! qualified)))
        disk (with-out-str (az/explain! (runtime/precompile-function! qualified)))
        function (ns-resolve ns-object 'increment)
        result (atom nil)
        load-output (with-out-str (az/explain! (reset! result (function 41))))
        warm (with-out-str (az/explain! (reset! result (function 41))))]
    (is (str/includes? declaration-output "no compiler/cache events"))
    (is (str/includes? cold "[aguafria] compiled "))
    (is (str/includes? disk "[aguafria] disk-cache-hit "))
    (is (str/includes? load-output "[aguafria] disk-cache-hit "))
    (is (str/includes? warm "[aguafria] memory-cache-hit "))
    (is (= 42 (az/value @result)))))
