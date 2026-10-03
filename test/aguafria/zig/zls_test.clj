(ns aguafria.zig.zls-test
  (:require [aguafria.zig.toolchain :as toolchain]
            [aguafria.zig.zls :as zls]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(deftest incompatible-zls-is-rejected-before-initialization
  (with-redefs [shell/sh (fn [& _] {:exit 0 :out "0.16.0\n" :err ""})
                toolchain/manifest (constantly {:zig-version "0.17.0"})]
    (let [failure (try (zls/start! "must-not-start")
                       (catch clojure.lang.ExceptionInfo e e))]
      (is (= :zls-version (:aguafria/phase (ex-data failure))))
      (is (= "0.17.0" (:expected (ex-data failure))))
      (is (= "0.16.0" (:actual (ex-data failure)))))))
