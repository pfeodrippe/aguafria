(ns aguafria.zig.jvm-test
  (:require [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]
            [aguafria.zig.jvm :as native-call]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io StringWriter]))

(defn- fixture []
  (let [namespace (create-ns (gensym "aguafria.native-call-fixture-"))]
    (binding [*ns* namespace]
      (refer 'clojure.core)
      (require '[aguafria.zig :as az] '[aguafria.keyword :as ak]
               '[aguafria.std.debug :as debug]))
    namespace))

(deftest direct-imported-function-results-and-output
  (let [err (StringWriter.)]
    (binding [*err* err]
      (is (nil? (debug/print "Hello, {s}!\n" ["World"])))
      (is (nil? (debug/print "Hello, {s}!\n" ["Clojure"]))))
    (is (= "Hello, World!\nHello, Clojure!\n" (str err))))
  (is (= 3.0 (math/sqrt 9.0)))
  (is (= 4.0 (math/sqrt 16.0)))
  (is (Double/isNaN (math/sqrt -1.0)))
  (let [adapters-before (count @@#'native-call/prepared-adapters)]
    (is (= 5.0 (math/sqrt 25.0)))
    (is (= adapters-before (count @@#'native-call/prepared-adapters))
        "changing an ordinary argument reuses the typed adapter")))

(deftest generic-private-functions-are-ordinary-callable-vars
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (eval '(az/defn- maximum T
               [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
               (if (== T :bool)
                 (or left right)
                 (if (> left right) left right)))))
    (let [maximum (ns-resolve namespace 'maximum)]
      (is (true? (maximum :bool false true)))
      (is (false? (maximum :bool false false)))
      (is (= 8 (maximum :i32 3 8)))
      (is (= 12 (maximum :i32 12 4)))
      (is (true? (.invoke ^clojure.lang.IFn maximum :bool false true)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Wrong number"
                           (maximum :bool true)))
      (binding [*ns* namespace]
        (eval '(az/defn- maximum T
                 [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
                 (if (== T :bool)
                   (and left right)
                   (if (< left right) left right)))))
      (is (false? (maximum :bool false true)))
      (is (= 3 (maximum :i32 3 8))))))

(deftest generic-calls-use-live-native-state
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (eval '(az/defvar calls :i64 0))
      (eval '(az/defn count-call :i64 [[T {:zig/prefix "comptime"} :type] [x T]]
               (set! _ x)
               (set! calls (+ calls 1))
               calls))
      (eval '(az/defn count-now :i64 [] calls)))
    (let [count-call (ns-resolve namespace 'count-call)
          count-now (ns-resolve namespace 'count-now)]
      (is (= 1 (count-call :bool true)))
      (is (= 2 (count-call :bool false)))
      (is (= 2 (count-now))))))

(deftest native-output-is-restored-after-failure
  (let [err (StringWriter.)]
    (binding [*err* err]
      (is (thrown-with-msg? Exception #"deliberate"
                           (native-call/call-with-output
                            #(do (debug/print "before\n" [])
                                 (throw (Exception. "deliberate"))))))
      (debug/print "after\n" []))
    (is (= "before\nafter\n" (str err)))))

(deftest normal-zero-arg-function-forwards-native-output
  (let [namespace (fixture)
        err (StringWriter.)]
    (binding [*ns* namespace]
      (eval '(az/defn main :void [] (debug/print "native main\n" []))))
    (binding [*err* err]
      (is (nil? ((ns-resolve namespace 'main)))))
    (is (= "native main\n" (str err)))))

(deftest forced-inline-runtime-result-still-folds-at-the-callsite
  (let [namespace (fixture)
        err (StringWriter.)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defn- inline-add :i32 {:zig/prefix "inline"}
                 [[left :i32] [right :i32]]
                 (debug/print "inside inline call\n" [])
                 (+ left right)))
        (eval '(az/defn main :void []
                 (when (!= (inline-add 1200 34) 1234)
                   (ak/compileError "inline result no longer folds")))))
      (binding [*err* err]
        (is (nil? ((ns-resolve namespace 'main)))))
      (is (= "inside inline call\n" (str err)))
      (finally (remove-ns (ns-name namespace))))))

(deftest process-init-main-is-directly-callable
  (let [namespace (fixture)
        out (StringWriter.)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std.process :as process]
                 '[aguafria.std.Io.File :as std-file])
        (eval '(az/defn main :!void [[init process/Init]]
                 (try (std-file/writeStreamingAll (std-file/stdout)
                                                  (az/field init :io)
                                                  "main in this JVM\n"))))
        (eval '(az/defn ordinary :i32 [[value :i32]] value)))
      (binding [*out* out]
        (is (nil? ((ns-resolve namespace 'main)))))
      (is (= "main in this JVM\n" (str out)))
      (is (= 42 ((ns-resolve namespace 'ordinary) 42)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)(arity|arguments)"
                           ((ns-resolve namespace 'ordinary))))
      (finally (remove-ns (ns-name namespace))))))

(deftest minimal-process-main-receives-argv
  (let [namespace (fixture)
        err (StringWriter.)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std.process.Init :as process-init])
        (eval '(az/defn main :!void [[init process-init/Minimal]]
                 (let [arguments (az/field (az/field init :args) :vector)]
                   (debug/print "argc={d}; argv0={s}\n"
                                [(az/field arguments :len) (az/index arguments 0)])))))
      (binding [*err* err]
        (is (nil? ((ns-resolve namespace 'main))))
        (is (nil? ((ns-resolve namespace 'main) ["hello" "world"]))))
      (is (= "argc=1; argv0=main\nargc=3; argv0=main\n" (str err)))
      (finally (remove-ns (ns-name namespace))))))

(deftest module-struct-results-retain-field-names
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/deffield first-value :u32))
        (eval '(az/deffield second-value :u64))
        (eval '(az/defconst Record (ak/This)))
        (eval '(az/defn make-record Record [[value :u32]]
                 (az/object [[:first-value value] [:second-value (* value 10)]]))))
      (let [result ((ns-resolve namespace 'make-record) 42)]
        (try
          (is (= {:first-value 42 :second-value 420} (az/value result)))
          (finally (az/close! result))))
      (finally (remove-ns (ns-name namespace))))))

(deftest keyword-enum-members-are-callable-values
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defconst Mode
                 (az/container {:kind :enum :argument :c_int}
                   (az/enum-field-decl :idle)
                   (az/enum-field-decl :running))))
        (eval '(az/defn running? :bool [[mode Mode]] (== mode :.running))))
      (let [running? (ns-resolve namespace 'running?)]
        (is (false? (running? :idle)))
        (is (true? (running? :running)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown Zig enum member"
                             (running? :missing))))
      (finally (remove-ns (ns-name namespace))))))

(deftest quoted-native-export-is-looked-up-without-zig-syntax
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defn sentence :i32
                 {:attrs #{:export} :zig/name "@\"A complete sentence.\""}
                 [] 42)))
      (is (= 42 ((ns-resolve namespace 'sentence))))
      (finally (remove-ns (ns-name namespace))))))

(deftest ordinary-java-program-calls-the-same-native-vars
  (let [source (io/file (io/resource "fixtures/jvm/NativeCallSmoke.java"))
        process (.start
                 (doto (ProcessBuilder.
                        ^java.util.List
                        [(str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         (.getAbsolutePath source)])
                   (.redirectErrorStream true)))
        output (slurp (.getInputStream process))]
    (is (zero? (.waitFor process)) output)
    (is (re-find #"Hello, Java!" output))
    (is (re-find #"maximum=true, sqrt=4.0, print=nil" output))))
