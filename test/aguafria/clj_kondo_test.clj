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

(def prelude
  "(ns fixture
     (:require [aguafria.zig :as az]
               [aguafria.std.process :as process :refer [Init]]
               [aguafria.std.process.Init :as process-init]))\n")

(deftest native-destructured-bindings-are-visible-to-the-linter
  (is (empty?
       (lint "(ns fixture (:require [aguafria.zig :as az] [aguafria.keyword :as k]))
              (az/defn sum :i32 [[{:keys [x y]} :anytype]] (k/+ x y))
              (az/defn points :void [[items :anytype]]
                (k/for [{:keys [x y]} items]
                  (k/= :_ (k/+ x y))))"))))

(deftest native-block-labels-are-data-not-vars
  (let [findings (lint "(ns fixture (:require [aguafria.zig :as az] [aguafria.keyword :as k]))
                       (let [answer 42]
                         (az/with-block :result
                           (try (k/break :result answer))))")]
    (is (empty? findings) (pr-str findings))))

(deftest native-array-initializer-retains-binding-uses
  (let [source "(ns fixture (:require [aguafria.zig :as az] [aguafria.keyword :as k]))
                (az/defstruct Point [[:x :i32] [:y :i32]])
                (az/defvar fancy-array
                  (az/with-block :init
                    (let [initial-value (k/var k/undefined [:array 10 Point])]
                      (k/for [(k/* point) (k/& initial-value)
                              index (az/range 0)]
                        (k/= @point (Point {:x (k/intCast index)
                                           :y (k/intCast (k/* index 2))})))
                      (k/break :init initial-value))))"
        findings (lint source)]
    (is (empty? findings) (pr-str findings)))
  (testing "unused locals still produce warnings"
    (let [findings (lint "(ns fixture (:require [aguafria.zig :as az]))
                         (az/with-block :result (let [unused 1] 42))")]
      (is (= ["unused binding unused"] (mapv :message findings))))))

(defn- findings-of [kind findings]
  (filter #(= kind (:type %)) findings))

(deftest array-arities-come-from-the-library-definition
  (let [findings
        (lint-with-config
         (io/file (io/resource export-path))
         "(ns fixture (:require [aguafria.zig :as az]))
          (az/array [1 2] :u8)
          (az/array [1 0 0 4] {:sentinel 0} :u8)
          (az/deftest sentinel-array
            (let [array (az/array [1 0 0 4] {:sentinel 0} :u8)]
              (az/get array 4)))
          (az/array [1 2])
          (az/array [1 2] {:sentinel 0} :u8 :extra)"
         [(str (io/file (io/resource "aguafria/zig.clj")))])]
    (is (= [:invalid-arity :invalid-arity] (mapv :type findings))
        (pr-str findings))
    (is (re-find #"called with 1 arg" (:message (first findings))))
    (is (re-find #"called with 4 args" (:message (second findings))))))

(deftest flat-native-loop-captures-are-lexical-bindings
  (let [findings (lint
                  "(ns fixture (:require [aguafria.zig :as az] [aguafria.keyword :as k]))
                   (defn run [items]
                     (k/for [(k/* item) (k/& items) index (az/range 0)]
                       (k/= @item (k/intCast index))))")]
    (is (empty? (filter #(= :error (:level %)) findings)))))

(deftest native-try-is-not-clojure-exception-handling
  (let [findings (lint
                  (str prelude
                       "(az/defn f :!void [] (let [x 1] (try (inc x))))
                            (az/defn- g :!void [] (try (f)))
                            (az/deftest native-test (try (f)))
                            (az/defcomptime startup (try (f)))
                            (az/defstruct S
                              [(az/fn- helper :!void [] (try (f)))
                               (az/fn method :!void [] (try (f)))])
                            (defn host [] (try (inc 1)))"))]
    (is (= 1 (count (findings-of :missing-clause-in-try findings))))
    (is (empty? (filter #(= :error (:level %)) findings)))))

(deftest native-try-still-analyzes-its-contents
  (let [findings (lint
                  (str prelude "(az/defn f :!void [] (try (inc missing 2)))"))]
    (is (= 1 (count (findings-of :unresolved-symbol findings))))
    (is (= 1 (count (findings-of :invalid-arity findings))))
    (is (empty? (findings-of :missing-clause-in-try findings)))))

(deftest host-escape-uses-clojure-not-native-try-semantics
  (let [findings (lint
                 (str prelude
                      "(az/defn valid :i32 []
                         (az/clj! (try (inc 1) (catch Exception _ 0))))
                       (az/defn warning :i32 [] (az/clj! (try (inc 1))))
                       (az/defn unresolved :i32 [] (az/clj! (inc missing)))"))]
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
                           "(az/defn main " return-type " [[init " argument-type "]]
                              (try (identity init)))
                            (main)
                            (main [\"argument\"])
                            (main [] [])"))]
        (is (= 1 (count (findings-of :invalid-arity findings))))
        (is (re-find #"called with 2 args"
                     (:message (first (findings-of :invalid-arity findings)))))
        (is (empty? (findings-of :missing-clause-in-try findings)))))))

(deftest ordinary-functions-keep-their-declared-arity
  (doseq [declaration ["(az/defn ordinary :!void [[init process/Init]] init)"
                       "(az/defn main :i32 [[n :i32]] n)"
                       "(az/defn- main :!void [[init process/Init]] init)"
                       "(az/defn main :!void {:public false} [[init process/Init]] init)"
                       "(az/defn main :!void {:attrs #{:export}} [[init process/Init]] init)"
                       "(az/defextern main :!void [[init process/Init]])"]]
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
                              "(az/defn main :!void [[init process/Init]]
                                 (try (identity init)))
                               (main)
                               (main [] [])"))]
          (is (= 1 (count (findings-of :invalid-arity findings))))
          (is (re-find #"called with 2 args"
                       (:message (first (findings-of :invalid-arity findings)))))
          (is (empty? (findings-of :missing-clause-in-try findings))))))))
