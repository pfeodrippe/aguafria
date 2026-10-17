(ns aguafria.zig.let-destructuring-native-test
  (:require aguafria.keyword
            [aguafria.zig.emitter :as emit]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private native-declarations
  [{:kind :fn :name 'next-pair
    :args [{:name 'calls :type [:* :i32]}]
    :return [:array 2 :i32] :implicit-return? true
    :body '((set! (deref calls) (aguafria.keyword/+ (deref calls) 1))
            (init [20 22] [:array 2 :i32]))}
   {:kind :fn :name 'next-single
    :args [{:name 'calls :type [:* :i32]}]
    :return [:array 1 :i32] :implicit-return? true
    :body '((set! (deref calls) (aguafria.keyword/+ (deref calls) 1))
            (init [7] [:array 1 :i32]))}
   {:kind :fn :name 'tail-sum :args [] :return :i32 :implicit-return? true
    :body '((let [[left right] (init [20 22] [:array 2 :i32])]
              (aguafria.keyword/+ left right)))}
   {:kind :test :name 'destructuring-values-and-evaluation-count
    :test-name "native let destructuring keeps values and evaluation count"
    :body
    '((let [^{:var :i32} calls 0]
        (let [[^{:zig/type :i32} first ^{:var :i32} second] (next-pair (aguafria.keyword/& calls))]
          (set! second (aguafria.keyword/+ second first))
          (when (aguafria.keyword/!= second 42) (return (error-value :WrongMutableLeaf)))
          (when (aguafria.keyword/!= calls 1) (return (error-value :RepeatedInitializer))))
        (let [[_ second] (next-pair (aguafria.keyword/& calls))]
          (when (aguafria.keyword/!= second 22) (return (error-value :WrongDiscardedPair)))
          (when (aguafria.keyword/!= calls 2) (return (error-value :RepeatedDiscardInitializer))))
        (let [[only] (next-single (aguafria.keyword/& calls))]
          (when (aguafria.keyword/!= only 7) (return (error-value :WrongSingleLeaf)))
          (when (aguafria.keyword/!= calls 3) (return (error-value :RepeatedSingleInitializer))))
        (let [[_] (next-single (aguafria.keyword/& calls))]
          (when (aguafria.keyword/!= calls 4) (return (error-value :MissingDiscardEvaluation))))
        (let [^{:var :i32} left 0
              ^{:var :i32} right 0]
          (set! [left right] (next-pair (aguafria.keyword/& calls)))
          (when (aguafria.keyword/!= (aguafria.keyword/+ left right) 42) (return (error-value :WrongAssignment)))
          (when (aguafria.keyword/!= calls 5) (return (error-value :RepeatedAssignment)))
          (set! [left] (next-single (aguafria.keyword/& calls)))
          (when (aguafria.keyword/!= left 7) (return (error-value :WrongSingleAssignment)))
          (when (aguafria.keyword/!= calls 6) (return (error-value :RepeatedSingleAssignment)))
          (set! [:_ right] (next-pair (aguafria.keyword/& calls)))
          (when (aguafria.keyword/!= right 22) (return (error-value :WrongDiscardAssignment)))
          (when (aguafria.keyword/!= calls 7) (return (error-value :RepeatedDiscardAssignment)))
          (set! [left right] (init [right left] [:array 2 :i32]))
          (when (aguafria.keyword/!= left 22) (return (error-value :WrongSwapLeft)))
          (when (aguafria.keyword/!= right 7) (return (error-value :WrongSwapRight))))
        (let [total (aguafria.keyword/+ 1 (let [[left right] (next-pair (aguafria.keyword/& calls))]
                                            (aguafria.keyword/+ left right)))]
          (when (aguafria.keyword/!= total 43) (return (error-value :WrongExpressionScope)))
          (when (aguafria.keyword/!= calls 8) (return (error-value :RepeatedExpressionInitializer))))
        (when (aguafria.keyword/!= (tail-sum) 42) (return (error-value :WrongImplicitReturn)))))}
   {:kind :test :name 'local-comptime-and-alignment
    :test-name "native let metadata preserves comptime and alignment"
    :body
    '((let [^{:var :i32 :zig/prefix "comptime"} count 1
            ^{:var [:array 4 :u8] :align 16} bytes aguafria.keyword/undefined]
        (set! count (aguafria.keyword/+ count 1))
        (set! (index bytes 0) 7)
        (when (aguafria.keyword/!= count 2) (return (error-value :WrongComptimeValue)))
        (when (aguafria.keyword/!= (op "%" (aguafria.keyword/intFromPtr (aguafria.keyword/& bytes)) 16) 0)
          (return (error-value :WrongLocalAlignment)))))}])

(deftest ^:integration generated-destructuring-runs-with-pinned-zig
  (let [directory (.toFile (Files/createTempDirectory
                            "aguafria-let-destructuring-"
                            (make-array FileAttribute 0)))
        source-file (io/file directory "destructuring.zig")
        ;; The three-argument overload prepares forms in the defining namespace.
        source (emit/emit-module (the-ns 'aguafria.zig.let-destructuring-native-test)
                                 "aguafria.zig.let-destructuring-native-test"
                                 native-declarations)]
    ;; Retain the isolated artifact on failure so compiler diagnostics are inspectable.
    (spit source-file source)
    (let [result (shell/sh (runtime/zig-executable)
                           "test" (.getAbsolutePath source-file)
                           "-Osafe"
                           "--cache-dir" (.getAbsolutePath (io/file directory "cache")))]
      (is (zero? (:exit result))
          (str "Native destructuring regression failed at " source-file "\n"
               (:out result) (:err result))))))
