(ns aguafria.clj-kondo-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipEntry ZipOutputStream]))

(def export-path "clj-kondo.exports/io.github.pfeodrippe/aguafria")

(defn- lint-with-config
  ([config source]
   (lint-with-config config source []))
  ([config source dependencies]
   (let [{:keys [exit out err]}
         (apply shell/sh
                (concat ["clj-kondo" "--lint"] dependencies
                        ["-" "--filename" "fixture.clj"
                         "--cache" "false" "--config-dir" (str config)
                         "--config" "{:output {:format :edn}}" :in source]))]
     (assert (#{0 2 3} exit) (str "clj-kondo failed: " err out))
     (filterv #(= "fixture.clj" (:filename %))
              (:findings (edn/read-string out))))))

(defn- lint [source]
  (lint-with-config (io/file (io/resource export-path)) source))

(deftest c-binding-macro-defines-vars-and-checks-host-code
  (is (empty? (lint "(ns fixture (:require [aguafria.c :as ac]))
                     (ac/defbindings api (ac/import! \"fixture\" \"fixture.h\" {})
                       [native_point point_sum])
                     (point_sum (native_point {:x 1 :y 2})) api")))
  (let [findings (lint "(ns fixture (:require [aguafria.c :as ac]))
                        (ac/defbindings api (missing-import) [native_point])")]
    (is (= [:unresolved-symbol] (mapv :type findings)))
    (is (re-find #"missing-import" (:message (first findings))))))

(def prelude
  "(ns fixture
     (:require [aguafria.zig :as a]
               [aguafria.std.process :as process :refer [Init]]
               [aguafria.std.process.Init :as process-init]))\n")

(deftest explain-wrapper-preserves-linting
  (is (empty?
       (lint "(ns fixture (:require [aguafria.zig :as a]))
              (a/explain! (def answer 42) (+ answer 1))"))))

(deftest named-union-declarations-register-real-vars
  (is (empty?
       (lint "(ns fixture (:require [aguafria.zig :as a] [aguafria.keyword :as k]))
              (a/defunion Payload \"Payload docs.\" {:attrs #{k/enum}}
                [[:int {:doc \"Integer\"} :i32] [:empty :void]])
              (Payload {:int 42})"))))

(deftest native-destructured-bindings-are-visible-to-the-linter
  (is (empty?
       (lint "(ns fixture (:require [aguafria.zig :as a] [aguafria.keyword :as k]))
              (a/defn sum :i32 [[{:keys [x y]} :anytype]] (k/+ x y))
              (a/defn points :void [[items :anytype]]
                (k/for [{:keys [x y]} items]
                  (k/= :_ (k/+ x y))))"))))

(deftest native-block-labels-are-data-not-vars
  (let [findings (lint "(ns fixture (:require [aguafria.zig :as a] [aguafria.keyword :as k]))
                       (let [answer 42]
                         (a/with-block :result
                           (try (k/break :result answer))))")]
    (is (empty? findings) (pr-str findings))))

(deftest native-payload-and-error-captures-are-lexical-bindings
  (let [findings
        (lint "(ns fixture (:require [aguafria.zig :as a] [aguafria.keyword :as k]))
               (let [source 1 bias 2]
                 (a/block (try (k/+ source bias)))
                 (a/if-capture {:payload [item] :error [err]} source
                   (k/+ item bias) err)
                 (a/if-capture-stmt {:payload [item]} source (k/= :_ item))
                 (a/catch-capture [err] source (k/+ bias err)))")]
    (is (empty? findings) (pr-str findings))))

(deftest native-array-initializer-retains-binding-uses
  (let [source "(ns fixture (:require [aguafria.zig :as a] [aguafria.keyword :as k]))
                (a/defstruct Point [[:x :i32] [:y :i32]])
                (a/defvar fancy-array
                  (a/with-block :init
                    (let [initial-value (k/var k/undefined [:array 10 Point])]
                      (k/for [(k/* point) (k/& initial-value)
                              index (a/range 0)]
                        (k/= @point (Point {:x (k/intCast index)
                                           :y (k/intCast (k/* index 2))})))
                      (k/break :init initial-value))))"
        findings (lint source)]
    (is (empty? findings) (pr-str findings)))
  (testing "unused locals still produce warnings"
    (let [findings (lint "(ns fixture (:require [aguafria.zig :as a]))
                         (a/with-block :result (let [unused 1] 42))")]
      (is (= ["unused binding unused"] (mapv :message findings))))))

(deftest native-switch-and-loop-captures-are-lexical
  (let [findings
        (lint "(ns fixture (:require [aguafria.zig :as a] [aguafria.keyword :as k]))
               (let [source 1 state 0]
                 (a/switch-stmt source
                   (case [:.ok] [(a/pointer-capture value)] (k/+= @value state))
                   (case-else (k/= state 1)))
                 (a/labeled-switch vm source
                   (case [0] (k/continue vm state))
                   (case-else state))
                 (a/labeled-switch-stmt exit source
                   (case [0] (a/break-label exit))
                   (case-else (k/= state 1)))
                 (a/while-loop {:label loop :payload [item] :error [err]
                                :continue (k/+= state item) :else [(k/= state err)]}
                   source (k/= state item) (k/continue loop)))")]
    (is (empty? findings) (pr-str findings)))
  (let [findings (lint "(ns fixture (:require [aguafria.zig :as a]))
                       (let [source 1 unused 2]
                         (a/switch source (case [0] source) (case-else source)))")]
    (is (= ["unused binding unused"] (mapv :message findings)))))

(defn- findings-of [kind findings]
  (filter #(= kind (:type %)) findings))

(deftest array-arities-come-from-the-library-definition
  (let [findings
        (lint-with-config
         (io/file (io/resource export-path))
         "(ns fixture (:require [aguafria.zig :as a]))
          (a/array [1 2] :u8)
          (a/array [1 0 0 4] {:sentinel 0} :u8)
          (a/deftest sentinel-array
            (let [array (a/array [1 0 0 4] {:sentinel 0} :u8)]
              (a/get array 4)))
          (a/array [1 2])
          (a/array [1 2] {:sentinel 0} :u8 :extra)"
         [(str (io/file (io/resource "aguafria/zig.clj")))])]
    (is (= [:invalid-arity :invalid-arity] (mapv :type findings))
        (pr-str findings))
    (is (re-find #"called with 1 arg" (:message (first findings))))
    (is (re-find #"called with 4 args" (:message (second findings))))))

(deftest flat-native-loop-captures-are-lexical-bindings
  (let [findings (lint
                  "(ns fixture (:require [aguafria.zig :as a] [aguafria.keyword :as k]))
                   (defn run [items]
                     (k/for [(k/* item) (k/& items) index (a/range 0)]
                       (k/= @item (k/intCast index))))")]
    (is (empty? (filter #(= :error (:level %)) findings)))))

(deftest scoped-native-for-owners-preserve-lexical-uses
  (let [findings
        (lint "(ns fixture (:require [aguafria.zig :as a] [aguafria.keyword :as k]))
               (let [items [1 2] total 0 outer 42]
                 (a/inline-for [item items] (k/+= total item))
                 (a/for-loop {:label outer :body-label body}
                   [(k/* item) (k/& items) index (a/range 0)]
                   (a/for-loop {:label inner} [value items]
                     (k/= @item (k/+ index value outer))
                     (k/continue outer))
                   (a/break-label body)
                   (else-expression total)))")]
    (is (empty? findings) (pr-str findings)))
  (let [findings
        (lint "(ns fixture (:require [aguafria.zig :as a]))
               (let [items [1] unused 2]
                 (a/for-loop {:label exit} [item items] item))")]
    (is (= ["unused binding unused"] (mapv :message findings))))
  (let [findings
        (lint "(ns fixture (:require [aguafria.zig :as a]))
               (let [items [1]]
                 (a/for-loop {} [item items] item (else-expression item)))")]
    (is (= [:unresolved-symbol] (mapv :type findings)) (pr-str findings))
    (is (re-find #"item" (:message (first findings))))))

(deftest native-try-is-not-clojure-exception-handling
  (let [findings (lint
                  (str prelude
                       "(a/defn f :!void [] (let [x 1] (try (inc x))))
                            (a/defn- g :!void [] (try (f)))
                            (a/deftest native-test (try (f)))
                            (a/defcomptime startup (try (f)))
                            (a/defstruct S
                              [(a/fn- helper :!void [] (try (f)))
                               (a/fn method :!void [] (try (f)))])
                            (defn host [] (try (inc 1)))"))]
    (is (= 1 (count (findings-of :missing-clause-in-try findings))))
    (is (empty? (filter #(= :error (:level %)) findings)))))

(deftest native-try-still-analyzes-its-contents
  (let [findings (lint
                  (str prelude "(a/defn f :!void [] (try (inc missing 2)))"))]
    (is (= 1 (count (findings-of :unresolved-symbol findings))))
    (is (= 1 (count (findings-of :invalid-arity findings))))
    (is (empty? (findings-of :missing-clause-in-try findings)))))

(deftest host-escape-uses-clojure-not-native-try-semantics
  (let [findings (lint
                  (str prelude
                       "(a/defn valid :i32 []
                         (a/clj! (try (inc 1) (catch Exception _ 0))))
                       (a/defn warning :i32 [] (a/clj! (try (inc 1))))
                       (a/defn unresolved :i32 [] (a/clj! (inc missing)))"))]
    (is (= 1 (count (findings-of :missing-clause-in-try findings))))
    (is (= 1 (count (findings-of :unresolved-symbol findings))))))

(deftest process-main-exposes-both-jvm-arities
  (doseq [[return-type argument-type]
          [[":!void" "process/Init"]
           ["[:! :void]" "Init"]
           ["[:error-union :void]" "aguafria.std.process/Init"]
           [":!void" "process-init/Minimal"]]]
    (testing (str return-type " " argument-type)
      (let [findings (lint
                      (str prelude
                           "(a/defn main " return-type " [[init " argument-type "]]
                              (try (identity init)))
                            (main)
                            (main [\"argument\"])
                            (main [] [])"))]
        (is (= 1 (count (findings-of :invalid-arity findings))))
        (is (re-find #"called with 2 args"
                     (:message (first (findings-of :invalid-arity findings)))))
        (is (empty? (findings-of :missing-clause-in-try findings)))))))

(deftest ordinary-functions-keep-their-declared-arity
  (doseq [declaration ["(a/defn ordinary :!void [[init process/Init]] init)"
                       "(a/defn main :i32 [[n :i32]] n)"
                       "(a/defn- main :!void [[init process/Init]] init)"
                       "(a/defn main :!void {:public false} [[init process/Init]] init)"
                       "(a/defn main :!void {:attrs #{:export}} [[init process/Init]] init)"
                       "(a/defextern main :!void [[init process/Init]])"]]
    (let [function-name (if (.contains declaration "ordinary") "ordinary" "main")
          findings (lint (str prelude declaration "(" function-name ")"))]
      (is (= 1 (count (findings-of :invalid-arity findings))) declaration))))

(deftest shared-hooks-import-from-either-artifact
  (doseq [artifact ["aguafria-macos-aarch64" "aguafria-linux-x86-64"]]
    (testing artifact
      (let [directory (.toFile (Files/createTempDirectory
                                "aguafria-kondo-" (make-array FileAttribute 0)))
            jar (io/file directory (str artifact ".jar"))
            config (io/file directory ".clj-kondo")]
        (.mkdirs config)
        (with-open [output (ZipOutputStream. (io/output-stream jar))]
          (doseq [file ["config.edn" "hooks/aguafria.clj"]]
            (.putNextEntry output (ZipEntry. (str export-path "/" file)))
            (with-open [input (io/input-stream (io/resource (str export-path "/" file)))]
              (io/copy input output))
            (.closeEntry output)))
        (let [result (shell/sh "clj-kondo" "--lint" (str jar)
                               "--config-dir" (str config)
                               "--copy-configs" "--skip-lint" "--cache" "false")]
          (is (zero? (:exit result)) (pr-str result)))
        (doseq [file ["config.edn" "hooks/aguafria.clj"]]
          (is (= (slurp (io/resource (str export-path "/" file)))
                 (slurp (io/file config "imports" "io.github.pfeodrippe" "aguafria" file)))))
        (let [findings (lint-with-config config
                                         (str prelude
                                              "(a/defn main :!void [[init process/Init]]
                                 (try (identity init)))
                               (main)
                               (main [] [])"))]
          (is (= 1 (count (findings-of :invalid-arity findings))))
          (is (re-find #"called with 2 args"
                       (:message (first (findings-of :invalid-arity findings)))))
          (is (empty? (findings-of :missing-clause-in-try findings))))))))
