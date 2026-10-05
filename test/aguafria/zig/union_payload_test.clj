(ns aguafria.zig.union-payload-test
  (:require [aguafria.zig.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.lang.foreign Arena MemorySegment ValueLayout]
           [java.nio.file Files]))

(deftest union-initializer-payload-never-aliases-destination
  (with-open [arena (Arena/ofConfined)]
    (let [destination (.allocate arena 128 64)
          observed (atom nil)
          schema {:kind :union :alignment 64
                  :fields [{:name :ok :type :u8 :byte-size 1
                            :init-fn
                            (fn [^MemorySegment target ^MemorySegment payload]
                              (reset! observed {:payload payload :address (.address payload)})
                              ;; Model the legal optimized tag-before-payload store.
                              (.fill target (byte 0))
                              (.set target ValueLayout/JAVA_BYTE 1
                                    (.get payload ValueLayout/JAVA_BYTE 0)))}]}]
      (value/write-union! destination schema {:ok 42})
      (is (= 42 (.get destination ValueLayout/JAVA_BYTE 1)))
      (is (not= (.address destination) (:address @observed)))
      (is (zero? (mod (:address @observed) 64)))
      (is (thrown? IllegalStateException
                   (.get ^MemorySegment (:payload @observed) ValueLayout/JAVA_BYTE 0))))))

(deftest union-encoding-fails-before-mutating-destination
  (with-open [arena (Arena/ofConfined)]
    (let [destination (.allocate arena 8 8)
          invoked? (atom false)
          schema {:kind :union
                  :fields [{:name :ok :type :u8 :byte-size 1
                            :init-fn (fn [& _] (reset! invoked? true))}]}]
      (doseq [alignment [nil 0 -1 3]]
        (.fill destination (byte 99))
        (is (thrown? clojure.lang.ExceptionInfo
                     (value/write-union! destination (assoc schema :alignment alignment) {:ok 42})))
        (is (= 99 (.get destination ValueLayout/JAVA_BYTE 0))))
      (.fill destination (byte 99))
      (is (thrown? clojure.lang.ExceptionInfo
                   (value/write-union! destination (assoc schema :alignment 8) {:ok 256})))
      (is (= 99 (.get destination ValueLayout/JAVA_BYTE 0)))
      (is (false? @invoked?)))))

(deftest void-union-fields-do-not-need-payload-storage
  (with-open [arena (Arena/ofConfined)]
    (let [destination (.allocate arena 1 1)
          payloads (atom [])
          schema {:kind :union
                  :fields [{:name :empty :type :void :byte-size 0
                            :init-fn (fn [_ payload] (swap! payloads conj payload))}]}]
      (value/write-union! destination schema {:empty nil})
      (is (= [nil] @payloads))
      (.fill destination (byte 99))
      (is (thrown? clojure.lang.ExceptionInfo
                   (value/write-union! destination schema {:empty 1})))
      (is (= 99 (.get destination ValueLayout/JAVA_BYTE 0)))
      (is (= [nil] @payloads)))))

(deftest union-slice-pointees-stay-in-the-callers-arena
  (with-open [arena (Arena/ofConfined)]
    (let [destination (.allocate arena 16 8)
          pointee (atom nil)
          schema {:kind :union :alignment 8
                  :fields [{:name :bytes :type [:slice :u8] :byte-size 16
                            :schema {:kind :slice :element-type :u8
                                     :element-size 1 :element-alignment 1
                                     :set-fn (fn [^MemorySegment payload ^MemorySegment backing length]
                                               (reset! pointee backing)
                                               (.set payload ValueLayout/JAVA_LONG 0 (.address backing))
                                               (.set payload ValueLayout/JAVA_LONG 8 (long length)))}
                            :init-fn (fn [^MemorySegment target ^MemorySegment payload]
                                       (.copyFrom target payload))}]}]
      (value/write-value! destination 'NativeUnion schema {:bytes [42 43]} arena)
      (is (= (.address ^MemorySegment @pointee) (.get destination ValueLayout/JAVA_LONG 0)))
      (is (= 2 (.get destination ValueLayout/JAVA_LONG 8)))
      (is (= 42 (.get ^MemorySegment @pointee ValueLayout/JAVA_BYTE 0)))
      (is (= 43 (.get ^MemorySegment @pointee ValueLayout/JAVA_BYTE 1))))))

(deftest fresh-optimized-native-union-construction-and-pointer-mutation
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "union-payload-regression-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        body
        '(with-open [seed (Payload {:ok 42})
                     mutable (k/var seed)
                     empty (Payload {:empty nil})
                     aligned (AlignedPayload {:aligned {:value 42}})
                     sliced (SlicePayload {:bytes [42 43]})]
           (let [before (a/value mutable)
                 result (a/value (increment! (k/& mutable)))]
             (when (and (map? result) (contains? result :error))
               (throw (ex-info "Native mutation returned an error" {:result result})))
             {:constructor (a/value seed) :before before :after (a/value mutable)
              :void (a/value empty) :aligned (a/value aligned)
              :alignment (:alignment (aguafria.zig.value/realize! aligned))
              :schema-alignment (:alignment (:schema (aguafria.zig.value/realize! aligned)))
              :slice (a/value sliced)}))
        code
        `(do
           (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.keyword 'aguafria.zig.value)
           (aguafria.zig.runtime/configure! {:cache-dir ~cache :optimize "fast" :jvm-optimize "safe"})
           (binding [aguafria.zig.runtime/*source-only-registration?* true]
             (require 'aguafria.zig.union-payload-fixture))
           (binding [*ns* (the-ns 'aguafria.zig.union-payload-fixture)]
             (prn (eval '~body)))
           (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (spit (io/file cache "native-result.edn") (pr-str result))
    (is (zero? (:exit result)) (:err result))
    (when (zero? (:exit result))
      (is (= {:constructor {:ok 42} :before {:ok 42} :after {:ok 43}
              :void {:empty nil} :aligned {:aligned {:value 42}}
              :alignment 64 :schema-alignment 64 :slice {:bytes [42 43]}}
             (edn/read-string (:out result)))))))
