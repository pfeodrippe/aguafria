(ns aguafria.clj-kondo-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipEntry ZipOutputStream]))

(def export-path "clj-kondo.exports/io.github.pfeodrippe/aguafria")

(defn- lint-with-config [config source]
  (let [{:keys [exit out err]}
        (shell/sh "clj-kondo" "--lint" "-" "--filename" "fixture.clj"
                  "--cache" "false" "--config-dir" (str config)
                  "--config" "{:output {:format :edn}}" :in source)]
    (assert (#{0 2 3} exit) (str "clj-kondo failed: " err out))
    (:findings (edn/read-string out))))

(defn- lint [source]
  (lint-with-config (io/file (io/resource export-path)) source))

(def prelude
  "(ns fixture
     (:require [aguafria.zig :as az]
               [aguafria.std.process :as process :refer [Init]]
               [aguafria.std.process.Init :as process-init]))\n")

(defn- findings-of [kind findings]
  (filter #(= kind (:type %)) findings))

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
