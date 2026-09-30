(ns aguafria.zig.cache-test
  (:require [aguafria.zig.cache :as cache]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest shared-native-cache-default
  (let [previous (System/getProperty "aguafria.cache-dir")]
    (try
      (System/clearProperty "aguafria.cache-dir")
      (is (= (str (io/file (System/getProperty "user.home") ".aguafria" "zig"))
             (cache/default-directory)))
      (System/setProperty "aguafria.cache-dir" "/explicit/native-cache")
      (is (= "/explicit/native-cache" (cache/default-directory)))
      (finally
        (if previous
          (System/setProperty "aguafria.cache-dir" previous)
          (System/clearProperty "aguafria.cache-dir"))))))
