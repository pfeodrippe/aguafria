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
