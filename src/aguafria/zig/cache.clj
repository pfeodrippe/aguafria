(ns aguafria.zig.cache
  "Shared location for content-addressed native artifacts."
  (:require [clojure.java.io :as io]))

(defn default-directory
  "Native artifacts are shared across projects; explicit cache configuration wins."
  []
  (or (System/getProperty "aguafria.cache-dir")
      (str (io/file (System/getProperty "user.home") ".aguafria" "zig"))))
