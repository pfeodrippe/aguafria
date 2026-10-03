(ns learn.example.test-merging-error-sets
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst A
  (a/type [:error-set [:NotDir
                       ;; A doc comment
                       :PathNotFound]]))

(a/defconst B
  (a/type [:error-set [:OutOfMemory
                       ;; B doc comment
                       :PathNotFound]]))

(a/defconst C (k/|| A B))

(a/defn- foo [:error-union C :void] []
  (a/error-value :NotDir))

(a/deftest merge-error-sets
  (a/if-capture-stmt {:error [err]} (foo)
                     (k/panic "unexpected")
                     (a/switch-stmt err
                                    (case [(a/error-value :OutOfMemory)] (k/panic "unexpected"))
                                    (case [(a/error-value :PathNotFound)] (k/panic "unexpected"))
                                    (case [(a/error-value :NotDir)] (a/block)))))

(comment
  (merge-error-sets))
