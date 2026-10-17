(ns learn.example.test-merging-error-sets
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst A
  (a/type [:error-set [:NotDir
                       ;; A doc comment
                       :PathNotFound]]))

(a/defconst B
  (a/type [:error-set [:OutOfMemory
                       ;; B doc comment
                       :PathNotFound]]))

(a/defconst C (|| A B))

(a/defn- foo [:error-union C :void] []
  (a/error-value :NotDir))

(a/deftest merge-error-sets
  (a/if-capture-stmt {:error [err]} (foo)
                     (panic "unexpected")
                     (switch err
                       (a/error-value :OutOfMemory) (panic "unexpected")
                       (a/error-value :PathNotFound) (panic "unexpected")
                       (a/error-value :NotDir) (a/block))))

(comment
  (merge-error-sets))
