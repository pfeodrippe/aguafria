(ns aguafria.runtime-diagnostics-test
  (:require [aguafria.zig :as az]
            [aguafria.zig.runtime :as runtime]
            [clojure.main :as main]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest standard-clojure-compilation-report
  (let [location {:file "/project/src/demo.clj" :line 12 :column 5
                  :declaration "demo/main"}
        error (#'runtime/compilation-exception
               "error union is ignored"
               {:aguafria/phase :zig-compile :stderr "full compiler stderr"
                :aguafria/report "full code frame"}
               location nil)
        triage (main/ex-triage (Throwable->map error))
        cause (.getCause error)]
    (is (instance? clojure.lang.Compiler$CompilerException error))
    (is (instance? clojure.lang.ExceptionInfo cause))
    (is (= :compile-syntax-check (:clojure.error/phase triage)))
    (is (= "/project/src/demo.clj" (:clojure.error/path triage)))
    (is (= 12 (:clojure.error/line triage)))
    (is (= 5 (:clojure.error/column triage)))
    (is (= 'demo/main (:clojure.error/symbol triage)))
    (is (= "error union is ignored" (:aguafria/summary (runtime/error-data error))))
    (is (= "full code frame" (:clojure.error/cause triage)))
    (is (= "/project/src/demo.clj" (:clojure.error/source (ex-data cause))))
    (is (= "full compiler stderr" (:stderr (runtime/error-data error))))
    (is (str/includes? (main/ex-str triage) "full code frame"))
    (is (str/includes? (main/err->msg error) "full code frame"))))

(deftest emission-cause-is-preserved
  (let [cause (ex-info "Cannot emit this form" {:form '(unknown)})
        error (#'runtime/compilation-exception
               (ex-message cause) {:aguafria/phase :emit}
               {:file "demo.clj" :line 7 :column 3} cause)]
    (is (identical? cause (ex-cause (ex-cause error))))
    (is (= 7 (:clojure.error/line (main/ex-triage (Throwable->map error)))))))

(deftest file-loading-preserves-the-compiler-location
  (let [error (#'runtime/compilation-exception
               "error union is ignored"
               {:aguafria/report "error union is ignored\nfull native details"}
               {:file "/project/hello.clj" :line 10 :column 7
                :declaration "hello/main"}
               nil)]
    ;; load-string/load-file wrap non-CompilerException failures as :execution.
    ;; Inspecting err->msg on the original throwable alone misses this bug.
    (with-redefs [runtime/invoke! (fn [& _] (throw error))]
      (doseq [evaluate [load-string #(eval (read-string %))]]
        (let [caught (try
                       (evaluate "(aguafria.zig.runtime/invoke! 'hello/main [])")
                       (catch Throwable e e))
              report (main/err->msg caught)]
          (is (identical? error caught))
          (is (str/includes? report "(/project/hello.clj:10:7)"))
          (is (str/starts-with? report "Syntax error"))
          (is (str/includes? report "full native details"))
          (is (not (str/includes? report "runtime.clj:"))))))))

(deftest missing-location-does-not-invent-a-clojure-file
  (let [error (#'runtime/compilation-exception "Link failed" {} nil nil)
        triage (main/ex-triage (Throwable->map error))]
    (is (nil? (:clojure.error/source triage)))
    (is (= 1 (:clojure.error/line triage)))
    (is (= "Link failed" (:clojure.error/cause triage)))))

(deftest compiler-summary-keeps-the-full-diagnostics
  (let [source (str "// Aguafria source: /project/demo.clj:7:3\n"
                    "// Aguafria declaration: demo/main\n"
                    "const broken = missing;\n")
        result (#'runtime/pretty-zig-error
                "demo" source "/generated/demo.zig" ["zig" "build-lib"]
                (str "/generated/demo.zig:3:16: error: undeclared identifier 'missing'\n"
                     "/generated/demo.zig:3:1: note: referenced here\n"))]
    (is (= "undeclared identifier 'missing'" (:message result)))
    (is (= 2 (count (:diagnostics result))))
    (is (= "/project/demo.clj" (get-in result [:diagnostics 0 :aguafria/source :file])))
    (is (str/includes? (:report result) "Raw Zig diagnostics:"))))

(deftest real-compiler-error-at-jvm-call
  (let [configuration (az/configuration)
        ns-name (symbol (str "aguafria.ide-error-" (random-uuid)))
        fixture-ns (create-ns ns-name)
        file (str (System/getProperty "user.dir") "/ide-error-fixture.clj")]
    (try
      (az/configure! {:async? false :modules {}})
      (binding [*ns* fixture-ns *file* file
                runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'az 'aguafria.zig)
        (eval (with-meta '(az/defn broken :i32 [] true)
                {:line 23 :column 4})))
      (let [error (try
                    ((ns-resolve fixture-ns 'broken))
                    nil
                    (catch clojure.lang.Compiler$CompilerException error error))
            triage (main/ex-triage (Throwable->map error))]
        (is (some? error))
        (is (= :compile-syntax-check (:clojure.error/phase triage)))
        (is (= file (:clojure.error/source (runtime/error-data error))))
        (is (= "ide-error-fixture.clj" (:clojure.error/path triage)))
        (is (= 23 (:clojure.error/line triage)))
        (is (str/includes? (:clojure.error/cause triage) "expected type"))
        (is (str/includes? (main/err->msg error) "error[aguafria::zig]"))
        (is (seq (:diagnostics (runtime/error-data error))))
        (is (seq (:stderr (runtime/error-data error))))
        (is (str/includes? (:aguafria/report (runtime/error-data error)) "Zig reported the error here")))
      (finally
        (az/configure! configuration)
        (remove-ns ns-name)))))

(deftest missing-try-reports-the-call-and-recovery-works
  (let [configuration (az/configuration)
        ns-name (symbol (str "aguafria.ide-try-" (random-uuid)))
        fixture-ns (create-ns ns-name)]
    (try
      (az/configure! {:async? false :modules {}})
      (binding [*ns* fixture-ns *file* "missing_try.clj"
                runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'az 'aguafria.zig)
        (eval '(az/defn may-fail :!void [] (set! _ 0)))
        (eval (with-meta
                (list 'az/defn 'broken :void []
                      (with-meta '(may-fail) {:line 9 :column 3}))
                {:line 8 :column 1})))
      (let [error (try
                    ((ns-resolve fixture-ns 'broken))
                    nil
                    (catch clojure.lang.Compiler$CompilerException error error))
            triage (main/ex-triage (Throwable->map error))]
        (is (= "missing_try.clj" (:clojure.error/source triage)))
        (is (= 9 (:clojure.error/line triage)))
        (is (= 3 (:clojure.error/column triage)))
        (is (str/includes? (main/err->msg error) "error union is ignored")))
      (binding [*ns* fixture-ns *file* "missing_try.clj"
                runtime/*source-only-registration?* true]
        (eval '(az/defn broken :void [] (try (may-fail)))))
      (is (nil? ((ns-resolve fixture-ns 'broken))))
      (finally
        (az/configure! configuration)
        (remove-ns ns-name)))))
