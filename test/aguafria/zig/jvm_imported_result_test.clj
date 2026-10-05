(ns aguafria.zig.jvm-imported-result-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.Io.File :as file]
            [aguafria.std.Thread :as thread]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.signature :as signature]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(defn- prepare-without-invocation! [call]
  (let [fail! (fn [& _] (throw (ex-info "Preparation invoked native code" {})))]
    (binding [runtime/*compile-only?* true]
      (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
        (jvm/precompile-call! call)))))

(a/defn thread-body :void [])

(deftest imported-result-readers-retain-the-actual-zig-reference
  (doseq [v [#'file/stdout #'thread/spawn #'mem/print]]
    (let [reference (:aguafria/zig-reference (meta v))
          expression (#'jvm/call-result-expression
                      (with-meta (list (:symbol reference))
                        {:aguafria/zig-reference reference})
                      (signature/callable-declaration reference) [] reference)
          type (:aguafria/jvm-result-type (meta expression))
          producer (some #(when (= (:symbol reference) %) %)
                         (tree-seq coll? seq type))]
      (is (some? type) (str (:symbol reference)))
      (is (= reference (:aguafria/zig-reference (meta producer)))
          (str (:symbol reference))))))

(deftest std-file-results-prepare-and-round-trip-without-recompiling
  (doseq [[function expected-handle] [[#'file/stdout 1] [#'file/stderr 2]]]
    (is (= :prepared
           (:status (prepare-without-invocation!
                     {:function (get-in (meta function) [:aguafria/zig-reference :symbol])
                      :args []}))))
    (let [events (atom [])
          snapshot (binding [explain/*reporter* #(swap! events conj %)]
                     (with-open [result (function)] (a/value result)))]
      (is (= expected-handle (:handle snapshot)))
      (is (map? (:flags snapshot)))
      (is (not-any? #(= :compiled (:event %)) @events) (pr-str @events)))))

(deftest imported-error-union-results-preserve-their-payload-types
  (is (= :prepared
         (:status (prepare-without-invocation!
                   {:function 'aguafria.std.Thread/spawn
                    :args [{:comptime {}}
                           {:comptime-expression 'aguafria.zig.jvm-imported-result-test/thread-body}
                           {:tuple []}]}))))
  (with-open [result (:ok (thread/spawn {} thread-body []))]
    (is (nil? ((:join result)))))
  (is (= :prepared
         (:status (prepare-without-invocation!
                   {:function 'aguafria.std.mem/print
                    :args [[:slice :u8] {:comptime "{s} {s}"}
                           {:tuple [[:slice-const :u8] [:slice-const :u8]]}]}))))
  (let [buffer (k/var k/undefined [:array 100 :u8])
        start (k/var 0 :usize)
        result (:ok (mem/print (a/slice buffer start) "{s} {s}" ["hello" "世界"]))]
    (is (= "hello 世界" (a/value result)))))

(defn- imported-result-jvm [cache prepare?]
  (let [code `(do
                (require 'aguafria.zig 'aguafria.zig.explain 'aguafria.zig.runtime
                         'aguafria.std.Io.File)
                (aguafria.zig/configure! {:cache-dir ~cache})
                (if ~prepare?
                  (with-redefs [aguafria.zig.runtime/invoke!
                                (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                                aguafria.zig.runtime/invoke-with-result!
                                (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))]
                    (let [report# (aguafria.zig/precompile!
                                   {:calls [{:function 'aguafria.std.Io.File/stdout :args []}
                                            {:function 'aguafria.std.Io.File/stderr :args []}]})]
                      (prn {:statuses (mapv :status (:calls report#))})))
                  (let [events# (atom [])
                        handles# (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                                   (mapv (fn [function#]
                                           (with-open [result# (function#)]
                                             (:handle (aguafria.zig/value result#))))
                                         [aguafria.std.Io.File/stdout
                                          aguafria.std.Io.File/stderr]))]
                    (prn {:handles handles# :events @events#})))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Imported result JVM failed" result)))
    (edn/read-string (:out result))))

(deftest imported-result-readers-use-the-bundle-in-a-fresh-jvm
  (let [cache (str (java.nio.file.Files/createTempDirectory
                    "aguafria-imported-results-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (imported-result-jvm cache true)
        restarted (imported-result-jvm cache false)
        events (:events restarted)
        hits (filterv #(= :bundle-cache-hit (:event %)) events)
        library (:path (first (filter #(= :bundle-loaded (:event %)) events)))
        entries (when library
                  (set (keys (:entries
                              (edn/read-string
                               (slurp (io/file (.getParentFile (io/file library))
                                               "manifest.edn")))))))]
    (is (= [:prepared :prepared] (:statuses prepared)))
    (is (= [1 2] (:handles restarted)))
    (is (not-any? #(#{:compiled :compile-failed :disk-cache-hit} (:event %)) events)
        (pr-str events))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (seq hits))
    (is (every? #(contains? entries
                            (artifact/key-for
                             :bundle-entry [(:module %) (:artifact-key %)])) hits))))
