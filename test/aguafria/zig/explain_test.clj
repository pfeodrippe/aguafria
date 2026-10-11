(ns aguafria.zig.explain-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.explain :as explanation]
            [aguafria.zig.runtime :as runtime]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest reports-source-before-evaluation-without-print-truncation
  (let [expanded (binding [*file* "some/project/example.clj"
                           *print-length* 1 *print-level* 1 *print-meta* true]
                   (macroexpand-1
                    (with-meta '(aguafria.zig/explain!
                                 (println "body output")
                                 (let [x 41] (inc x)))
                      {:line 37 :column 5})))
        writer (java.io.StringWriter.)]
    ;; The origin is the macro call, not the later runtime *file* binding.
    (binding [*out* writer *file* "different.clj"]
      (is (= 42 (eval expanded))))
    (let [output (str writer)]
      (is (str/starts-with? output "\n[aguafria] >>> BEGIN explain some/project/example.clj:37\n"))
      (is (str/includes? output "(println \"body output\")\n"))
      (is (str/includes? output "(let [x 41] (inc x))\n"))
      (is (< (str/index-of output "(let [x 41]")
             (str/index-of output "\nbody output\n")))
      (is (str/ends-with? output "[aguafria] <<< END explain\n\n"))
      (is (not (str/includes? output "different.clj"))))))

(deftest exact-source-preserves-comments-whitespace-and-reader-syntax
  (let [source "(let [x 41] ; keep this comment: 😀\n\t#_ (throw (Exception. \"discarded\"))\n    (inc x))"
        form (with-meta (list 'aguafria.zig/explain! (read-string source))
               {:line 12 :aguafria.explain/source source})
        output (with-out-str
                 (binding [*file* "unsaved.clj"]
                   (is (= 42 (eval form)))))]
    (is (str/starts-with? output (str "\n[aguafria] >>> BEGIN explain unsaved.clj:12\n" source "\n\n")))
    (is (str/ends-with? output "[aguafria] <<< END explain\n\n"))))

(deftest fallback-pretty-prints-rather-than-flattening-code
  (let [form '(let [a-very-long-binding-name (range 10)
                    another-long-binding-name (map inc a-very-long-binding-name)]
                (reduce + another-long-binding-name))
        [text] (explanation/form-texts '(a/explain!) [form])]
    (is (str/includes? text "\n"))
    (is (= form (read-string text)))))

(deftest direct-reporter-remains-compatible
  (let [writer (java.io.StringWriter.)]
    (binding [*out* writer]
      (is (= 42 (explanation/call-with-report (constantly 42)))))
    (is (= "[aguafria] no compiler/cache events\n" (str writer)))))

(deftest evaluation-is-unchanged
  (let [calls (atom 0)
        result (Object.)
        writer (java.io.StringWriter.)]
    (binding [*out* writer]
      (is (identical? result
                      (a/explain!
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
                      (try (a/explain! (throw failure))
                           (catch Throwable error error)))))
    (is (str/includes? (str writer) "no compiler/cache events"))))

(deftest failing-form-is-also-identified
  (let [writer (java.io.StringWriter.)]
    (binding [*out* writer *file* "failing.clj"]
      (is (thrown-with-msg? Exception #"original"
                            (eval (with-meta '(aguafria.zig/explain! (throw (Exception. "original")))
                                    {:line 19})))))
    (is (str/starts-with? (str writer) "\n[aguafria] >>> BEGIN explain failing.clj:19\n"))
    (is (str/includes? (str writer) "(throw (Exception. \"original\"))\n"))
    (is (str/ends-with? (str writer) "[aguafria] <<< END explain\n\n"))))

(deftest broken-report-output-does-not-change-evaluation
  (let [writer (proxy [java.io.Writer] []
                 (write [& _] (throw (java.io.IOException. "disconnected")))
                 (flush [] (throw (java.io.IOException. "disconnected")))
                 (close []))
        failure (ex-info "original" {})]
    (binding [*out* writer]
      (is (= 42 (a/explain! 42)))
      (is (identical? failure
                      (try (a/explain! (throw failure))
                           (catch Throwable error error)))))))

(deftest reporting-is-opt-in
  (is (= "" (with-out-str
              (explanation/event! {:event :compiled :module "silent"}))))
  (let [output (with-out-str
                 (a/explain!
                  (explanation/event! {:event :compiled :module "example"})
                  (explanation/event! {:event :disk-cache-hit :module "example"})))]
    (is (str/includes? output "1 compiled, 1 disk-cache-hit"))))

(deftest asynchronous-work-is-not-forced
  (let [gate (promise)
        writer (java.io.StringWriter.)
        work (binding [*out* writer]
               (a/explain!
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
        cold (with-out-str (a/explain! (runtime/precompile-function! qualified)))
        disk (with-out-str (a/explain! (runtime/precompile-function! qualified)))
        function (ns-resolve ns-object 'increment)
        result (atom nil)
        load-output (with-out-str (a/explain! (reset! result (function 41))))
        warm (with-out-str (a/explain! (reset! result (function 41))))]
    (is (str/includes? declaration-output "no compiler/cache events"))
    (is (str/includes? cold "[aguafria] compiled "))
    (is (str/includes? disk "[aguafria] disk-cache-hit "))
    (is (str/includes? load-output "[aguafria] disk-cache-hit "))
    (is (str/includes? warm "[aguafria] memory-cache-hit "))
    (is (= 42 (a/value @result)))))
