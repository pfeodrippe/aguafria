(ns learn.wasm-examples-check
  "Runs the actual Wasm/WASI comment forms in a fresh JVM/working directory."
  (:require [aguafria.zig :as a]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def examples
  {'learn.example.math "The result is 3\n"
   'learn.example.wasi-args "0: wasi_args.wasm\n1: 123\n2: hello\n"
   'learn.example.wasi-preopens "info: 0: stdin\ninfo: 1: stdout\ninfo: 2: stderr\ninfo: 3: .\n"
   'learn.example.test-wasmMemoryGrow-builtin
   "1/1 test_wasmMemoryGrow_builtin.test.wasmMemoryGrow...OK\nAll 1 tests passed.\n"})

(defn- checked-sh [& args]
  (let [result (apply sh/sh args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Wasm validation command failed" (assoc result :command args))))
    result))

(defn- comment-output [namespace]
  (require namespace)
  (let [resource (str (-> (str namespace) (str/replace "." "/") (str/replace "-" "_")) ".clj")
        forms (with-open [reader (java.io.PushbackReader. (io/reader (io/resource resource)))]
                (doall (take-while #(not= ::eof %)
                                   (repeatedly #(read {:eof ::eof} reader)))))
        comments (filter #(and (seq? %) (= 'comment (first %))) forms)]
    (assert (= 1 (count comments)) (str "Expected one runnable comment in " namespace))
    (with-out-str
      (binding [*ns* (the-ns namespace)]
        (doseq [form (rest (first comments))] (eval form))))))

(defn -main [& _]
  (a/configure! {:cache-dir (.getAbsolutePath (io/file "cache"))})
  (doseq [[namespace expected] (sort-by (comp str key) examples)]
    (let [actual (comment-output namespace)]
      (assert (= expected actual) (pr-str {:namespace namespace :expected expected :actual actual}))
      (println namespace :passed)
      (print actual)))
  ;; Compare the same host and inputs against unmodified upstream executables.
  (doseq [[name target options host-args expected]
          [["math" "wasm32-freestanding" ["-fno-entry" "--export=add"] []
            (examples 'learn.example.math)]
           ["wasi_args" "wasm32-wasi" [] ["123" "hello"]
            (examples 'learn.example.wasi-args)]
           ["wasi_preopens" "wasm32-wasi" [] []
            (examples 'learn.example.wasi-preopens)]]]
    (let [source (str "upstream/" name ".zig")
          wasm (str "upstream/" name ".wasm")
          _ (io/make-parents source)
          _ (spit source (slurp (io/resource (str "upstream/doc/langref/" name ".zig"))))
          _ (apply checked-sh (a/zig-executable) "build-exe" source "-target" target
                   (str "-femit-bin=" wasm) options)
          host (if (= name "math") "math.cjs" "wasi.cjs")
          args (concat (when (= name "wasi_preopens") ["--dir=."]) [wasm] host-args)
          result (apply checked-sh "node" "--no-warnings" "-e"
                        (slurp (io/resource (str "learn/host/" host))) "--" args)]
      (assert (= expected (str (:out result) (:err result))) (pr-str result))
      (println :upstream-parity name :passed)))
  ;; The upstream memory-growth test normally skips on native hosts. On wasm32
  ;; its isize/usize comparison fails with this pinned compiler. Preserve the
  ;; original failure, then check a temporary copy with the same checked cast.
  (let [original (slurp (io/resource "upstream/doc/langref/test_wasmMemoryGrow_builtin.zig"))
        source "upstream/memory.zig"
        wasm "upstream/memory.wasm"
        command [(a/zig-executable) "test" source "-target" "wasm32-wasi"
                 "--test-no-exec" (str "-femit-bin=" wasm)]]
    (spit source original)
    (let [result (apply sh/sh command)]
      (assert (not (zero? (:exit result))) "Expected the known upstream type mismatch")
      (assert (str/includes? (:err result) "incompatible types: 'isize' and 'usize'") (:err result))
      (spit "upstream/memory-original-error.txt" (:err result)))
    (let [corrected (str/replace original
                                 "try expectEqual(@wasmMemoryGrow(0, 1), prev);"
                                 "try expectEqual(@as(isize, @intCast(prev)), @wasmMemoryGrow(0, 1));")]
      (assert (not= corrected original))
      (spit source corrected))
    (apply checked-sh command)
    (let [result (checked-sh "node" "--no-warnings" "-e"
                             (slurp (io/resource "learn/host/wasi.cjs")) "--" wasm)]
      (assert (str/includes? (:err result) "All 1 tests passed.") (pr-str result))
      (assert (not (str/includes? (:err result) "SKIP")) (pr-str result)))
    (println :corrected-upstream-memory-test :passed))
  (println :wasm-examples :all-passed)
  (shutdown-agents))
